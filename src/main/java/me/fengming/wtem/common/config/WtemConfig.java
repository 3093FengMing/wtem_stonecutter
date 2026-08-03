package me.fengming.wtem.common.config;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.function.Function;
import java.util.stream.Stream;
import me.fengming.wtem.common.Wtem;
import me.fengming.wtem.common.util.ResourceIo;

/**
 * User-facing extraction settings, read from a JSON file next to the other mod configuration.
 *
 * <p>Every setting is optional. An absent setting keeps the built-in behavior, so a partial file
 * stays valid and a file written by an older version of the mod keeps working. A malformed file is
 * reported and ignored rather than aborting extraction, because losing the ability to extract is a
 * worse outcome than losing a customization.
 *
 * @param stages which extraction stages run
 * @param resources which data-pack resource kinds are processed, keyed by registry directory name
 * @param keyReuse when an existing translation key is reused instead of a fresh one being allocated
 * @param keyNaming how generated translation keys are spelled
 * @param nbtMaxDepth how deep nested item, entity, and block-entity data is followed
 * @param rebuildNestedKeys whether data nested inside an item restarts its key instead of extending
 *     the key of the item that carries it
 * @param skipped text that is translatable but that a pack may want left alone
 * @param skippedPaths data-pack directories left unread, so their contents are neither extracted nor
 *     copied into the generated pack
 * @param builtinEntries entries the catalog starts with, which extracted text reuses instead of
 *     allocating a key of its own
 * @param languageFile name of the catalog written to the world directory
 * @author FengMing
 */
