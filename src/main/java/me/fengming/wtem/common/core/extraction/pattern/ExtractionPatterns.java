package me.fengming.wtem.common.core.extraction.pattern;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import me.fengming.wtem.common.Wtem;
import me.fengming.wtem.common.util.ResourceIo;
import net.minecraft.resources.Identifier;

/**
 * User supplied extraction selectors.
 *
 * <p>The rules are intentionally schema-neutral.  They select a JSON/NBT/command value and then
 * hand it to the existing Minecraft component/NBT translators; they do not parse command syntax
 * themselves.  The optional external files follow the same object shape as the inline
 * {@code patterns} object, but cannot themselves include another {@code files} list.
 *
 * @author FengMing
 */
public final class ExtractionPatterns {
    public static final ExtractionPatterns DEFAULT =
            new ExtractionPatterns(
                    List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());

    private final List<String> files;
    private final List<JsonRule> inlineJson;
    private final List<SavedDataRule> inlineSavedData;
    private final List<CommandRule> inlineCommands;
    private final List<JsonRule> json;
    private final List<SavedDataRule> savedData;
    private final List<CommandRule> commands;

    private ExtractionPatterns(
            List<String> files,
            List<JsonRule> inlineJson,
            List<SavedDataRule> inlineSavedData,
            List<CommandRule> inlineCommands,
            List<JsonRule> json,
            List<SavedDataRule> savedData,
            List<CommandRule> commands) {
        this.files = List.copyOf(files);
        this.inlineJson = List.copyOf(inlineJson);
        this.inlineSavedData = List.copyOf(inlineSavedData);
        this.inlineCommands = List.copyOf(inlineCommands);
        this.json = List.copyOf(json);
        this.savedData = List.copyOf(savedData);
        this.commands = List.copyOf(commands);
    }

    public static ExtractionPatterns fromJson(JsonElement value) {
        return fromJson(value, null);
    }

    /**
     * Reads inline rules and, when a configuration directory is supplied, the referenced external
     * rule files.  A broken individual rule/file is ignored so a typo cannot disable normal
     * extraction.
     */
    public static ExtractionPatterns fromJson(JsonElement value, Path configurationDirectory) {
        if (value == null || !value.isJsonObject()) return DEFAULT;

        JsonObject object = value.getAsJsonObject();
        Builder inline = new Builder();
        List<String> files = readFiles(object);
        inline.files.addAll(files);
        readRules(object, inline, "inline patterns");

        Builder effective = inline.copy();

        if (configurationDirectory != null) {
            Path base = configurationDirectory.toAbsolutePath().normalize();
            for (String file : files) {
                Path resolved = resolvePatternFile(base, file);
                if (resolved == null) continue;
                try {
                    JsonElement external =
                            ResourceIo.readJson(() -> Files.newInputStream(resolved), "");
                    if (!external.isJsonObject()) {
                        warn(file, "pattern file root must be an object");
                        continue;
                    }
                    readRules(external.getAsJsonObject(), effective, "pattern file " + file);
                } catch (RuntimeException exception) {
                    warn(file, "failed to read pattern file: " + exception.getMessage());
                }
            }
        }

        if (inline.files.isEmpty()
                && effective.json.isEmpty()
                && effective.savedData.isEmpty()
                && effective.commands.isEmpty()) {
            return DEFAULT;
        }
        return new ExtractionPatterns(
                inline.files,
                inline.json,
                inline.savedData,
                inline.commands,
                effective.json,
                effective.savedData,
                effective.commands);
    }

    private static List<String> readFiles(JsonObject object) {
        JsonElement value = object.get("files");
        if (value == null) return List.of();
        if (!value.isJsonArray()) {
            warn("patterns.files", "expected an array of relative JSON file paths");
            return List.of();
        }
        LinkedHashSet<String> files = new LinkedHashSet<>();
        for (JsonElement entry : value.getAsJsonArray()) {
            if (entry == null || !entry.isJsonPrimitive() || !entry.getAsJsonPrimitive().isString()) {
                warn("patterns.files", "ignoring a non-string file path");
                continue;
            }
            String file = entry.getAsString().trim().replace('\\', '/');
            if (file.isBlank()) {
                warn("patterns.files", "ignoring a blank file path");
                continue;
            }
            if (resolvePatternFile(Path.of("."), file) == null) continue;
            files.add(file);
        }
        return List.copyOf(files);
    }

