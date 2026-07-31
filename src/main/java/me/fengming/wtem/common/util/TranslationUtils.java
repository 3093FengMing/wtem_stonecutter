package me.fengming.wtem.common.util;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import me.fengming.wtem.common.core.extraction.TranslationContext;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.chat.Style;

/**
 * Converts literal text components into generated translatable components.
 *
 * @author FengMing
 */
public final class TranslationUtils {
    private static final String LEGACY_HOVER_EVENT = "hoverEvent";
    private static final String HOVER_EVENT = "hover_event";

    private TranslationUtils() {}

    public static String translateLiteral(String literal, boolean nonItalic) {
        if (literal == null || literal.isBlank()) return literal;

        try (var transaction = TranslationContext.beginTransaction()) {
            Component original = deserializeComponent(JsonParser.parseString(literal));
            Component translated = translateLiteral(original);
            if (translated == original) return literal;

            if (nonItalic) {
                translated = translated.copy().withStyle(Style.EMPTY.withItalic(false));
            }
            String result = serializeComponent(translated).toString();
            transaction.commit();
            return result;
        } catch (JsonParseException | IllegalStateException e) {
            return literal;
        }
    }

    /**
     * Recursively translates every literal node in a component tree.
     *
     * <p>The component is transformed through Minecraft's codec so the implementation remains
     * compatible with both the legacy untyped JSON format and the newer typed component format.
     */
    public static Component translateLiteral(Component original) {
        if (original == null) return null;

        try (var transaction = TranslationContext.beginTransaction()) {
            TransformResult result = translateComponentJson(serializeComponent(original));
            if (!result.changed()) return original;

            Component translated = deserializeComponent(result.value());
            transaction.commit();
            return translated;
        } catch (JsonParseException | IllegalStateException e) {
            return original;
        }
    }

    public static JsonElement translateLiteral(JsonElement json) {
        if (json == null || json.isJsonNull()) return json;

        try (var transaction = TranslationContext.beginTransaction()) {
            deserializeComponent(json);
            TransformResult result = translateComponentJson(json.deepCopy());
            if (!result.changed()) return json;

            deserializeComponent(result.value());
            transaction.commit();
            return result.value();
        } catch (JsonParseException | IllegalStateException e) {
            return json;
        }
    }

    public static String translateToJson(Component component) {
        if (component == null) return "";
        try (var transaction = TranslationContext.beginTransaction()) {
            String result = serializeComponent(translateLiteral(component)).toString();
            transaction.commit();
            return result;
        }
    }

    /**
     * Translates a component stored in NBT.
     *
     * <p>Legacy saves store components as JSON inside a {@link StringTag}; newer codecs may emit a
     * structured NBT value. Both representations are supported.
     */
    public static boolean translateNbtComponent(CompoundTag compound, String path) {
        return translateNbtComponent(compound, path, ResourceIds.path(path));
    }

    /**
     * Translates a component stored at {@code path}, using {@code keyPath} for the generated
     * language key.
     *
     * <p>The storage path and key path are deliberately separate. Save data names such as {@code
     * minecraft:custom_name} are implementation details and do not necessarily match the stable
     * key names used by the upstream extractor.
     */
    public static boolean translateNbtComponent(
            CompoundTag compound, String path, String keyPath) {
        if (compound == null || path == null || path.isBlank()) return false;

        try (var transaction = TranslationContext.beginTransaction();
                var ignored = TranslationContext.push(keyPath)) {
            TagPath tagPath = TagPath.of(path);
            CompoundTag parent = findNbtParent(compound, tagPath);
            if (parent == null) return false;

            Tag originalTag = parent.get(tagPath.name());
            if (originalTag == null) return false;

            Tag translatedTag =
                    translateNbtComponentTag(
                            originalTag,
                            originalTag instanceof StringTag
                                    ? NbtUtils.getString(parent, tagPath.name())
                                    : null);
            if (translatedTag == originalTag) return false;

            parent.put(tagPath.name(), translatedTag);
            transaction.commit();
            return true;
        } catch (JsonParseException | IllegalStateException e) {
            return false;
        }
    }

