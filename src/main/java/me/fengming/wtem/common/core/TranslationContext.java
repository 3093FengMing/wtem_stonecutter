package me.fengming.wtem.common.core;

import com.google.gson.GsonBuilder;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
        state().typeCounts.merge(type, 1, Integer::sum);
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
     * subsequent uses receive {@code .1}, {@code .2}, and so on. Identical text is reused unless
     * duplicate entries have explicitly been enabled.
     */
    public static String addEntry(String value) {
        State state = state();
        if (!state.keepDuplicates) {
            String existing = state.textToKey.get(value);
            if (existing != null) return existing;
        }

        String key = allocateKey(currentPath());
        state.languageEntries.put(key, value);
        if (!state.keepDuplicates) state.textToKey.put(value, key);
        return key;
    }

    /** Compatibility entry point for callers that already have a base key. */
    public static String addKey(String key, String value) {
        State state = state();
        if (!state.keepDuplicates) {
            String existing = state.textToKey.get(value);
            if (existing != null) return existing;
        }

        String actualKey = uniqueKey(key);
        state.languageEntries.put(actualKey, value);
        if (!state.keepDuplicates) state.textToKey.put(value, actualKey);
        return actualKey;
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
