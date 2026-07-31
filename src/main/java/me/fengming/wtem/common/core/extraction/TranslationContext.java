package me.fengming.wtem.common.core.extraction;

import com.google.gson.GsonBuilder;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
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

    /**
     * Replaces the key below the pinned base.
     *
     * <p>Handlers name their own output from scratch rather than extending whatever key their caller
     * happened to be using, which is what keeps a key stable no matter where the data was found.
     * {@link #pinKey()} makes that restart stop at a prefix instead of at the root.
     */
    public static void setKey(String key) {
        State state = state();
        while (state.pathStack.size() > state.baseDepth) state.pathStack.removeLast();
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
        State state = state();
        String path = currentPath();
        boolean reuse = !state.keepDuplicates && state.keyReuse.allows(path);
        if (reuse) {
            String existing = state.textToKey.get(value);
            if (existing != null) return existing;
        }

        String key =
                state.keyNaming.scheme() == WtemConfig.KeyNaming.Scheme.RANDOM
                        ? randomKey(state)
                        : allocateKey(state, state.keyNaming.baseKey(path));
        state.languageEntries.put(key, value);
        if (reuse) state.textToKey.put(value, key);
        return key;
    }

    public static void setKeyNaming(WtemConfig.KeyNaming keyNaming) {
        state().keyNaming = keyNaming;
    }

    /**
     * Seeds the catalog with entries that extracted text may reuse.
     *
     * <p>The entries are registered as if they had been extracted, so text matching one of them
     * receives the seeded key instead of a fresh one. Their keys are also marked as taken, which keeps
     * a later allocation from appending a suffix onto a name a seeded entry already holds.
     *
     * <p>An entry whose text duplicates an earlier one keeps its key in the catalog but does not take
     * over the reuse mapping: the first entry named a piece of text, and the second renaming it would
     * make the result depend on the order the file happened to be written in.
     */
    public static void setBuiltinEntries(Map<String, String> entries) {
        State state = state();
        entries.forEach(
                (key, value) -> {
                    state.languageEntries.put(key, value);
                    state.keyCounts.putIfAbsent(key, 1);
                    state.textToKey.putIfAbsent(value, key);
                });
        state.builtinEntryCount = state.languageEntries.size();
    }

    /**
     * Counts the entries extraction produced, excluding the seeded ones.
     *
     * <p>Seeded entries exist before anything is read, so counting them would report progress for a
     * run that found nothing.
     */
    public static int extractedEntryCount() {
        State state = state();
        return state.languageEntries.size() - state.builtinEntryCount;
    }

    /**
     * Draws an unused random key.
     *
     * <p>The alphabet is small enough that collisions are expected once a catalog grows, so a taken
     * key is redrawn rather than suffixed: a suffix would make the key describe its allocation order,
     * which is the one thing a random key is chosen to avoid.
     */
    private static String randomKey(State state) {
        int length = state.keyNaming.randomLength();
        for (int attempt = 0; attempt < 64; attempt++) {
            String candidate = WtemConfig.KeyNaming.randomKey(state.random, length);
            if (!state.languageEntries.containsKey(candidate)) return candidate;
        }
        // Every draw at this length is taken. Growing the key is the only way to stay unique, and is
        // preferable to overwriting an existing entry.
        return WtemConfig.KeyNaming.randomKey(state.random, length + 1);
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

    public static void setKeyReuse(WtemConfig.KeyReuse keyReuse) {
        state().keyReuse = keyReuse;
    }

    public static void append(String path) {
        if (path == null || path.isBlank()) return;
        state().pathStack.addLast(path);
    }

    public static Scope push(String path) {
        State state = state();
        List<String> previous = new ArrayList<>(state.pathStack);
        append(path);
        return new Scope(previous, state.baseDepth);
    }

    public static Scope pushKey(String key) {
        State state = state();
        List<String> previous = new ArrayList<>(state.pathStack);
        setKey(key);
        return new Scope(previous, state.baseDepth);
    }

    /**
     * Keeps the current path as a base that a nested {@link #setKey} will not discard.
     *
     * <p>Data nested inside an item stack is reached through a key that already says where it came
     * from. A handler that restarts its key would throw that away, so the caller can pin the prefix
     * and have the handler extend it instead.
     */
    public static Scope pinKey() {
        State state = state();
        List<String> previous = new ArrayList<>(state.pathStack);
        int previousBaseDepth = state.baseDepth;
        state.baseDepth = state.pathStack.size();
        return new Scope(previous, previousBaseDepth);
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

    private static String allocateKey(State state, String baseKey) {
        Map<String, Integer> keyCounts = state.keyCounts;
        int count = keyCounts.getOrDefault(baseKey, 0);
        keyCounts.put(baseKey, count + 1);
        return count == 0 ? baseKey : baseKey + "." + count;
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
        // How much of the path a restart keeps. Zero means a restart discards the whole path.
        private int baseDepth;
        // How many entries were seeded rather than extracted, so a report can tell the two apart.
        private int builtinEntryCount;
        private boolean keepDuplicates;
        private WtemConfig.KeyReuse keyReuse = WtemConfig.KeyReuse.DEFAULT;
        private WtemConfig.KeyNaming keyNaming = WtemConfig.KeyNaming.DEFAULT;
        // Shared across copies so a rolled-back transaction does not replay the same draws and
        // reissue keys that a later entry already took.
        private Random random = new Random();

        private State copy() {
            State copy = new State();
            copy.keyCounts.putAll(this.keyCounts);
            copy.languageEntries.putAll(this.languageEntries);
            copy.textToKey.putAll(this.textToKey);
            copy.typeCounts.putAll(this.typeCounts);
            copy.pathStack.addAll(this.pathStack);
            copy.baseDepth = this.baseDepth;
            copy.builtinEntryCount = this.builtinEntryCount;
            copy.keepDuplicates = this.keepDuplicates;
            copy.keyReuse = this.keyReuse;
            copy.keyNaming = this.keyNaming;
            copy.random = this.random;
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
        private final int previousBaseDepth;
        private boolean closed;

        private Scope(List<String> previousPath, int previousBaseDepth) {
            this.previousPath = previousPath;
            this.previousBaseDepth = previousBaseDepth;
        }

        @Override
        public void close() {
            if (this.closed) return;
            this.closed = true;
            State state = state();
            state.pathStack.clear();
            state.pathStack.addAll(this.previousPath);
            state.baseDepth = this.previousBaseDepth;
        }
    }
}
