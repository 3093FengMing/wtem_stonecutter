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
import java.util.HashSet;
import java.util.Set;
import me.fengming.wtem.common.config.WtemConfig;
import me.fengming.wtem.common.core.extraction.ai.AiKeyNamer;
import me.fengming.wtem.common.core.extraction.manifest.ExtractionOrigin;
import me.fengming.wtem.common.core.extraction.manifest.ExtractionRecord;
import me.fengming.wtem.common.core.extraction.service.ExtractionDiagnostics;

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
     * <p>The first use keeps the base key, while subsequent uses receive {@code .1}, {@code .2},
     * and so on. Identical text reuses the key it was first given,
     * unless the configured reuse policy or {@code keepDuplicates} opts out.
     */
    public static String addEntry(String value) {
        return addEntry(value, true);
    }

    /**
     * Adds text to the catalog without claiming that its source value was replaced.
     *
     * <p>Some Minecraft fields, notably {@code writable_book_content.pages[*].raw}, are plain
     * strings rather than text components. They are still useful translation input, but putting a
     * JSON component into those fields would display the JSON literally. Such entries therefore
     * stay in the catalog and manifest while the world data remains untouched.
     */
    public static String addCatalogEntry(String value) {
        return addEntry(value, false);
    }

    /**
     * Adds a catalog-only entry whose key is fixed by data-pack runtime behavior.
     *
     * <p>A function macro used as a component translation key is expanded by Minecraft after the
     * function is called. If a caller supplies {@code Shop}, the generated language file must
     * therefore contain {@code "Shop": "Shop"}; allocating a normal structured key would create an
     * entry the command can never address. Existing equal entries are retained and recorded as
     * reuses. A conflicting existing value is never overwritten.
     *
     * @return {@code true} when the requested key maps to the requested value after this call
     */
    public static boolean addCatalogEntry(String key, String value) {
        if (key == null || key.isBlank() || value == null) return false;
        State state = state();
        String existing = state.languageEntries.get(key);
        if (existing != null) {
            if (!existing.equals(value)) return false;
            state.records.add(new ExtractionRecord(key, value, state.origin, true, false));
            return true;
        }

        state.languageEntries.put(key, value);
        state.keyCounts.putIfAbsent(key, 1);
        if (!state.keepDuplicates && state.keyReuse.allows(currentPath())) {
            state.textToKey.putIfAbsent(value, key);
        }
        state.records.add(new ExtractionRecord(key, value, state.origin, false, false));
        return true;
    }

    /**
     * Removes a speculative entry allocated after {@code recordIndex} when macro restoration made
     * its generated key unreachable. Keys with any earlier occurrence are retained because another
     * already-written command may still reference them.
     */
    public static boolean discardEntryAddedSince(String key, int recordIndex) {
        if (key == null || key.isBlank() || recordIndex < 0) return false;
        State state = state();
        int boundary = Math.min(recordIndex, state.records.size());
        for (int i = 0; i < boundary; i++) {
            if (key.equals(state.records.get(i).key())) return false;
        }
        if (!state.languageEntries.containsKey(key)) return false;

        state.languageEntries.remove(key);
        state.textToKey.entrySet().removeIf(entry -> key.equals(entry.getValue()));
        state.records.subList(boundary, state.records.size())
                .removeIf(record -> key.equals(record.key()));
        return true;
    }

    private static String addEntry(String value, boolean replaced) {
        State state = state();

        if (state.builtinValues.contains(value)) {
            // Can guarantee that it is definitely from the built-in entries
            return state.textToKey.get(value);
        }

        String path = currentPath();
        boolean reuse = !state.keepDuplicates && state.keyReuse.allows(path);
        if (reuse) {
            String existing = state.textToKey.get(value);
            if (existing != null) {
                // The text is already named, but this is a second place it appears, and that is
                // exactly what a translator needs to know before rewording it. Record the sighting
                // and mark it as a reuse so the row is not mistaken for a second entry.
                state.records.add(
                        new ExtractionRecord(existing, value, state.origin, true, replaced));
                return existing;
            }
        }

        String key;
        switch (state.keyNaming.scheme()) {
            case RANDOM -> key = randomKey(state);
            case AI -> {
                String suggested =
                    state.aiKeyNamer == null ? null : state.aiKeyNamer.suggest(path, value);
                key = allocateKey(state, suggested == null ? path : suggested);
            }
            case null, default -> key = allocateKey(state, state.keyNaming.baseKey(path));
        }
        state.languageEntries.put(key, value);
        if (reuse) state.textToKey.put(value, key);
        state.records.add(new ExtractionRecord(key, value, state.origin, false, replaced));
        return key;
    }

    /** Describes where the text reached by the current path came from. */
    public static ExtractionOrigin getOrigin() {
        return state().origin;
    }

    /**
     * Replaces the origin for the duration of the scope.
     *
     * <p>The origin is separate from the key path because the two answer different questions: the
     * path names the entry, while the origin says which chest in which chunk it was found in. A key
     * is deliberately stable no matter where the data was found, so it cannot carry the location.
     */
    public static OriginScope pushOrigin(ExtractionOrigin origin) {
        State state = state();
        ExtractionOrigin previous = state.origin;
        state.origin = origin == null ? ExtractionOrigin.UNKNOWN : origin;
        return new OriginScope(previous);
    }

    /** Starts a fresh origin for an extraction stage. */
    public static OriginScope pushSource(String source) {
        return pushOrigin(ExtractionOrigin.of(source));
    }

    /** Narrows the current origin to a place inside it, such as a chunk or a datapack resource. */
    public static OriginScope pushLocation(String segment) {
        return pushOrigin(state().origin.addLocation(segment));
    }

    /** Narrows the current origin to the block, entity, or item the text is attached to. */
    public static OriginScope pushSubject(String segment) {
        return pushOrigin(state().origin.addSubject(segment));
    }

    /** Every entry the run produced, in allocation order, including repeat sightings. */
    public static List<ExtractionRecord> records() {
        return List.copyOf(state().records);
    }

    /** Number of source occurrences retained by the current extraction transaction. */
    public static int recordCount() {
        return state().records.size();
    }

    /**
     * Reports whether every occurrence added after {@code recordIndex} is intentionally
     * catalog-only.
     *
     * <p>This is stricter than merely checking that a record was added: an ordinary handler that
     * speculatively allocated a replacement and later returned unchanged must still roll back.
     */
    public static boolean hasOnlyCatalogEntriesSince(int recordIndex) {
        List<ExtractionRecord> records = state().records;
        if (recordIndex < 0 || recordIndex >= records.size()) return false;
        for (int i = recordIndex; i < records.size(); i++) {
            if (records.get(i).replaced()) return false;
        }
        return true;
    }

    public static void setKeyNaming(WtemConfig.KeyNaming keyNaming) {
        state().keyNaming = keyNaming;
    }

    /** Installs the run-scoped semantic key provider used by the {@code ai} naming scheme. */
    public static void setAiKeyNamer(AiKeyNamer aiKeyNamer) {
        state().aiKeyNamer = aiKeyNamer;
    }

    /** Installs the diagnostics sink for warnings emitted by low-level visitors. */
    public static void setDiagnostics(ExtractionDiagnostics diagnostics) {
        state().diagnostics = diagnostics;
    }

    /**
     * Records a non-fatal warning at the current extraction location.
     *
     * <p>Visitors run below resource handlers, so carrying the sink in this thread-local context
     * keeps their warnings on the same diagnostics stream. The sink is deliberately not part of
     * transaction rollback: a warning remains useful even when a handler later abandons a
     * speculative NBT rewrite.
     */
    public static void recordWarning(String scope, String message) {
        ExtractionDiagnostics diagnostics = state().diagnostics;
        if (diagnostics == null) return;
        diagnostics.recordWarning(scope, warningLocation(), message);
    }

    private static String warningLocation() {
        State state = state();
        StringBuilder location = new StringBuilder();
        appendWarningLocation(location, state.origin.source());
        appendWarningLocation(location, state.origin.location());
        appendWarningLocation(location, state.origin.subject());
        String key = currentPath();
        if (!key.isBlank()) appendWarningLocation(location, "key " + key);
        return location.isEmpty() ? "unknown" : location.toString();
    }

    private static void appendWarningLocation(StringBuilder location, String value) {
        if (value == null || value.isBlank()) return;
        if (!location.isEmpty()) location.append(" > ");
        location.append(value);
    }

    /** Captures the immutable configuration snapshot used by the current extraction thread. */
    public static void setConfig(WtemConfig config) {
        state().config = config == null ? WtemConfig.active() : config;
    }

    public static WtemConfig config() {
        WtemConfig configured = state().config;
        // Unit tests and callers that use the context directly do not establish an extraction
        // snapshot. In that case the context must observe the active (possibly temporarily
        // overridden) configuration. WorldExtractor calls setConfig before starting a run, so a
        // real extraction still keeps the immutable snapshot for its entire lifetime.
        return configured == null ? WtemConfig.active() : configured;
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
        state.builtinValues.clear();
        entries.forEach(
                (key, value) -> {
                    state.languageEntries.put(key, value);
                    state.keyCounts.putIfAbsent(key, 1);
                    state.textToKey.putIfAbsent(value, key);
                    state.builtinValues.add(value);
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
        private final Set<String> builtinValues = new HashSet<>();
        private final Deque<String> pathStack = new ArrayDeque<>();
        private final List<ExtractionRecord> records = new ArrayList<>();
        private ExtractionOrigin origin = ExtractionOrigin.UNKNOWN;
        // How much of the path a restart keeps. Zero means a restart discards the whole path.
        private int baseDepth;
        // How many entries were seeded rather than extracted, so a report can tell the two apart.
        private int builtinEntryCount;
        private boolean keepDuplicates;
        private WtemConfig.KeyReuse keyReuse = WtemConfig.KeyReuse.DEFAULT;
        private WtemConfig.KeyNaming keyNaming = WtemConfig.KeyNaming.DEFAULT;
        // Shared across transactional copies so caching and the failure circuit breaker apply to
        // the complete extraction run, including entries whose enclosing write is rolled back.
        private AiKeyNamer aiKeyNamer;
        // Shared across transactional copies so warnings survive a handler transaction that is
        // later rolled back.
        private ExtractionDiagnostics diagnostics;
        // Null means that no extraction snapshot has been pinned yet. Keeping this unset is
        // important for direct handler/visitor tests, which install a temporary active config
        // after clearing the context.
        private WtemConfig config;
        // Shared across copies so a rolled-back transaction does not replay the same draws and
        // reissue keys that a later entry already took.
        private Random random = new Random();

        private State copy() {
            State copy = new State();
            copy.keyCounts.putAll(this.keyCounts);
            copy.languageEntries.putAll(this.languageEntries);
            copy.textToKey.putAll(this.textToKey);
            copy.typeCounts.putAll(this.typeCounts);
            copy.builtinValues.addAll(this.builtinValues);
            copy.pathStack.addAll(this.pathStack);
            copy.records.addAll(this.records);
            copy.origin = this.origin;
            copy.baseDepth = this.baseDepth;
            copy.builtinEntryCount = this.builtinEntryCount;
            copy.keepDuplicates = this.keepDuplicates;
            copy.keyReuse = this.keyReuse;
            copy.keyNaming = this.keyNaming;
            copy.aiKeyNamer = this.aiKeyNamer;
            copy.diagnostics = this.diagnostics;
            copy.config = this.config;
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

    public static final class OriginScope implements AutoCloseable {
        private final ExtractionOrigin previousOrigin;
        private boolean closed;

        private OriginScope(ExtractionOrigin previousOrigin) {
            this.previousOrigin = previousOrigin;
        }

        @Override
        public void close() {
            if (this.closed) return;
            this.closed = true;
            state().origin = this.previousOrigin;
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