    private static void readRules(JsonObject object, Builder builder, String source) {
        readJsonRules(object.get("json"), builder.json, source + ".json");
        readSavedDataRules(object.get("saved_data"), builder.savedData, source + ".saved_data");
        readCommandRules(object.get("commands"), builder.commands, source + ".commands");
        // The spelling used by the public configuration is intentionally accepted as an alias;
        // it makes the type visible to users without making the file format command-centric.
        readCommandRules(object.get("command"), builder.commands, source + ".command");
    }

    private static void readJsonRules(JsonElement value, List<JsonRule> output, String source) {
        for (JsonObject object : objects(value, source)) {
            String resource = string(object, "resource", "");
            String path = string(object, "path", "");
            if (resource.isBlank() || path.isBlank()) {
                warn(source, "a JSON rule requires resource and path");
                continue;
            }
            try {
                output.add(
                        new JsonRule(
                                resource,
                                string(object, "namespace", "*"),
                                string(object, "resource_path", "*"),
                                DataPath.parse(path),
                                ValueKind.parse(string(object, "kind", "component"))));
            } catch (IllegalArgumentException exception) {
                warn(source, exception.getMessage());
            }
        }
    }

    private static void readSavedDataRules(
            JsonElement value, List<SavedDataRule> output, String source) {
        for (JsonObject object : objects(value, source)) {
            String path = string(object, "path", "");
            if (path.isBlank()) {
                warn(source, "a SavedData rule requires path");
                continue;
            }
            try {
                output.add(
                        new SavedDataRule(
                                string(object, "file", "*"),
                                DataPath.parse(path),
                                ValueKind.parse(string(object, "kind", "component"))));
            } catch (IllegalArgumentException exception) {
                warn(source, exception.getMessage());
            }
        }
    }

    private static void readCommandRules(
            JsonElement value, List<CommandRule> output, String source) {
        for (JsonObject object : objects(value, source)) {
            String command = string(object, "command", "");
            String argument = string(object, "argument", "");
            int index = integer(object, "argument_index", -1);
            if (command.isBlank() || (argument.isBlank() && index < 1)) {
                warn(source, "a command rule requires command and argument or argument_index");
                continue;
            }
            try {
                List<String> literals = strings(object, "literals");
                String dataPath = string(object, "data_path", "");
                output.add(
                        new CommandRule(
                                command,
                                literals,
                                argument,
                                index,
                                dataPath.isBlank() ? null : DataPath.parse(dataPath),
                                ValueKind.parse(string(object, "kind", "component"))));
            } catch (IllegalArgumentException exception) {
                warn(source, exception.getMessage());
            }
        }
    }

    private static List<JsonObject> objects(JsonElement value, String source) {
        if (value == null) return List.of();
        if (!value.isJsonArray()) {
            warn(source, "expected an array");
            return List.of();
        }
        List<JsonObject> result = new ArrayList<>();
        for (JsonElement entry : value.getAsJsonArray()) {
            if (entry != null && entry.isJsonObject()) result.add(entry.getAsJsonObject());
            else warn(source, "ignoring a non-object rule");
        }
        return result;
    }

    private static String string(JsonObject object, String name, String fallback) {
        JsonElement value = object.get(name);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()
                ? value.getAsString().trim()
                : fallback;
    }