    /** Translates one component in an NBT list without changing the list's element type. */
    public static boolean translateNbtComponent(ListTag list, int index, String keyPath) {
        if (list == null || index < 0 || index >= list.size()) return false;

        try (var transaction = TranslationContext.beginTransaction();
                var ignored = TranslationContext.push(keyPath)) {
            Tag originalTag = list.get(index);
            Tag translatedTag =
                    translateNbtComponentTag(
                            originalTag,
                            originalTag instanceof StringTag ? NbtUtils.getString(list, index) : null);
            if (translatedTag == originalTag) return false;
            if (!list.setTag(index, translatedTag)) return false;
            transaction.commit();
            return true;
        } catch (JsonParseException | IllegalStateException e) {
            return false;
        }
    }

    /** Creates a new translatable NBT component from plain text. */
    public static boolean putTranslatedNbtComponent(
            CompoundTag compound,
            String path,
            String literal,
            boolean nonItalic,
            String keyPath) {
        if (compound == null
                || path == null
                || path.isBlank()
                || literal == null
                || literal.isBlank()) {
            return false;
        }

        try (var transaction = TranslationContext.beginTransaction();
                var ignored = TranslationContext.push(keyPath)) {
            Component translated = Component.translatable(TranslationContext.addEntry(literal));
            if (nonItalic) {
                translated = translated.copy().withStyle(Style.EMPTY.withItalic(false));
            }

            TagPath tagPath = TagPath.of(path);
            CompoundTag parent = findNbtParent(compound, tagPath);
            if (parent == null) return false;
            parent.put(tagPath.name(), serializeNbtComponent(translated));
            transaction.commit();
            return true;
        } catch (JsonParseException | IllegalStateException e) {
            return false;
        }
    }

    private static boolean looksLikeSerializedJson(String value) {
        if (value == null) return false;
        String trimmed = value.trim();
        if (trimmed.isEmpty()) return false;
        char first = trimmed.charAt(0);
        return first == '{' || first == '[' || first == '"';
    }

    private static Tag translateNbtComponentTag(Tag originalTag, String stringValue) {
        if (originalTag instanceof StringTag) {
            Component component;
            if (looksLikeSerializedJson(stringValue)) {
                try {
                    component = deserializeComponent(JsonParser.parseString(stringValue));
                } catch (JsonParseException | IllegalStateException ignoredException) {
                    component = deserializeNbtComponent(originalTag);
                }
            } else {
                component = deserializeNbtComponent(originalTag);
            }

            Component translated = translateLiteral(component);
            if (translated == component) return originalTag;

            // Legacy component lists are lists of JSON strings. Keeping StringTag here avoids
            // violating ListTag's homogeneous element-type requirement.
            return StringTag.valueOf(serializeComponent(translated).toString());
        }

        Component component = deserializeNbtComponent(originalTag);
        Component translated = translateLiteral(component);
        return translated == component ? originalTag : serializeNbtComponent(translated);
    }

    /**
     * Translates a component at a JSON path.
     *
     * <p>Path segments support object names, numeric array indexes, and {@code [*]} wildcards, for
     * example {@code body[*].contents} and {@code pages[0].raw}.
     *
     * <p>The generated language key mirrors the resolved location instead of the path expression, so
     * a wildcard produces one key per visited element, for example {@code body.0.contents}.
     */
    public static boolean translateJsonElement(JsonObject json, String path) {
        if (json == null || path == null || path.isBlank()) return false;

        try (var transaction = TranslationContext.beginTransaction()) {
            List<PathSegment> segments = parseJsonPath(path);
            JsonObject working = json.deepCopy();
            if (!translateJsonPath(working, segments, 0).changed()) return false;

            json.entrySet().clear();
            for (Map.Entry<String, JsonElement> entry : working.entrySet()) {
                json.add(entry.getKey(), entry.getValue());
            }
            transaction.commit();
            return true;
        } catch (JsonParseException | IllegalArgumentException | IllegalStateException e) {
            return false;
        }
    }

