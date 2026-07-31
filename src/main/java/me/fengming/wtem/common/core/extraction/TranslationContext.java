package me.fengming.wtem.common.core.extraction;

import com.google.gson.GsonBuilder;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;
import me.fengming.wtem.common.config.WtemConfig;

/**
 * @author FengMing
 */
public final class TranslationContext {
    private static final ThreadLocal<State> STATE = ThreadLocal.withInitial(State::new);
    private static final GsonBuilder LANGUAGE_GSON =
            new GsonBuilder().setPrettyPrinting().disableHtmlEscaping();

    private TranslationContext() {}

    public static void clear() {
        STATE.set(new State());
    }

    public static void release() {
        STATE.remove();
    }

    public static int getTypeCounts(String type) {
        return state().typeCounts.getOrDefault(type, 1);
    }

    public static void increaseTypeCounts(String type) {
        // Counting starts at 1, so the stored value has to be derived from the same default that
        // getTypeCounts reports. Merging onto an absent key would store 1 again and waste the first
        // increment, making the second occurrence of a type reuse index 1.
        state().typeCounts.put(type, getTypeCounts(type) + 1);
    }

    public static int nextTypeCount(String type) {
        int count = getTypeCounts(type);
        increaseTypeCounts(type);
        return count;
    }

    public static void setKey(String key) {
        state().pathStack.clear();
        append(key);
    }

    public static String getKey() {
        return currentPath();
    }

    /**
     * Adds a translation entry using the current path as its base key.
     *
     * <p>The allocation order follows the upstream extractor: the first use keeps the base key, while
     * subsequent uses receive {@code .1}, {@code .2}, and so on. Identical text reuses the key it was
     * first given, unless the configured reuse policy or {@code keepDuplicates} opts out.
     */
    public static String addEntry(String value) {
        return store(currentPath(), value, TranslationContext::allocateKey);
    }

    /** Compatibility entry point for callers that already have a base key. */
    public static String addKey(String key, String value) {
        return store(key, value, TranslationContext::uniqueKey);
    }

    private static String store(
            String baseKey, String value, UnaryOperator<String> keyAllocator) {
        State state = state();
        boolean reuse = !state.keepDuplicates && state.keyReuse.allows(baseKey);
        if (reuse) {
            String existing = state.textToKey.get(value);
            if (existing != null) return existing;
        }

        String key = keyAllocator.apply(baseKey);
        state.languageEntries.put(key, value);
        if (reuse) state.textToKey.put(value, key);
        return key;
    }

    public static String exportLanguage() {
        return LANGUAGE_GSON.create().toJson(state().languageEntries);
    }

    public static Map<String, String> snapshot() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(state().languageEntries));
    }

    public static void setKeepDuplicates(boolean keepDuplicates) {
        state().keepDuplicates = keepDuplicates;
    }

    public static boolean isKeepingDuplicates() {
        return state().keepDuplicates;
    }

    /** Applies the configured per-key reuse policy to subsequent entries. */
    public static void setKeyReuse(WtemConfig.KeyReuse keyReuse) {
        state().keyReuse = keyReuse;
    }

    public static void revert() {
        Deque<String> pathStack = state().pathStack;
        if (pathStack.size() > 1) pathStack.removeLast();
    }

    public static void append(String path) {
        if (path == null || path.isBlank()) return;
        state().pathStack.addLast(path);
    }

    public static Scope push(String path) {
        List<String> previous = new ArrayList<>(state().pathStack);
        append(path);
        return new Scope(previous);
    }

    public static Scope pushKey(String key) {
        List<String> previous = new ArrayList<>(state().pathStack);
        setKey(key);
        return new Scope(previous);
    }

    /**
     * Starts a nested transaction for key allocation and language entries.
     *
     * <p>Closing an uncommitted transaction restores the complete context state. This keeps failed
     * codec conversions and failed resource writes from consuming keys or leaking orphan entries.
     */
    public static Transaction beginTransaction() {
        return new Transaction(state().copy());
    }

    private static String currentPath() {
        Deque<String> pathStack = state().pathStack;
        if (pathStack.isEmpty()) return "no_key";
        return String.join(".", pathStack);
    }

    private static String allocateKey(String baseKey) {
        Map<String, Integer> keyCounts = state().keyCounts;
        int count = keyCounts.getOrDefault(baseKey, 0);
        keyCounts.put(baseKey, count + 1);
        return count == 0 ? baseKey : baseKey + "." + count;
    }

    private static String uniqueKey(String baseKey) {
        State state = state();
        if (!state.languageEntries.containsKey(baseKey)) {
            state.keyCounts.putIfAbsent(baseKey, 1);
            return baseKey;
        }
        return allocateKey(baseKey);
    }

    private static State state() {
        return STATE.get();
    }

    private static final class State {
        private final Map<String, Integer> keyCounts = new LinkedHashMap<>();
        private final Map<String, String> languageEntries = new LinkedHashMap<>();
        private final Map<String, String> textToKey = new LinkedHashMap<>();
        private final Map<String, Integer> typeCounts = new LinkedHashMap<>();
        private final Deque<String> pathStack = new ArrayDeque<>();
        private boolean keepDuplicates;
        private WtemConfig.KeyReuse keyReuse = WtemConfig.KeyReuse.DEFAULT;

        private State copy() {
            State copy = new State();
            copy.keyCounts.putAll(this.keyCounts);
            copy.languageEntries.putAll(this.languageEntries);
            copy.textToKey.putAll(this.textToKey);
            copy.typeCounts.putAll(this.typeCounts);
            copy.pathStack.addAll(this.pathStack);
            copy.keepDuplicates = this.keepDuplicates;
            copy.keyReuse = this.keyReuse;
            return copy;
        }
    }

    public static final class Transaction implements AutoCloseable {
        private final State rollbackState;
        private boolean committed;
        private boolean closed;

        private Transaction(State rollbackState) {
            this.rollbackState = rollbackState;
        }

        public void commit() {
            if (this.closed) throw new IllegalStateException("Transaction is already closed");
            this.committed = true;
        }

        @Override
        public void close() {
            if (this.closed) return;
            this.closed = true;
            if (!this.committed) STATE.set(this.rollbackState);
        }
    }

    public static final class Scope implements AutoCloseable {
        private final List<String> previousPath;
        private boolean closed;

        private Scope(List<String> previousPath) {
            this.previousPath = previousPath;
        }

        @Override
        public void close() {
            if (this.closed) return;
            this.closed = true;
            Deque<String> pathStack = state().pathStack;
            pathStack.clear();
            pathStack.addAll(this.previousPath);
        }
    }
}
