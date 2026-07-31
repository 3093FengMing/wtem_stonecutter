package me.fengming.wtem.common.config;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import me.fengming.wtem.common.Wtem;
import me.fengming.wtem.common.util.ResourceIo;

/**
 * User-facing extraction settings, read from a JSON file next to the other mod configuration.
 *
 * <p>Every setting is optional. An absent setting keeps the built-in behaviour, so a partial file
 * stays valid and a file written by an older version of the mod keeps working. A malformed file is
 * reported and ignored rather than aborting extraction, because losing the ability to extract is a
 * worse outcome than losing a customisation.
 *
 * @param stages which extraction stages run
 * @param resources which data-pack resource kinds are processed, keyed by registry directory name
 * @param keyReuse when an existing translation key is reused instead of a fresh one being allocated
 * @param languageFile name of the catalog written to the world directory
 * @author FengMing
 */
public record WtemConfig(
        Map<Stage, Boolean> stages,
        Map<String, Boolean> resources,
        KeyReuse keyReuse,
        String languageFile) {

    public static final String FILE_NAME = "wtem.json";
    public static final String DEFAULT_LANGUAGE_FILE = "en_us.json";
    public static final WtemConfig DEFAULT =
            new WtemConfig(Map.of(), Map.of(), KeyReuse.DEFAULT, DEFAULT_LANGUAGE_FILE);

    private static volatile WtemConfig active = DEFAULT;

    public WtemConfig {
        stages = Map.copyOf(stages);
        resources = Map.copyOf(resources);
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
     * <p>The generated file documents the available settings by listing them, which is the only way a
     * user can discover them without reading the source.
     */
    public static WtemConfig loadOrCreate(Path directory) {
        Path file = directory.resolve(FILE_NAME);
        if (!Files.exists(file)) {
            try {
                ResourceIo.writeJson(file, DEFAULT.toJson());
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
                languageFile(json));
    }

    public JsonObject toJson() {
        JsonObject stageJson = new JsonObject();
        for (Stage stage : Stage.values()) {
            stageJson.addProperty(stage.id(), isEnabled(stage));
        }

        JsonObject resourceJson = new JsonObject();
        this.resources.forEach(resourceJson::addProperty);

        JsonObject json = new JsonObject();
        json.add("stages", stageJson);
        json.add("resources", resourceJson);
        json.add("key_reuse", this.keyReuse.toJson());
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
        public static final KeyReuse DEFAULT = new KeyReuse(true, Map.of());

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
}