    private static TransformResult translateComponentJson(JsonElement json) {
        if (json == null || json.isJsonNull()) return TransformResult.unchanged(json);

        if (json.isJsonPrimitive()) {
            if (!json.getAsJsonPrimitive().isString()) return TransformResult.unchanged(json);

            String literal = json.getAsString();
            if (literal.isBlank()) return TransformResult.unchanged(json);
            return TransformResult.changed(
                    serializeComponent(Component.translatable(TranslationContext.addEntry(literal))));
        }

        if (json.isJsonArray()) {
            JsonArray array = json.getAsJsonArray();
            boolean changed = false;
            for (int i = 0; i < array.size(); i++) {
                TransformResult child = translateComponentJson(array.get(i));
                if (!child.changed()) continue;

                array.set(i, child.value());
                changed = true;
            }
            return new TransformResult(array, changed);
        }

        JsonObject component = json.getAsJsonObject();
        JsonObject result = component;
        ChangeTracker tracker = new ChangeTracker();

        if (isLiteralComponent(component)) {
            String literal = component.get("text").getAsString();
            if (!literal.isBlank()) {
                JsonElement encoded =
                        serializeComponent(Component.translatable(TranslationContext.addEntry(literal)));
                if (!encoded.isJsonObject()) {
                    throw new JsonParseException("A translatable component must encode as an object");
                }

                result = encoded.getAsJsonObject();
                copyLiteralMetadata(component, result);
                tracker.add(true);
            }
        }

        tracker.add(translateComponentProperty(result, "extra"));
        tracker.add(translateComponentProperty(result, "separator"));
        tracker.add(translateComponentArguments(result, "with"));
        tracker.add(translateHoverEvent(result, LEGACY_HOVER_EVENT));
        tracker.add(translateHoverEvent(result, HOVER_EVENT));
        return new TransformResult(result, tracker.isChanged());
    }

    private static boolean isLiteralComponent(JsonObject component) {
        if (!component.has("text") || !component.get("text").isJsonPrimitive()) return false;
        if (!component.get("text").getAsJsonPrimitive().isString()) return false;
        return !component.has("type") || "text".equals(component.get("type").getAsString());
    }

    private static void copyLiteralMetadata(JsonObject source, JsonObject target) {
        for (Map.Entry<String, JsonElement> entry : source.entrySet()) {
            String name = entry.getKey();
            if ("text".equals(name) || "type".equals(name)) continue;
            target.add(name, entry.getValue().deepCopy());
        }
    }

    private static boolean translateComponentProperty(JsonObject component, String name) {
        if (!component.has(name)) return false;

        TransformResult child = translateComponentJson(component.get(name));
        if (!child.changed()) return false;
        component.add(name, child.value());
        return true;
    }

    private static boolean translateComponentArguments(JsonObject component, String name) {
        if (!component.has(name) || !component.get(name).isJsonArray()) return false;

        JsonArray arguments = component.getAsJsonArray(name);
        boolean changed = false;
        for (int i = 0; i < arguments.size(); i++) {
            JsonElement argument = arguments.get(i);
            if (!argument.isJsonObject()
                    && !argument.isJsonArray()
                    && !(argument.isJsonPrimitive() && argument.getAsJsonPrimitive().isString())) {
                continue;
            }

            TransformResult translated = translateComponentJson(argument);
            if (!translated.changed()) continue;
            arguments.set(i, translated.value());
            changed = true;
        }
        return changed;
    }

    private static boolean translateHoverEvent(JsonObject component, String name) {
        if (!component.has(name) || !component.get(name).isJsonObject()) return false;

        JsonObject hoverEvent = component.getAsJsonObject(name);
        if (!hoverEvent.has("action")) return false;

        String action = hoverEvent.get("action").getAsString();
        if ("show_text".equals(action)) {
            ChangeTracker tracker = new ChangeTracker();
            tracker.add(translateComponentProperty(hoverEvent, "contents"));
            tracker.add(translateComponentProperty(hoverEvent, "value"));
            return tracker.isChanged();
        }

        if (!"show_entity".equals(action)) return false;
        for (String contentName : List.of("contents", "value")) {
            if (!hoverEvent.has(contentName) || !hoverEvent.get(contentName).isJsonObject()) continue;

            JsonObject entity = hoverEvent.getAsJsonObject(contentName);
            if (translateComponentProperty(entity, "name")) return true;
        }
        return false;
    }

