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
    private static final Map<String, Integer> KEY_COUNTS = new LinkedHashMap<>();
    private static final Map<String, String> LANGUAGE_ENTRIES = new LinkedHashMap<>();
    private static final Map<String, String> TEXT_TO_KEY = new LinkedHashMap<>();
    private static final Map<String, Integer> TYPE_COUNTS = new LinkedHashMap<>();
    private static final Deque<String> PATH_STACK = new ArrayDeque<>();
    private static final GsonBuilder LANGUAGE_GSON =
            new GsonBuilder().setPrettyPrinting().disableHtmlEscaping();

    private static boolean keepDuplicates;

    private TranslationContext() {}

    public static void clear() {
        KEY_COUNTS.clear();
        LANGUAGE_ENTRIES.clear();
        TEXT_TO_KEY.clear();
        TYPE_COUNTS.clear();
        PATH_STACK.clear();
    }

    public static int getTypeCounts(String type) {
        return TYPE_COUNTS.getOrDefault(type, 1);
    }

    public static void increaseTypeCounts(String type) {
        TYPE_COUNTS.merge(type, 1, Integer::sum);
    }

    public static int nextTypeCount(String type) {
        int count = getTypeCounts(type);
        increaseTypeCounts(type);
        return count;
    }

    public static void setKey(String key) {
        PATH_STACK.clear();
        append(key);
    }

    public static String getKey() {
        return allocateKey(currentPath());
    }

    /**
     * Adds a translation entry using the current path as its base key.
     *
     * <p>The allocation order follows the upstream extractor: the first use keeps the base key, while
     * subsequent uses receive {@code .1}, {@code .2}, and so on. Identical text is reused unless
     * duplicate entries have explicitly been enabled.
     */
    public static String addEntry(String value) {
        if (!keepDuplicates) {
            String existing = TEXT_TO_KEY.get(value);
            if (existing != null) return existing;
        }

        String key = allocateKey(currentPath());
        LANGUAGE_ENTRIES.put(key, value);
        if (!keepDuplicates) TEXT_TO_KEY.put(value, key);
        return key;
    }

    /** Compatibility entry point for callers that already have a base key. */
    public static String addKey(String key, String value) {
        if (!keepDuplicates) {
            String existing = TEXT_TO_KEY.get(value);
            if (existing != null) return existing;
        }

        String actualKey = uniqueKey(key);
        LANGUAGE_ENTRIES.put(actualKey, value);
        if (!keepDuplicates) TEXT_TO_KEY.put(value, actualKey);
        return actualKey;
    }

    public static String exportLanguage() {
        return LANGUAGE_GSON.create().toJson(LANGUAGE_ENTRIES);
    }

    public static Map<String, String> snapshot() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(LANGUAGE_ENTRIES));
    }

    public static void setKeepDuplicates(boolean keepDuplicates) {
        TranslationContext.keepDuplicates = keepDuplicates;
    }

    public static boolean isKeepingDuplicates() {
        return keepDuplicates;
    }

    public static void revertAndAppend(String id) {
        revert();
        append(id);
    }

    public static void revert() {
        if (PATH_STACK.size() > 1) PATH_STACK.removeLast();
    }

    public static void append(String path) {
        if (path == null || path.isBlank()) return;
        PATH_STACK.addLast(path);
    }

    public static Scope push(String path) {
        List<String> previous = new ArrayList<>(PATH_STACK);
        append(path);
        return new Scope(previous);
    }

    public static Scope pushKey(String key) {
        List<String> previous = new ArrayList<>(PATH_STACK);
        setKey(key);
        return new Scope(previous);
    }

    private static String currentPath() {
        if (PATH_STACK.isEmpty()) return "no_key";
        return String.join(".", PATH_STACK);
    }

    private static String allocateKey(String baseKey) {
        int count = KEY_COUNTS.getOrDefault(baseKey, 0);
        KEY_COUNTS.put(baseKey, count + 1);
        return count == 0 ? baseKey : baseKey + "." + count;
    }

    private static String uniqueKey(String baseKey) {
        if (!LANGUAGE_ENTRIES.containsKey(baseKey)) {
            KEY_COUNTS.putIfAbsent(baseKey, 1);
            return baseKey;
        }
        return allocateKey(baseKey);
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
            PATH_STACK.clear();
            PATH_STACK.addAll(this.previousPath);
        }
    }
}