public record WtemConfig(
        Map<Stage, Boolean> stages,
        Map<String, Boolean> resources,
        KeyReuse keyReuse,
        KeyNaming keyNaming,
        int nbtMaxDepth,
        boolean rebuildNestedKeys,
        Skipped skipped,
        List<String> skippedPaths,
        Map<String, String> builtinEntries,
        String languageFile) {

    public static final String FILE_NAME = "wtem.json";
    public static final String DEFAULT_LANGUAGE_FILE = "en_us.json";
    public static final int DEFAULT_NBT_MAX_DEPTH = 32;

    /**
     * Datapack directories skipped by default.
     */
    public static final List<String> DEFAULT_SKIPPED_PATHS = List.of("animated_java/function");

    /**
     * Entries every catalog starts with.
     */
    public static final Map<String, String> DEFAULT_BUILTIN_ENTRIES = defaultBuiltinEntries();

    public static final WtemConfig DEFAULT =
            new WtemConfig(
                    Map.of(),
                    Map.of(),
                    KeyReuse.DEFAULT,
                    KeyNaming.DEFAULT,
                    DEFAULT_NBT_MAX_DEPTH,
                    true,
                    Skipped.DEFAULT,
                    DEFAULT_SKIPPED_PATHS,
                    DEFAULT_BUILTIN_ENTRIES,
                    DEFAULT_LANGUAGE_FILE);

    private static volatile WtemConfig active = DEFAULT;

    public WtemConfig {
        stages = Map.copyOf(stages);
        // Insertion order is kept for the string-keyed maps: it decides the order of the generated
        // file, and for the built-in entries it also decides which key wins when two share a value.
        resources = ordered(resources);
        skippedPaths = List.copyOf(skippedPaths);
        builtinEntries = ordered(builtinEntries);
        // A non-positive limit would stop traversal before the outermost tag is read, which silently
        // extracts nothing at all. Treat it the same way as a malformed value.
        if (nbtMaxDepth < 1) nbtMaxDepth = DEFAULT_NBT_MAX_DEPTH;
    }

    private static Map<String, String> defaultBuiltinEntries() {
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put(KeyNaming.GENERATED_PREFIX + "blank", "");
        entries.put(KeyNaming.GENERATED_PREFIX + "space", " ");
        for (int digit = 0; digit <= 9; digit++) {
            entries.put(KeyNaming.GENERATED_PREFIX + digit, String.valueOf(digit));
        }
        return Collections.unmodifiableMap(entries);
    }

    /** The extraction stages that can be switched off independently. */
    public enum Stage {
        REGION("region"),
        ENTITIES("entities"),
        SCOREBOARD("scoreboard"),
        BOSS_BAR("boss_bar"),
        DATAPACKS("datapacks"),
        GENERATED_STRUCTURES("generated_structures");

        private final String id;

        Stage(String id) {
            this.id = id;
        }

        public String id() {
            return this.id;
        }
    }

    public static WtemConfig active() {
        return active;
    }

    public static void initialize(WtemConfig config) {
        active = config;
    }

    /**
     * Reads the configuration from {@code directory}, writing a default file when none exists.
     *
     * <p>The generated file spells out every setting at its default value, which is the only way a
     * user can discover what may be changed without reading the source. {@code resourceDirectories}
     * supplies the resource kinds to list, because which handlers exist is not known here.
     */
    public static WtemConfig loadOrCreate(Path directory, Collection<String> resourceDirectories) {
        Path file = directory.resolve(FILE_NAME);
        if (!Files.exists(file)) {
            try {
                ResourceIo.writeJson(file, DEFAULT.toJson(resourceDirectories));
            } catch (RuntimeException exception) {
                Wtem.LOGGER.warn("Failed to write the default WTEM configuration", exception);
            }
            return DEFAULT;
        }

        try {
            return fromJson(ResourceIo.readJson(() -> Files.newInputStream(file), ""));
        } catch (RuntimeException exception) {
            Wtem.LOGGER.error(
                    "Failed to read {}, continuing with the default settings", file, exception);
            return DEFAULT;
        }
    }

    public static WtemConfig fromJson(JsonElement root) {
        if (!root.isJsonObject()) {
            throw new IllegalArgumentException("WTEM configuration root is not a JSON object");
        }

        JsonObject json = root.getAsJsonObject();
        Map<Stage, Boolean> stages = new LinkedHashMap<>();
        JsonObject stageJson = object(json, "stages");
        for (Stage stage : Stage.values()) {
            readBoolean(stageJson, stage.id()).ifPresent(enabled -> stages.put(stage, enabled));
        }

        Map<String, Boolean> resources = new LinkedHashMap<>();
        JsonObject resourceJson = object(json, "resources");
        for (String name : resourceJson.keySet()) {
            readBoolean(resourceJson, name).ifPresent(enabled -> resources.put(name, enabled));
        }

        return new WtemConfig(
                stages,
                resources,
                KeyReuse.fromJson(object(json, "key_reuse")),
                KeyNaming.fromJson(object(json, "key_naming")),
                readInt(json, "nbt_max_depth").orElse(DEFAULT_NBT_MAX_DEPTH),
                readBoolean(json, "rebuild_nested_keys").orElse(DEFAULT.rebuildNestedKeys()),
                Skipped.fromJson(object(json, "skipped")),
                skippedPaths(json),
                builtinEntries(json),
                languageFile(json));
    }

    /**
     * Writes the settings out, listing {@code resourceDirectories} whether or not they were
     * configured.
     *
     * <p>A resource kind absent from the file is enabled, so writing only the configured ones would
     * produce a file that hides most of what it controls.
     */
    public JsonObject toJson(Collection<String> resourceDirectories) {
        JsonObject stageJson = new JsonObject();
        for (Stage stage : Stage.values()) {
            stageJson.addProperty(stage.id(), isEnabled(stage));
        }

        JsonObject resourceJson = new JsonObject();
        for (String directory : resourceDirectories) {
            resourceJson.addProperty(directory, isResourceEnabled(directory));
        }
        this.resources.forEach(resourceJson::addProperty);

        JsonObject builtinJson = new JsonObject();
        this.builtinEntries.forEach(builtinJson::addProperty);

        JsonObject json = new JsonObject();
        json.add("stages", stageJson);
        json.add("resources", resourceJson);
        json.add("key_reuse", this.keyReuse.toJson());
        json.add("key_naming", this.keyNaming.toJson());
        json.addProperty("nbt_max_depth", this.nbtMaxDepth);
        json.addProperty("rebuild_nested_keys", this.rebuildNestedKeys);
        json.add("skipped", this.skipped.toJson());
        JsonArray skippedPathJson = new JsonArray();
        this.skippedPaths.forEach(skippedPathJson::add);
        json.add("skipped_paths", skippedPathJson);
        json.add("builtin_entries", builtinJson);
        json.addProperty("language_file", this.languageFile);
        return json;
    }

    public boolean isEnabled(Stage stage) {
        return this.stages.getOrDefault(stage, true);
    }

    /**
     * Reports whether a data-pack resource kind is processed.
     *
     * @param directory the registry directory the handler reads, such as {@code advancement}
     */
    public boolean isResourceEnabled(String directory) {
        return this.resources.getOrDefault(directory, true);
    }

    /**
     * Reports whether a data-pack resource lies under a skipped directory.
     *
     * <p>A skipped resource is left out of the generated pack entirely rather than copied through
     * unchanged, because the pack it came from is still loaded alongside and already supplies it.
     *
     * @param path the resource path within its namespace, including the registry directory, such as
     *     {@code animated_java/function/}
     */
    public boolean isPathSkipped(String path) {
        if (path == null || this.skippedPaths.isEmpty()) return false;

        for (String skipped : this.skippedPaths) {
            if (path.startsWith(skipped)) return true;
        }
        return false;
    }

    /**
     * Reads the skipped data-pack directories.
     *
     * <p>An absent section keeps the built-in list, but an empty one switches it off, which is the
     * only way a user can ask for a directory the mod skips by default to be extracted after all.
     */
    private static List<String> skippedPaths(JsonObject json) {
        JsonElement value = json.get("skipped_paths");
        if (value == null || !value.isJsonArray()) return DEFAULT_SKIPPED_PATHS;

        List<String> paths = new ArrayList<>();
        for (JsonElement element : value.getAsJsonArray()) {
            if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
                Wtem.LOGGER.warn("Ignoring skipped_paths entry {}: expected a string", element);
                continue;
            }

            // Both spellings of a directory mean the same thing to a reader, so both are accepted and
            // stored in the one form the matcher compares against.
            String path = element.getAsString().replace('\\', '/');
            while (path.startsWith("/")) path = path.substring(1);
            while (path.endsWith("/")) path = path.substring(0, path.length() - 1);
            if (path.isBlank()) continue;
            paths.add(path);
        }
        return List.copyOf(paths);
    }

    /**
     * Reads the seeded catalog entries.
     *
     * <p>An absent section keeps the built-in entries, but an empty one switches them off: a user who
     * wants nothing seeded has no other way to say so.
     */
    private static Map<String, String> builtinEntries(JsonObject json) {
        JsonElement value = json.get("builtin_entries");
        if (value == null || !value.isJsonObject()) return DEFAULT_BUILTIN_ENTRIES;

        JsonObject entryJson = value.getAsJsonObject();
        Map<String, String> entries = new LinkedHashMap<>();
        for (String key : entryJson.keySet()) {
            JsonElement text = entryJson.get(key);
            if (text == null || !text.isJsonPrimitive()) {
                Wtem.LOGGER.warn("Ignoring builtin entry {}: expected a string", key);
                continue;
            }
            entries.put(key, text.getAsString());
        }
        return entries;
    }

    private static <V> Map<String, V> ordered(Map<String, V> values) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }

    private static String languageFile(JsonObject json) {
        JsonElement value = json.get("language_file");
        if (value == null || !value.isJsonPrimitive()) return DEFAULT_LANGUAGE_FILE;

        String name = value.getAsString();
        // The catalog is written into the world directory, so the name must stay a plain file name:
        // a path fragment here would let the configuration write anywhere on disk.
        if (!name.matches("[A-Za-z0-9._-]+\\.json") || name.startsWith(".")) {
            Wtem.LOGGER.warn(
                    "Ignoring language_file {}: expected a plain .json file name", name);
            return DEFAULT_LANGUAGE_FILE;
        }
        return name;
    }

    private static JsonObject object(JsonObject json, String name) {
        JsonElement value = json.get(name);
        return value != null && value.isJsonObject() ? value.getAsJsonObject() : new JsonObject();
    }

    private static Optional<Boolean> readBoolean(JsonObject json, String name) {
        JsonElement value = json.get(name);
        if (value == null || !value.isJsonPrimitive()) return Optional.empty();

        JsonPrimitive primitive = value.getAsJsonPrimitive();
        if (!primitive.isBoolean()) {
            Wtem.LOGGER.warn("Ignoring {}: expected a boolean but found {}", name, primitive);
            return Optional.empty();
        }
        return Optional.of(primitive.getAsBoolean());
    }

    private static Optional<Integer> readInt(JsonObject json, String name) {
        JsonElement value = json.get(name);
        if (value == null || !value.isJsonPrimitive()) return Optional.empty();

        JsonPrimitive primitive = value.getAsJsonPrimitive();
        if (!primitive.isNumber()) {
            Wtem.LOGGER.warn("Ignoring {}: expected a number but found {}", name, primitive);
            return Optional.empty();
        }
        return Optional.of(primitive.getAsInt());
    }

    private static <E extends Enum<E>> Optional<E> readEnum(
            JsonObject json, String name, E[] values, Function<E, String> id) {
        JsonElement value = json.get(name);
        if (value == null || !value.isJsonPrimitive()) return Optional.empty();

        String text = value.getAsString();
        for (E candidate : values) {
            if (id.apply(candidate).equals(text)) return Optional.of(candidate);
        }
        Wtem.LOGGER.warn(
                "Ignoring {} {}: expected one of {}",
                name,
                text,
                Stream.of(values).map(id).toList());
        return Optional.empty();
    }

    /**
     * Text the game will happily translate but that a pack may have no reason to.
     *
     * <p>Both settings default to off, which keeps the text being extracted. Switching one on drops
     * the text from the catalog and leaves the world data holding it untouched, so nothing has to be
     * undone to change the decision later: extracting again with the setting off picks the text up.
     *
     * @param commandBlockOutput the cached result of a command block's last run, which the game
     *     overwrites on the next tick the block fires and which only an operator holding a redstone
     *     debugger ever reads
     * @param filteredText the profanity-filtered duplicate of a sign line or book page, shown in
     *     place of the original to players who have chat filtering on. Only servers with a filter
     *     configured write it, and it is the same sentence as the text beside it.
     */
    public record Skipped(boolean commandBlockOutput, boolean filteredText) {
        public static final Skipped DEFAULT = new Skipped(true, true);

        static Skipped fromJson(JsonObject json) {
            return new Skipped(
                    readBoolean(json, "command_block_output").orElse(DEFAULT.commandBlockOutput()),
                    readBoolean(json, "filtered_text").orElse(DEFAULT.filteredText()));
        }

        JsonObject toJson() {
            JsonObject json = new JsonObject();
            json.addProperty("command_block_output", this.commandBlockOutput);
            json.addProperty("filtered_text", this.filteredText);
            return json;
        }
    }

    /**
     * Decides whether text that has already been extracted reuses its existing key.
     *
     * <p>Reuse keeps the catalog small, which is what a translator wants for incidental text such as
     * repeated sign lines. It is the wrong default for text a translator may need to render
     * differently depending on where it appears, so the policy can be overridden per key prefix. The
     * longest matching prefix wins, which lets a broad rule be narrowed without restating it.
     *
     * @param byDefault the policy for keys no override matches
     * @param overrides per-key-prefix policies, such as {@code datapack.} or {@code item.}
     */
    public record KeyReuse(boolean byDefault, Map<String, Boolean> overrides) {
        public static final KeyReuse DEFAULT = new KeyReuse(true, Map.of("sign.", false));

        public KeyReuse {
            overrides = Map.copyOf(overrides);
        }

        static KeyReuse fromJson(JsonObject json) {
            boolean byDefault = readBoolean(json, "default").orElse(DEFAULT.byDefault());

            Map<String, Boolean> overrides = new LinkedHashMap<>();
            JsonObject overrideJson = object(json, "overrides");
            for (String prefix : overrideJson.keySet()) {
                readBoolean(overrideJson, prefix)
                        .ifPresent(reuse -> overrides.put(prefix, reuse));
            }
            return new KeyReuse(byDefault, overrides);
        }

        JsonObject toJson() {
            JsonObject overrideJson = new JsonObject();
            this.overrides.forEach(overrideJson::addProperty);

            JsonObject json = new JsonObject();
            json.addProperty("default", this.byDefault);
            json.add("overrides", overrideJson);
            return json;
        }

        /** Reports whether text extracted under {@code key} may reuse an existing entry. */
        public boolean allows(String key) {
            String longest = null;
            for (String prefix : this.overrides.keySet()) {
                if (!key.startsWith(prefix)) continue;
                if (longest == null || prefix.length() > longest.length()) longest = prefix;
            }
            return longest == null ? this.byDefault : this.overrides.get(longest);
        }
    }

    /**
     * Decides how a generated translation key is spelled.
     *
     * <p>The default keys describe where the text came from, which is what a translator needs in order
     * to know the context. That makes them long, and a resource pack that has to round-trip its keys
     * through a spreadsheet or a translation platform may prefer opaque short ones instead.
     *
     * @param scheme how the key text is derived
     * @param randomLength how many letters a {@link Scheme#RANDOM} key uses
     */
    public record KeyNaming(Scheme scheme, int randomLength) {
        /**
         * Prefix for keys that no longer describe their source.
         *
         * <p>A key such as {@code entity.zombie.1.name} cannot collide with a vanilla key, because the
         * generated index is part of it. A bare random or hashed key has no such protection, so it is
         * namespaced instead.
         */
        public static final String GENERATED_PREFIX = "wtem.";

        public static final int DEFAULT_RANDOM_LENGTH = 8;

        public static final KeyNaming DEFAULT =
                new KeyNaming(Scheme.STRUCTURED, DEFAULT_RANDOM_LENGTH);

        private static final char[] ALPHABET = "abcdefghijklmnopqrstuvwxyz".toCharArray();
        private static final int HASH_LENGTH = 6;

        public KeyNaming {
            if (randomLength < 1) randomLength = DEFAULT_RANDOM_LENGTH;
        }

        /** How the key text is derived. */
        public enum Scheme {
            /** Describes the extraction path, for example {@code entity.zombie.1.name}. */
            STRUCTURED("structured"),
            /** Random letters, which keeps keys short but loses all context. */
            RANDOM("random"),
            /** A stable digest of the extraction path, short but reproducible across runs. */
            HASHED("hashed");

            private final String id;

            Scheme(String id) {
                this.id = id;
            }

            public String id() {
                return this.id;
            }
        }

        static KeyNaming fromJson(JsonObject json) {
            return new KeyNaming(
                    readEnum(json, "scheme", Scheme.values(), Scheme::id).orElse(DEFAULT.scheme()),
                    readInt(json, "random_length").orElse(DEFAULT.randomLength()));
        }

        JsonObject toJson() {
            JsonObject json = new JsonObject();
            json.addProperty("scheme", this.scheme.id());
            json.addProperty("random_length", this.randomLength);
            return json;
        }

        /** Converts an extraction path into the base key, before uniqueness suffixes are added. */
        public String baseKey(String path) {
            return this.scheme == Scheme.HASHED ? GENERATED_PREFIX + hash(path) : path;
        }

        /** Produces one candidate {@link Scheme#RANDOM} key of {@code length} letters. */
        public static String randomKey(Random random, int length) {
            StringBuilder key = new StringBuilder(GENERATED_PREFIX);
            for (int i = 0; i < length; i++) {
                key.append(ALPHABET[random.nextInt(ALPHABET.length)]);
            }
            return key.toString();
        }

        /** A short, stable digest. FNV-1a is used because the value never leaves the catalog. */
        private static String hash(String value) {
            long h = 0xcbf29ce484222325L;
            for (int i = 0; i < value.length(); i++) {
                h ^= value.charAt(i);
                h *= 0x100000001b3L;
            }
            String hex = Long.toHexString(h);
            return hex.length() <= HASH_LENGTH ? hex : hex.substring(hex.length() - HASH_LENGTH);
        }
    }
}