    private static PathTransform translateJsonPath(
            JsonElement current, List<PathSegment> segments, int segmentIndex) {
        if (segmentIndex >= segments.size()) {
            JsonElement translated = translateLiteral(current);
            return new PathTransform(translated, translated != current);
        }

        PathSegment segment = segments.get(segmentIndex);
        JsonElement child = current;
        JsonObject parentObject = null;
        if (!segment.name().isEmpty()) {
            if (!current.isJsonObject()) return PathTransform.unchanged(current);
            parentObject = current.getAsJsonObject();
            if (!parentObject.has(segment.name())) return PathTransform.unchanged(current);
            child = parentObject.get(segment.name());
        }

        // The key path follows the resolved location, so an unnamed segment contributes nothing and a
        // wildcard contributes the visited index instead of the literal '*'.
        try (var ignored = TranslationContext.push(keySegment(segment.name()))) {
            if (!segment.hasArraySelector()) {
                PathTransform translated = translateJsonPath(child, segments, segmentIndex + 1);
                if (translated.changed() && parentObject != null) {
                    parentObject.add(segment.name(), translated.value());
                }
                return new PathTransform(current, translated.changed());
            }

            if (!child.isJsonArray()) return PathTransform.unchanged(current);
            JsonArray array = child.getAsJsonArray();
            boolean changed = false;
            if (segment.wildcard()) {
                for (int i = 0; i < array.size(); i++) {
                    PathTransform translated;
                    try (var e = TranslationContext.push(Integer.toString(i))) {
                        translated = translateJsonPath(array.get(i), segments, segmentIndex + 1);
                    }
                    if (!translated.changed()) continue;
                    array.set(i, translated.value());
                    changed = true;
                }
                return new PathTransform(current, changed);
            }

            if (segment.index() < 0 || segment.index() >= array.size()) {
                return PathTransform.unchanged(current);
            }

            PathTransform translated;
            try (var e = TranslationContext.push(Integer.toString(segment.index()))) {
                translated = translateJsonPath(array.get(segment.index()), segments, segmentIndex + 1);
            }
            if (translated.changed()) {
                array.set(segment.index(), translated.value());
                changed = true;
            }
            return new PathTransform(current, changed);
        }
    }

    /** Converts a JSON member name into a language-key segment. */
    private static String keySegment(String name) {
        if (name.isEmpty()) return "";
        // Namespaced schema names such as 'minecraft:gameplay/bed_rule' would otherwise produce keys
        // containing ':' and '/', which cannot be addressed by resource-pack language files.
        return ResourceIds.path(name).replace('/', '.');
    }

    private static List<PathSegment> parseJsonPath(String path) {
        List<PathSegment> segments = new ArrayList<>();
        for (String rawSegment : path.split("\\.")) {
            int bracket = rawSegment.indexOf('[');
            if (bracket < 0) {
                segments.add(new PathSegment(rawSegment, -1, false, false));
                continue;
            }

            if (!rawSegment.endsWith("]")) {
                throw new IllegalArgumentException("Invalid JSON path segment: " + rawSegment);
            }

            String name = rawSegment.substring(0, bracket);
            String selector = rawSegment.substring(bracket + 1, rawSegment.length() - 1);
            if ("*".equals(selector)) {
                segments.add(new PathSegment(name, -1, true, true));
            } else {
                segments.add(
                        new PathSegment(name, Integer.parseInt(selector), false, true));
            }
        }
        return segments;
    }

    private static Component deserializeComponent(JsonElement json) {
        return ComponentSerialization.CODEC
                .parse(JsonOps.INSTANCE, json)
                .getOrThrow(JsonParseException::new);
    }

    private static JsonElement serializeComponent(Component component) {
        return ComponentSerialization.CODEC
                .encodeStart(JsonOps.INSTANCE, component)
                .getOrThrow(JsonParseException::new);
    }

    private static Component deserializeNbtComponent(Tag tag) {
        return ComponentSerialization.CODEC
                .parse(NbtOps.INSTANCE, tag)
                .getOrThrow(JsonParseException::new);
    }

    private static Tag serializeNbtComponent(Component component) {
        return ComponentSerialization.CODEC
                .encodeStart(NbtOps.INSTANCE, component)
                .getOrThrow(JsonParseException::new);
    }

    private static CompoundTag findNbtParent(CompoundTag root, TagPath path) {
        CompoundTag current = root;
        for (String name : path.parents()) {
            current = NbtUtils.findCompound(current, name);
            if (current == null) return null;
        }
        return current;
    }

    private record TransformResult(JsonElement value, boolean changed) {
        private static TransformResult unchanged(JsonElement value) {
            return new TransformResult(value, false);
        }

        private static TransformResult changed(JsonElement value) {
            return new TransformResult(value, true);
        }
    }

    private record PathTransform(JsonElement value, boolean changed) {
        private static PathTransform unchanged(JsonElement value) {
            return new PathTransform(value, false);
        }
    }

    private record PathSegment(String name, int index, boolean wildcard, boolean hasArraySelector) {}

    private record TagPath(String[] parents, String name) {
        private static TagPath of(String path) {
            String[] names = path.split("\\.");
            String[] parents = new String[Math.max(0, names.length - 1)];
            System.arraycopy(names, 0, parents, 0, parents.length);
            return new TagPath(parents, names[names.length - 1]);
        }
    }
}