    private static int integer(JsonObject object, String name, int fallback) {
        JsonElement value = object.get(name);
        if (value == null || !value.isJsonPrimitive()) return fallback;
        try {
            return value.getAsInt();
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private static List<String> strings(JsonObject object, String name) {
        JsonElement value = object.get(name);
        if (value == null) return List.of();
        if (!value.isJsonArray()) throw new IllegalArgumentException(name + " must be an array");
        List<String> result = new ArrayList<>();
        for (JsonElement entry : value.getAsJsonArray()) {
            if (entry == null || !entry.isJsonPrimitive() || !entry.getAsJsonPrimitive().isString()) {
                throw new IllegalArgumentException(name + " must contain only strings");
            }
            String text = entry.getAsString().trim();
            if (!text.isBlank()) result.add(text);
        }
        return List.copyOf(result);
    }

    private static Path resolvePatternFile(Path base, String raw) {
        if (raw == null || raw.isBlank()) return null;
        Path candidate;
        try {
            candidate = Path.of(raw.replace('\\', '/'));
        } catch (RuntimeException exception) {
            warn(raw, "invalid pattern file path");
            return null;
        }
        if (candidate.isAbsolute()) {
            warn(raw, "pattern file path must be relative to the WTEM config directory");
            return null;
        }
        for (Path part : candidate) {
            if ("..".equals(part.toString())) {
                warn(raw, "pattern file path must stay below the WTEM config directory");
                return null;
            }
        }
        Path normalizedBase = base.toAbsolutePath().normalize();
        Path resolved = normalizedBase.resolve(candidate).normalize();
        if (!resolved.startsWith(normalizedBase)) {
            warn(raw, "pattern file path escapes the WTEM config directory");
            return null;
        }
        return resolved;
    }

    private static void warn(String source, String message) {
        Wtem.LOGGER.warn("Ignoring {}: {}", source, message == null ? "invalid pattern" : message);
    }

    public List<String> files() {
        return this.files;
    }

    /** Resolves the already validated external sources for config hot-reload polling. */
    public List<Path> resolvedFiles(Path configurationDirectory) {
        if (configurationDirectory == null || this.files.isEmpty()) return List.of();
        Path base = configurationDirectory.toAbsolutePath().normalize();
        List<Path> resolved = new ArrayList<>();
        for (String file : this.files) {
            Path path = resolvePatternFile(base, file);
            if (path != null) resolved.add(path);
        }
        return List.copyOf(resolved);
    }

    public List<JsonRule> json() {
        return this.json;
    }

    public List<SavedDataRule> savedData() {
        return this.savedData;
    }

    public List<CommandRule> commands() {
        return this.commands;
    }

    public JsonObject toJson() {
        JsonObject result = new JsonObject();
        JsonArray files = new JsonArray();
        this.files.forEach(files::add);
        result.add("files", files);
        result.add("json", jsonArray(this.inlineJson, ExtractionPatterns::jsonObject));
        result.add(
                "saved_data", jsonArray(this.inlineSavedData, ExtractionPatterns::savedDataObject));
        result.add("commands", jsonArray(this.inlineCommands, ExtractionPatterns::commandObject));
        return result;
    }

    private static <T> JsonArray jsonArray(
            Collection<T> values, java.util.function.Function<T, JsonObject> converter) {
        JsonArray result = new JsonArray();
        for (T value : values) result.add(converter.apply(value));
        return result;
    }

    private static JsonObject jsonObject(JsonRule rule) {
        JsonObject object = new JsonObject();
        object.addProperty("resource", rule.resource());
        object.addProperty("namespace", rule.namespace());
        object.addProperty("resource_path", rule.resourcePath());
        object.addProperty("path", rule.path().source());
        object.addProperty("kind", rule.kind().id());
        return object;
    }

    private static JsonObject savedDataObject(SavedDataRule rule) {
        JsonObject object = new JsonObject();
        object.addProperty("file", rule.file());
        object.addProperty("path", rule.path().source());
        object.addProperty("kind", rule.kind().id());
        return object;
    }

    private static JsonObject commandObject(CommandRule rule) {
        JsonObject object = new JsonObject();
        object.addProperty("command", rule.command());
        if (!rule.literals().isEmpty()) {
            JsonArray literals = new JsonArray();
            rule.literals().forEach(literals::add);
            object.add("literals", literals);
        }
        if (!rule.argument().isBlank()) object.addProperty("argument", rule.argument());
        if (rule.argumentIndex() > 0) object.addProperty("argument_index", rule.argumentIndex());
        if (rule.dataPath() != null) object.addProperty("data_path", rule.dataPath().source());
        object.addProperty("kind", rule.kind().id());
        return object;
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof ExtractionPatterns patterns)) return false;
        return this.files.equals(patterns.files)
                && this.inlineJson.equals(patterns.inlineJson)
                && this.inlineSavedData.equals(patterns.inlineSavedData)
                && this.inlineCommands.equals(patterns.inlineCommands)
                && this.json.equals(patterns.json)
                && this.savedData.equals(patterns.savedData)
                && this.commands.equals(patterns.commands);
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                this.files,
                this.inlineJson,
                this.inlineSavedData,
                this.inlineCommands,
                this.json,
                this.savedData,
                this.commands);
    }

    public enum ValueKind {
        COMPONENT("component"),
        PLAIN_STRING("plain_string");

        private final String id;

        ValueKind(String id) {
            this.id = id;
        }

        public String id() {
            return this.id;
        }

        public static ValueKind parse(String value) {
            for (ValueKind kind : values()) {
                if (kind.id.equalsIgnoreCase(value == null ? "" : value.trim())) return kind;
            }
            throw new IllegalArgumentException("unknown pattern kind: " + value);
        }
    }

    public record JsonRule(
            String resource,
            String namespace,
            String resourcePath,
            DataPath path,
            ValueKind kind) {
        public JsonRule {
            resource = require(resource, "resource");
            namespace = defaultValue(namespace, "*");
            resourcePath = defaultValue(resourcePath, "*");
            kind = kind == null ? ValueKind.COMPONENT : kind;
        }

        public boolean matches(String directory, Identifier resourceId) {
            if (!globMatches(this.resource, directory)) return false;
            if (!globMatches(this.namespace, resourceId.getNamespace())) return false;
            return globMatches(this.resourcePath, resourceId.getPath());
        }
    }

    public record SavedDataRule(String file, DataPath path, ValueKind kind) {
        public SavedDataRule {
            file = defaultValue(file, "*");
            kind = kind == null ? ValueKind.COMPONENT : kind;
        }

        public boolean matches(String fileName, List<DataPath.Location> location) {
            return globMatches(this.file, fileName) && this.path.matches(location);
        }
    }

    public record CommandRule(
            String command,
            List<String> literals,
            String argument,
            int argumentIndex,
            DataPath dataPath,
            ValueKind kind) {
        public CommandRule {
            command = require(command, "command");
            literals = literals == null ? List.of() : literals.stream().filter(Objects::nonNull).toList();
            argument = argument == null ? "" : argument.trim();
            if (argument.isBlank() && argumentIndex < 1) {
                throw new IllegalArgumentException("command rule requires argument or argument_index");
            }
            kind = kind == null ? ValueKind.COMPONENT : kind;
        }
    }

    private static String require(String value, String name) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return normalized;
    }

    private static String defaultValue(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    /** A deliberately tiny wildcard matcher for user-facing resource/file selectors. */
    public static boolean globMatches(String pattern, String value) {
        if (pattern == null || value == null) return false;
        int patternIndex = 0;
        int valueIndex = 0;
        int wildcard = -1;
        int wildcardValue = -1;
        while (valueIndex < value.length()) {
            if (patternIndex < pattern.length()
                && (pattern.charAt(patternIndex) == '?' || pattern.charAt(patternIndex) == value.charAt(valueIndex))) {
                patternIndex++;
                valueIndex++;
            } else if (patternIndex < pattern.length() && pattern.charAt(patternIndex) == '*') {
                wildcard = patternIndex++;
                wildcardValue = valueIndex;
            } else if (wildcard >= 0) {
                patternIndex = wildcard + 1;
                valueIndex = ++wildcardValue;
            } else {
                return false;
            }
        }
        while (patternIndex < pattern.length() && pattern.charAt(patternIndex) == '*') patternIndex++;
        return patternIndex == pattern.length();
    }

    private static final class Builder {
        private final List<String> files = new ArrayList<>();
        private final List<JsonRule> json = new ArrayList<>();
        private final List<SavedDataRule> savedData = new ArrayList<>();
        private final List<CommandRule> commands = new ArrayList<>();

        private Builder copy() {
            Builder copy = new Builder();
            copy.files.addAll(this.files);
            copy.json.addAll(this.json);
            copy.savedData.addAll(this.savedData);
            copy.commands.addAll(this.commands);
            return copy;
        }
    }
}
