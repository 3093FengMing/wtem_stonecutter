package me.fengming.wtem.common.util;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.mojang.serialization.JsonOps;

import java.util.*;
import java.util.stream.Stream;

import me.fengming.wtem.common.core.extraction.TranslationContext;
import me.fengming.wtem.common.core.extraction.pattern.DataPath;
import me.fengming.wtem.common.core.handler.datapack.command.FunctionHandler;
import me.fengming.wtem.common.core.visitor.ItemTagVisitor;
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
    private static final String LEGACY_CLICK_EVENT = "clickEvent";
    private static final String CLICK_EVENT = "click_event";

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
            TransformResult result;
            if (!containsMacro(json)) {
                deserializeComponent(json);
            }
            result = translateComponentJson(json.deepCopy());
            if (!result.changed()) return json;

            if (!containsMacro(json)) deserializeComponent(result.value());
            transaction.commit();
            return result.value();
        } catch (JsonParseException | IllegalStateException e) {
            return json;
        }
    }

    /**
     * Translates a schema object that has already been decoded by a Minecraft codec.
     *
     * <p>This is used for command arguments such as inline dialogs. The caller must first let
     * Brigadier decode the argument into its Minecraft value; this method only walks that decoded
     * value's codec representation and never searches the original command for arbitrary JSON.
     */
    public static JsonElement translateDecodedTree(JsonElement json) {
        if (json == null || json.isJsonNull()) return json;

        try (var transaction = TranslationContext.beginTransaction()) {
            JsonElement working = json.deepCopy();
            TransformResult result = translateJsonComponentTree(working);
            if (!result.changed()) return json;
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
        return translateNbtComponent(list, index, keyPath, false);
    }

    /**
     * Translates one component in a list and prefers the structured NBT representation.
     *
     * <p>Sign messages are a notable edge case.  On the current component codec a message may be
     * represented directly by a compound ({@code {translate:"..."}}), while older releases only
     * accepted a JSON string.  Keeping this choice here, next to the list mutation, prevents a
     * sign's message list from accidentally containing a JSON object <em>inside</em> a StringTag.
     * When a legacy ListTag rejects a mixed element type we fall back to the codec's JSON string,
     * which is the only syntax that release can write back.
     */
    public static boolean translateNbtComponentAsStructured(
            ListTag list, int index, String keyPath) {
        return translateNbtComponent(list, index, keyPath, true);
    }

    private static boolean translateNbtComponent(
            ListTag list, int index, String keyPath, boolean preferStructured) {
        if (list == null || index < 0 || index >= list.size()) return false;

        try (var transaction = TranslationContext.beginTransaction();
                var ignored = TranslationContext.push(keyPath)) {
            Tag originalTag = list.get(index);
            Tag translatedTag =
                    translateNbtComponentTag(
                            originalTag,
                            originalTag instanceof StringTag ? NbtUtils.getString(list, index) : null,
                            preferStructured);
            if (translatedTag == originalTag) return false;
            if (!list.setTag(index, translatedTag)) {
                // Prior to the heterogeneous ListTag implementation, a sign's StringTag list
                // cannot hold a CompoundTag.  Preserve the translation for those versions in the
                // representation their codec accepts instead of reporting a false negative.
                if (!preferStructured || !(translatedTag instanceof CompoundTag)) return false;
                Component translatedComponent = deserializeNbtComponent(translatedTag);
                Tag legacy = StringTag.valueOf(serializeComponent(translatedComponent).toString());
                if (!list.setTag(index, legacy)) return false;
            }
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
        return translateNbtComponentTag(originalTag, stringValue, false);
    }

    private static Tag translateNbtComponentTag(
            Tag originalTag, String stringValue, boolean preferStructured) {
        if (originalTag instanceof StringTag stringTag) {
            // A string tag means one of two different things. Legacy saves store a whole component
            // serialized to JSON in one, while the component format reads a string as the literal
            // text of a component in its own right. Which one it is decides the form the
            // translation has to be written back in, so the answer is carried to the write below.
            boolean serializedJson = false;
            JsonElement sourceJson = null;
            if (looksLikeSerializedJson(stringValue)) {
                try {
                    sourceJson = JsonParser.parseString(stringValue);
                    serializedJson = true;
                } catch (JsonParseException | IllegalStateException ignoredException) {}
            }

            if (sourceJson == null) {
                JsonObject literal = new JsonObject();
                literal.addProperty("text", stringValue == null ? "" : stringValue);
                sourceJson = literal;
            }

            try {
                JsonElement translatedJson = translateLiteral(sourceJson);
                if (translatedJson != sourceJson) {
                    if (!preferStructured && serializedJson) {
                        return StringTag.valueOf(translatedJson.toString());
                    }
                    if (translatedJson.isJsonObject()) {
                        return NbtUtils.fromJson(translatedJson.getAsJsonObject());
                    }
                }
            } catch (JsonParseException | IllegalStateException ignoredException) {}

            Component component;
            try {
                //~ if >=1.21.5 '.getAsString()' -> '.value()'
                component = deserializeComponent(Optional.of(stringTag.value())
                    .map(JsonParser::parseString).orElseThrow());
            } catch (JsonParseException ignoredException) {
                try {
                    component = deserializeNbtComponent(originalTag);
                } catch (IllegalStateException | JsonParseException ignoredException2) {
                    return originalTag;
                }
            } catch (IllegalStateException | NoSuchElementException ignoredException) {
                return originalTag;
            }

            Component translated = translateLiteral(component);
            if (translated == component) return originalTag;

            // Writing JSON back over a field that held literal text would leave a component whose
            // text reads as '{"translate":...}' in game rather than a translatable one, so only a
            // field that already carried JSON keeps carrying it. Before 1.21.5 the component
            // argument parser only accepts the JSON form, so there structured NBT is not an option.
            //? if >=1.21.5 {
            return !preferStructured && serializedJson
                    ? StringTag.valueOf(serializeComponent(translated).toString())
                    : serializeNbtComponent(translated);
            //?} else
            //return StringTag.valueOf(serializeComponent(translated).toString());
        }

        try {
            JsonElement sourceJson = NbtOps.INSTANCE.convertTo(JsonOps.INSTANCE, originalTag);
            if (sourceJson.isJsonObject() || sourceJson.isJsonArray()) {
                JsonElement translatedJson = translateLiteral(sourceJson);
                if (translatedJson != sourceJson) {
                    // NBT component fields are not always compounds.  Text-display entities and
                    // function arguments may carry a component sequence as a ListTag; converting
                    // the translated JSON back through the same DynamicOps pair preserves that
                    // list instead of falling through to a codec path that cannot decode it.
                    return JsonOps.INSTANCE.convertTo(NbtOps.INSTANCE, translatedJson);
                }
            }
        } catch (JsonParseException | IllegalStateException ignoredException) {}

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
        try {
            return translateJsonElement(json, DataPath.parse(path));
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    /** Translates the value selected by a pre-validated custom extraction path. */
    public static boolean translateJsonElement(JsonObject json, DataPath path) {
        return translateJsonElement((JsonElement) json, path);
    }

    /** Translates a value selected from any mutable JSON root, including an array root. */
    public static boolean translateJsonElement(JsonElement json, DataPath path) {
        if (json == null || path == null || json.isJsonNull()) return false;

        try (var transaction = TranslationContext.beginTransaction()) {
            JsonElement working = json.deepCopy();
            if (!translateJsonPath(working, path.segments(), 0).changed()) return false;
            if (json.isJsonObject() && working.isJsonObject()) {
                JsonObject target = json.getAsJsonObject();
                target.entrySet().clear();
                for (Map.Entry<String, JsonElement> entry : working.getAsJsonObject().entrySet()) {
                    target.add(entry.getKey(), entry.getValue());
                }
            } else if (json.isJsonArray() && working.isJsonArray()) {
                JsonArray target = json.getAsJsonArray();
                // JsonArray#clear ??
                while (!target.isEmpty()) target.remove(target.size() - 1);
                for (JsonElement entry : working.getAsJsonArray()) target.add(entry);
            } else {
                return false;
            }
            transaction.commit();
            return true;
        } catch (JsonParseException | IllegalArgumentException | IllegalStateException e) {
            return false;
        }
    }

    /**
     * Catalogs string leaves selected by an explicit {@code plain_string} rule without replacing
     * them with components. Every value records a warning because the selected schema may still
     * contain identifiers or other non-visible strings.
     */
    public static int catalogJsonStrings(JsonObject json, DataPath path) {
        return catalogJsonStrings((JsonElement) json, path);
    }

    /** Catalogs string leaves selected from any JSON root without changing that root. */
    public static int catalogJsonStrings(JsonElement json, DataPath path) {
        if (json == null || path == null || json.isJsonNull()) return 0;
        try (var transaction = TranslationContext.beginTransaction()) {
            int count = catalogJsonPath(json, path.segments(), 0);
            if (count == 0) return 0;
            transaction.commit();
            return count;
        }
    }

    private static TransformResult translateComponentJson(JsonElement json) {
        if (json == null || json.isJsonNull()) return TransformResult.unchanged(json);

        if (json.isJsonPrimitive()) {
            if (!json.getAsJsonPrimitive().isString()) return TransformResult.unchanged(json);
            return translateTextComponent(json.getAsString(), new JsonObject());
        }
        if (json.isJsonArray()) return translateComponentArray(json.getAsJsonArray());
        if (!json.isJsonObject()) return TransformResult.unchanged(json);
        return translateComponentObject(json.getAsJsonObject());
    }

    /** Translates one component while retaining every style and event member. */
    private static TransformResult translateComponentObject(JsonObject source) {
        if (isLiteralComponent(source)
                && source.has("extra")
                && source.get("extra").isJsonArray()) {
            // Component codec serialization represents an input component sequence as one root
            // literal plus an `extra` array.  Treat that representation as a single sequence so a
            // score/selector/NBT child can become a `with` argument between two literal siblings.
            // The normal array path is also responsible for splitting differently styled text;
            // bypassing it here would apply the root style to every sibling and discard their
            // individual formatting.
            JsonArray sequence = new JsonArray();
            JsonObject root = source.deepCopy();
            root.remove("extra");
            sequence.add(root);
            source.getAsJsonArray("extra").forEach(child -> sequence.add(child.deepCopy()));
            TransformResult translated = translateComponentArray(sequence);
            JsonArray result = translated.value().getAsJsonArray();
            return new TransformResult(
                    result.size() == 1 ? result.get(0) : result,
                    translated.changed());
        }

        JsonObject result = source;
        boolean changed = false;

        if (isLiteralComponent(source)) {
            TransformResult translated =
                    translateTextComponent(source.get("text").getAsString(), source);
            result = translated.value().getAsJsonObject();
            changed = translated.changed();
        }

        ChangeTracker tracker = new ChangeTracker();
        tracker.add(changed);
        tracker.add(translateComponentProperty(result, "extra"));
        tracker.add(translateComponentProperty(result, "separator"));
        tracker.add(translateComponentArguments(result, "with"));
        tracker.add(translateHoverEvent(result, LEGACY_HOVER_EVENT));
        tracker.add(translateHoverEvent(result, HOVER_EVENT));
        tracker.add(translateClickEvent(result, LEGACY_CLICK_EVENT, true));
        tracker.add(translateClickEvent(result, CLICK_EVENT, false));
        return new TransformResult(result, tracker.isChanged());
    }

    /**
     * Translates an array of components as a sequence.  Adjacent pieces with the same style are
     * intentionally folded into one translatable component, which is what makes
     * {@code text + score + text} become one {@code translate/with} component instead of three
     * unrelated language entries.
     */
    private static TransformResult translateComponentArray(JsonArray source) {
        JsonArray result = new JsonArray();
        ChangeTracker tracker = new ChangeTracker();
        int cursor = 0;
        while (cursor < source.size()) {
            JsonElement element = source.get(cursor);
            if (!isComponentElement(element)) {
                result.add(element.deepCopy());
                cursor++;
                continue;
            }

            int end = cursor + 1;
            String textStyle = isTextElement(source.get(cursor))
                    ? componentStyle(source.get(cursor))
                    : null;
            while (end < source.size()
                    && isComponentElement(source.get(end))) {
                JsonElement next = source.get(end);
                // Dynamic components carry their own style inside the `with` argument and must
                // not split the surrounding literal sequence.  Literal siblings still need equal
                // style, otherwise folding them would silently lose per-piece formatting.
                if (isTextElement(next)) {
                    String nextStyle = componentStyle(next);
                    if (textStyle == null) textStyle = nextStyle;
                    else if (!Objects.equals(textStyle, nextStyle)) break;
                }
                end++;
            }

            List<JsonElement> group = new ArrayList<>();
            for (int i = cursor; i < end; i++) group.add(source.get(i).deepCopy());
            TransformResult translated = translateComponentGroup(group);
            result.add(translated.value());
            tracker.add(translated.changed());
            cursor = end;
        }

        return new TransformResult(result, tracker.isChanged());
    }

    private static TransformResult translateComponentGroup(List<JsonElement> group) {
        JsonObject anchor = null;
        StringBuilder template = new StringBuilder();
        JsonArray arguments = new JsonArray();
        JsonArray translatedGroup = new JsonArray();
        ChangeTracker nestedChanges = new ChangeTracker();
        boolean hasText = false;

        for (JsonElement element : group) {
            if (isTextElement(element)) {
                translatedGroup.add(element.deepCopy());
                JsonObject text =
                        element.isJsonObject()
                                ? element.getAsJsonObject()
                                : new JsonObject();
                String literal =
                        element.isJsonPrimitive() ? element.getAsString() : text.get("text").getAsString();
                if (anchor == null && element.isJsonObject()) anchor = text;
                if (literal.isEmpty()) continue;
                hasText = true;
                appendTextTemplate(literal, template, arguments);
            } else {
                TransformResult translated = translateComponentJson(element);
                JsonElement argument = translated.value();
                translatedGroup.add(argument.deepCopy());
                nestedChanges.add(translated.changed());
                template.append("%s");
                arguments.add(argument.deepCopy());
            }
        }

        if (!hasText) {
            JsonElement value = translatedGroup.size() == 1
                    ? translatedGroup.get(0)
                    : translatedGroup;
            return new TransformResult(value, nestedChanges.isChanged());
        }

        JsonObject translated = translatedTextObject(template.toString(), arguments, anchor);
        TransformResult nested = translateComponentObject(translated);
        return new TransformResult(nested.value(), true);
    }

    private static boolean isLiteralComponent(JsonObject component) {
        return isTextElement(component)
                && (!component.has("type") || "text".equals(component.get("type").getAsString()));
    }

    private static boolean isTextElement(JsonElement element) {
        if (element == null || element.isJsonNull()) return false;
        if (element.isJsonPrimitive()) return element.getAsJsonPrimitive().isString();
        return element.isJsonObject()
                && element.getAsJsonObject().has("text")
                && element.getAsJsonObject().get("text").isJsonPrimitive()
                && element.getAsJsonObject().get("text").getAsJsonPrimitive().isString();
    }

    private static boolean isComponentElement(JsonElement element) {
        if (isTextElement(element)) return true;
        if (!element.isJsonObject()) return false;
        JsonObject object = element.getAsJsonObject();
        return Stream.of(
            "translate", "score", "selector",
                "nbt", "keybind", "object", CLICK_EVENT,
                HOVER_EVENT, LEGACY_CLICK_EVENT,
                LEGACY_HOVER_EVENT)
            .anyMatch(object::has);
    }

    private static String componentStyle(JsonElement element) {
        if (!element.isJsonObject()) return "{}";
        JsonObject style = element.getAsJsonObject().deepCopy();
        for (String name :
                List.of(
                    "text", "type", "translate",
                    "fallback", "with", "score",
                    "selector", "nbt", "keybind",
                    "object")) {
            style.remove(name);
        }
        return style.toString();
    }

    private static TransformResult translateTextComponent(String literal, JsonObject metadata) {
        if (literal == null || literal.isBlank()) return TransformResult.unchanged(metadata);

        JsonArray arguments = new JsonArray();
        StringBuilder template = new StringBuilder();
        appendTextTemplate(literal, template, arguments);
        return new TransformResult(
                translatedTextObject(template.toString(), arguments, metadata), true);
    }

    private static JsonObject translatedTextObject(
            String template, JsonArray arguments, JsonObject metadata) {
        JsonObject result = new JsonObject();
        result.addProperty("translate", TranslationContext.addEntry(template));
        if (metadata != null) {
            for (Map.Entry<String, JsonElement> entry : metadata.entrySet()) {
                if ("text".equals(entry.getKey())
                        || "type".equals(entry.getKey())
                        || "translate".equals(entry.getKey())
                        || "with".equals(entry.getKey())) continue;
                result.add(entry.getKey(), entry.getValue().deepCopy());
            }
        }
        // Keep component styling next to the translation key and append the parameters last.  This
        // is also the conventional shape used by hand-written commands:
        // {"translate":"...","color":"red","with":[...]}
        if (!arguments.isEmpty()) result.add("with", arguments);
        return result;
    }

    private static void appendTextTemplate(
            String literal, StringBuilder template, JsonArray arguments) {
        int cursor = 0;
        MacroSpan macro;
        while ((macro = nextMacro(literal, cursor)) != null) {
            appendEscapedLiteral(template, literal.substring(cursor, macro.start()));
            template.append("%s");
            JsonObject argument = new JsonObject();
            argument.addProperty("text", literal.substring(macro.start(), macro.end()));
            arguments.add(argument);
            cursor = macro.end();
        }
        appendEscapedLiteral(template, literal.substring(cursor));
    }

    private static void appendEscapedLiteral(StringBuilder template, String literal) {
        template.append(literal.replace("%", "%%"));
    }

    private static boolean containsMacro(JsonElement json) {
        if (json == null || json.isJsonNull()) return false;
        if (json.isJsonPrimitive()) {
            return json.getAsJsonPrimitive().isString()
                    && nextMacro(json.getAsString(), 0) != null;
        }
        if (json.isJsonArray()) {
            for (JsonElement child : json.getAsJsonArray()) {
                if (containsMacro(child)) return true;
            }
            return false;
        }
        for (Map.Entry<String, JsonElement> entry : json.getAsJsonObject().entrySet()) {
            if (containsMacro(entry.getValue())) return true;
        }
        return false;
    }

    /** Finds one Minecraft macro marker without treating nested parentheses as a valid name. */
    private static MacroSpan nextMacro(String value, int from) {
        if (value == null) return null;
        int start = Math.max(0, from);
        while ((start = value.indexOf("$(", start)) >= 0) {
            int close = value.indexOf(')', start + 2);
            if (close < 0) return null;
            if (value.indexOf('(', start + 2) >= 0
                    && value.indexOf('(', start + 2) < close) {
                start += 2;
                continue;
            }
            return new MacroSpan(start, close + 1);
        }
        return null;
    }

    private record MacroSpan(int start, int end) {}

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
            // A macro text is the parameter deliberately emitted by the surrounding
            // translate component. Translating that argument again would recurse forever and
            // would also replace the parameter with a second, unrelated key.
            if (isTextElement(argument) && containsMacro(argument)) continue;

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
        ChangeTracker tracker = new ChangeTracker();

        for (String contentName : List.of("contents", "value")) {
            if (!hoverEvent.has(contentName) || !hoverEvent.get(contentName).isJsonObject()) continue;

            JsonObject content = hoverEvent.getAsJsonObject(contentName);
            if (translateComponentProperty(content, "name")) return true;
        }

        switch (action) {
            case "show_text" -> {
                tracker.add(translateComponentProperty(hoverEvent, "contents"));
                tracker.add(translateComponentProperty(hoverEvent, "value"));
                return tracker.isChanged();
            }
            case "show_item" -> {
                ItemTagVisitor visitor = new ItemTagVisitor();
                visitor.visitComponents(
                    hoverEvent.get("id").getAsString(),
                    NbtUtils.fromJson(hoverEvent.get("components").getAsJsonObject()));
                tracker.add(visitor.isChanged());
                return tracker.isChanged();
            }
            case "show_entity" -> {
                tracker.add(translateComponentProperty(hoverEvent, "name"));
                return tracker.isChanged();
            }
        }

        return false;
    }

    private static boolean translateClickEvent(JsonObject component, String name, boolean legacy) {
        if (!component.has(name) || !component.get(name).isJsonObject()) return false;

        JsonObject clickEvent = component.getAsJsonObject(name);
        if (!clickEvent.has("action")) return false;

        String action = clickEvent.get("action").getAsString();
        if (!"run_command".equals(action)) return false;

        // Both spellings have existed with both property names in datapacks in the wild.  The
        // event key's casing is not enough to decide whether the command is under value or command.
        String property =
                clickEvent.has(legacy ? "value" : "command")
                        ? (legacy ? "value" : "command")
                        : (legacy ? "command" : "value");
        if (!clickEvent.has(property)
                || !clickEvent.get(property).isJsonPrimitive()
                || !clickEvent.get(property).getAsJsonPrimitive().isString()) return false;

        String command = clickEvent.get(property).getAsString();
        if (command.startsWith("/")) command = command.substring(1);
        String translated = FunctionHandler.processNestedCommand(command);
        boolean changed = !command.equals(translated);
        if (changed) {
            clickEvent.addProperty(property, translated);
        }

        return changed;
    }

    private static PathTransform translateJsonPath(
            JsonElement current, List<DataPath.Segment> segments, int segmentIndex) {
        if (segmentIndex >= segments.size()) {
            TransformResult translated = translateJsonComponentTree(current);
            return new PathTransform(translated.value(), translated.changed());
        }

        DataPath.Segment segment = segments.get(segmentIndex);
        if (segment instanceof DataPath.KeySegment key) {
            if (!current.isJsonObject()) return PathTransform.unchanged(current);
            JsonObject object = current.getAsJsonObject();
            if (!key.wildcard()) {
                if (!object.has(key.name())) return PathTransform.unchanged(current);
                PathTransform translated;
                try (var ignored = TranslationContext.push(keySegment(key.name()))) {
                    translated = translateJsonPath(object.get(key.name()), segments, segmentIndex + 1);
                }
                if (translated.changed()) object.add(key.name(), translated.value());
                return new PathTransform(current, translated.changed());
            }

            boolean changed = false;
            for (Map.Entry<String, JsonElement> entry : new ArrayList<>(object.entrySet())) {
                PathTransform translated;
                try (var ignored = TranslationContext.push(keySegment(entry.getKey()))) {
                    translated = translateJsonPath(entry.getValue(), segments, segmentIndex + 1);
                }
                if (!translated.changed()) continue;
                object.add(entry.getKey(), translated.value());
                changed = true;
            }
            return new PathTransform(current, changed);
        }

        if (!(segment instanceof DataPath.IndexSegment index) || !current.isJsonArray()) {
            return PathTransform.unchanged(current);
        }
        JsonArray array = current.getAsJsonArray();
        if (!index.wildcard()) {
            if (index.index() < 0 || index.index() >= array.size()) {
                return PathTransform.unchanged(current);
            }
            PathTransform translated;
            try (var ignored = TranslationContext.push(Integer.toString(index.index()))) {
                translated = translateJsonPath(array.get(index.index()), segments, segmentIndex + 1);
            }
            if (translated.changed()) array.set(index.index(), translated.value());
            return new PathTransform(current, translated.changed());
        }

        boolean changed = false;
        for (int elementIndex = 0; elementIndex < array.size(); elementIndex++) {
            PathTransform translated;
            try (var ignored = TranslationContext.push(Integer.toString(elementIndex))) {
                translated = translateJsonPath(array.get(elementIndex), segments, segmentIndex + 1);
            }
            if (!translated.changed()) continue;
            array.set(elementIndex, translated.value());
            changed = true;
        }
        return new PathTransform(current, changed);
    }

    private static int catalogJsonPath(
            JsonElement current, List<DataPath.Segment> segments, int segmentIndex) {
        if (current == null || current.isJsonNull()) return 0;
        if (segmentIndex >= segments.size()) return catalogJsonTree(current);

        DataPath.Segment segment = segments.get(segmentIndex);
        if (segment instanceof DataPath.KeySegment key) {
            if (!current.isJsonObject()) return 0;
            JsonObject object = current.getAsJsonObject();
            if (!key.wildcard()) {
                if (!object.has(key.name())) return 0;
                try (var ignored = TranslationContext.push(keySegment(key.name()))) {
                    return catalogJsonPath(object.get(key.name()), segments, segmentIndex + 1);
                }
            }

            int count = 0;
            for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
                try (var ignored = TranslationContext.push(keySegment(entry.getKey()))) {
                    count += catalogJsonPath(entry.getValue(), segments, segmentIndex + 1);
                }
            }
            return count;
        }

        if (!(segment instanceof DataPath.IndexSegment index) || !current.isJsonArray()) return 0;
        JsonArray array = current.getAsJsonArray();
        if (!index.wildcard()) {
            if (index.index() < 0 || index.index() >= array.size()) return 0;
            try (var ignored = TranslationContext.push(Integer.toString(index.index()))) {
                return catalogJsonPath(array.get(index.index()), segments, segmentIndex + 1);
            }
        }

        int count = 0;
        for (int elementIndex = 0; elementIndex < array.size(); elementIndex++) {
            try (var ignored = TranslationContext.push(Integer.toString(elementIndex))) {
                count += catalogJsonPath(array.get(elementIndex), segments, segmentIndex + 1);
            }
        }
        return count;
    }

    private static int catalogJsonTree(JsonElement value) {
        if (value.isJsonPrimitive()) {
            if (!value.getAsJsonPrimitive().isString()) return 0;
            String text = value.getAsString();
            if (text.isBlank()) return 0;
            TranslationContext.addCatalogEntry(text);
            TranslationContext.recordWarning(
                    "pattern_json_string",
                    "A JSON string selected by a plain_string pattern remains unchanged and needs manual review");
            return 1;
        }
        if (value.isJsonArray()) {
            int count = 0;
            JsonArray array = value.getAsJsonArray();
            for (int index = 0; index < array.size(); index++) {
                try (var ignored = TranslationContext.push(Integer.toString(index))) {
                    count += catalogJsonTree(array.get(index));
                }
            }
            return count;
        }
        if (!value.isJsonObject()) return 0;
        int count = 0;
        for (Map.Entry<String, JsonElement> entry : value.getAsJsonObject().entrySet()) {
            try (var ignored = TranslationContext.push(keySegment(entry.getKey()))) {
                count += catalogJsonTree(entry.getValue());
            }
        }
        return count;
    }

    /**
     * Translates a component tree embedded in an arbitrary JSON schema.
     *
     * <p>Resource schemas such as dialogs are not themselves Minecraft chat components.  Running
     * their {@code description}, {@code contents}, or action objects through the component codec
     * therefore makes a valid nested component look invalid and drops the whole target.  This
     * schema-neutral traversal only treats object/array children as nested components, preserves
     * unknown dialog fields, and handles command-bearing action/event objects explicitly.
     */
    private static TransformResult translateJsonComponentTree(JsonElement json) {
        if (json == null || json.isJsonNull()) return TransformResult.unchanged(json);
        if (isComponentElement(json)) return translateComponentJson(json);

        if (json.isJsonArray()) {
            JsonArray source = json.getAsJsonArray();
            if (isComponentSequence(source)) return translateComponentArray(source);

            JsonArray result = new JsonArray();
            boolean changed = false;
            for (JsonElement child : source) {
                TransformResult translated = translateJsonComponentTree(child);
                result.add(translated.value());
                changed |= translated.changed();
            }
            return new TransformResult(result, changed);
        }

        if (json.isJsonPrimitive()) return TransformResult.unchanged(json);

        JsonObject object = json.getAsJsonObject();
        ChangeTracker tracker = new ChangeTracker();
        for (Map.Entry<String, JsonElement> entry : new ArrayList<>(object.entrySet())) {
            String name = entry.getKey();
            JsonElement child = entry.getValue();
            TransformResult translated;
            if (child.isJsonPrimitive()
                    && child.getAsJsonPrimitive().isString()
                    && isNestedComponentProperty(name)) {
                translated = translateComponentJson(child);
            } else if (child.isJsonObject() || child.isJsonArray()) {
                translated = translateJsonComponentTree(child);
            } else {
                continue;
            }
            if (!translated.changed()) continue;
            object.add(name, translated.value());
            tracker.add(true);
        }

        tracker.add(translateJsonHoverEvent(object, LEGACY_HOVER_EVENT));
        tracker.add(translateJsonHoverEvent(object, HOVER_EVENT));
        tracker.add(translateClickEvent(object, LEGACY_CLICK_EVENT, true));
        tracker.add(translateClickEvent(object, CLICK_EVENT, false));
        tracker.add(translateDialogAction(object));
        return new TransformResult(object, tracker.isChanged());
    }

    private static boolean isComponentSequence(JsonArray array) {
        if (array.isEmpty()) return false;
        for (JsonElement element : array) {
            if (!isComponentElement(element)) return false;
        }
        return true;
    }

    private static boolean isNestedComponentProperty(String name) {
        return switch (name) {
            case "contents", "description", "title",
                 "external_title", "label", "tooltip",
                 "display", "separator", "extra",
                 "name" -> true;
            default -> false;
        };
    }

    /** Handles legacy show_text hover values that are serialized as a JSON string. */
    private static boolean translateJsonHoverEvent(JsonObject component, String name) {
        if (!component.has(name) || !component.get(name).isJsonObject()) return false;

        JsonObject event = component.getAsJsonObject(name);
        if (!event.has("action")
                || !event.get("action").isJsonPrimitive()
                || !"show_text".equals(event.get("action").getAsString())) {
            return false;
        }

        boolean changed = false;
        for (String contentName : List.of("contents", "value")) {
            JsonElement content = event.get(contentName);
            if (content == null || !content.isJsonPrimitive()
                    || !content.getAsJsonPrimitive().isString()) continue;

            String raw = content.getAsString();
            JsonElement parsed;
            try {
                parsed = JsonParser.parseString(raw);
            } catch (JsonParseException ignored) {
                parsed = new JsonPrimitive(raw);
            }
            TransformResult translated = translateJsonComponentTree(parsed);
            if (!translated.changed()) continue;
            event.add(contentName, translated.value());
            changed = true;
        }
        return changed;
    }

    /** Processes the click-like action object used by the dialog schema. */
    private static boolean translateDialogAction(JsonObject object) {
        if (!object.has("type")
                || !object.get("type").isJsonPrimitive()
                || !object.get("type").getAsJsonPrimitive().isString()
                || !object.has("command")
                || !object.get("command").isJsonPrimitive()
                || !object.get("command").getAsJsonPrimitive().isString()) {
            return false;
        }

        String type = object.get("type").getAsString();
        if (!"run_command".equals(type) && !"suggest_command".equals(type)) return false;

        String original = object.get("command").getAsString();
        String prefix = original.startsWith("/") ? "/" : "";
        String command = prefix.isEmpty() ? original : original.substring(prefix.length());
        String translated = FunctionHandler.processNestedCommand(command);
        if (command.equals(translated)) return false;
        object.addProperty("command", prefix + translated);
        return true;
    }

    /** Converts a JSON member name into a language-key segment. */
    private static String keySegment(String name) {
        if (name.isEmpty()) return "";
        // Namespaced schema names such as 'minecraft:gameplay/bed_rule' would otherwise produce keys
        // containing ':' and '/', which cannot be addressed by resource-pack language files.
        return ResourceIds.path(name).replace('/', '.');
    }

    private static List<String> splitPath(String path, char separator) {
        List<String> segments = new ArrayList<>();
        int start = 0;
        for (int index = 0; index <= path.length(); index++) {
            if (index != path.length() && path.charAt(index) != separator) continue;
            segments.add(path.substring(start, index));
            start = index + 1;
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

    private record TagPath(String[] parents, String name) {
        private static TagPath of(String path) {
            List<String> split = splitPath(path, '.');
            String[] names = split.toArray(String[]::new);
            String[] parents = new String[Math.max(0, names.length - 1)];
            System.arraycopy(names, 0, parents, 0, parents.length);
            return new TagPath(parents, names[names.length - 1]);
        }
    }
}
