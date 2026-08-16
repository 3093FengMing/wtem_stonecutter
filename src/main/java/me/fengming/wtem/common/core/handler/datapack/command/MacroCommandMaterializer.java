package me.fengming.wtem.common.core.handler.datapack.command;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import java.util.HashSet;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import me.fengming.wtem.common.core.extraction.TranslationContext;
import me.fengming.wtem.common.util.NbtUtils;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.TagParser;

/**
 * Materializes known caller bindings before ordinary command extraction and validates the restored
 * macro command against every known caller binding.
 *
 * @author FengMing
 */
final class MacroCommandMaterializer {
    private static final ThreadLocal<MacroCallGraph> MACRO_CALL_GRAPH = new ThreadLocal<>();
    private static final ThreadLocal<String> CURRENT_FUNCTION_ID = new ThreadLocal<>();
    private static final Set<String> TEXT_COMPONENTS = Set.of("text", "translate", "score", "selector", "nbt", "keybind", "object");

    private MacroCommandMaterializer() {}

    static String currentFunctionId() {
        return CURRENT_FUNCTION_ID.get();
    }

    static void setCurrentFunctionId(String functionId) {
        if (functionId == null || functionId.isBlank()) CURRENT_FUNCTION_ID.remove();
        else CURRENT_FUNCTION_ID.set(functionId);
    }

    static void restoreCurrentFunctionId(String functionId) {
        setCurrentFunctionId(functionId);
    }

    static void initializeMacroCallGraph(
            Map<String, String> functionSources, CommandParseSupport.ParserContext parser) {
        MACRO_CALL_GRAPH.set(
                MacroCallGraph.build(
                        functionSources == null ? Map.of() : functionSources,
                        line -> CommandParseSupport.parseFunctionInvocation(parser, line),
                        line -> CommandParseSupport.parseStorageAssignment(parser, line)));
    }

    static void releaseMacroCallGraph() {
        MACRO_CALL_GRAPH.remove();
        CURRENT_FUNCTION_ID.remove();
    }

    /**
     * Materializes one command per caller binding, parses it through the ordinary command path, then
     * writes the extracted structure back onto the original source ranges. Macros in text
     * components remain as {@code with} arguments; non-text macros are written as their concrete
     * caller values. Validation re-applies the same caller binding to prove that the generated line
     * remains executable.
     */
    static CommandExtraction extract(
            CommandParseSupport.ParserContext parser,
            MacroArgumentRestorer.CommandLine sourceLine) {
        List<Map<String, String>> bindings = callChainBindings(sourceLine);
        if (bindings.isEmpty()) return extractFallback(parser, sourceLine);

        // A component-valued storage macro must be emitted as a raw with argument.  Keeping it
        // inside {"text":"$(name)"} would make Minecraft substitute the SNBT object into a
        // quoted scalar and fail before the dialog/title command is parsed.  Require the value to
        // be a component for every statically known caller so one shared output remains valid for
        // the whole call graph.
        Set<String> structuredComponentMacros =
                structuredComponentMacros(bindings, macroNames(sourceLine.source()));

        String rendered = null;
        boolean unsafeBindingSeen = false;
        List<Map<String, String>> parsedBindings = new ArrayList<>();
        List<String> lineMacros = macroNames(sourceLine.source());
        for (Map<String, String> binding : bindings) {
            MacroArgumentRestorer.CommandLine materialized =
                    MacroArgumentRestorer.CommandLine.of(sourceLine.source(), binding);
            String unsafeMacro = materialized.unsafeResolvedMacro(structuredComponentMacros);
            if (unsafeMacro != null) {
                unsafeBindingSeen = true;
                CommandParseSupport.recordCommandWarning(
                        "function_macro_binding",
                        sourceLine.source(),
                        "Caller value for $(%s) is not safe in this command; the original command was kept"
                                .formatted(unsafeMacro));
                continue;
            }
            if (!CommandParseSupport.parsesExecutableCommand(parser, materialized.text())) {
                if (rendered == null) {
                    try (var bindingTransaction = TranslationContext.beginTransaction()) {
                        List<MacroArgumentRestorer.Replacement> replacements =
                                CommandParseSupport.findReplacements(parser, materialized);
                        replacements =
                                rewriteStructuredMacroArguments(
                                        replacements, structuredComponentMacros);
                        if (!replacements.isEmpty()
                                && CommandParseSupport.isSafeMacroComponentFallback(
                                materialized, replacements)) {
                            String candidate = materialized.render(replacements);
                            if (CommandParseSupport.isSafeGeneratedCommand(candidate)) {
                                rendered = candidate;
                                bindingTransaction.commit();
                            } else {
                                CommandParseSupport.recordCommandWarning(
                                        "function_component_rewrite",
                                        sourceLine.source(),
                                        "Generated component JSON could not be parsed; the original command was kept");
                            }
                        }
                    }
                }
                if (rendered != null) continue;
                if (binding.keySet().containsAll(lineMacros)) {
                    CommandParseSupport.recordCommandFailure(
                            "function_macro_binding",
                            materialized.text(),
                            new IllegalArgumentException(
                                    "A fully resolved caller binding does not produce an executable command"));
                }
                continue;
            }

            parsedBindings.add(binding);
            // The source occurrence is extracted once. Remaining caller bindings are still parsed
            // and later validate the restored output, but extracting the same literal repeatedly
            // would allocate different keys when the user intentionally disables key reuse.
            if (rendered != null) continue;
            try (var bindingTransaction = TranslationContext.beginTransaction()) {
                int recordsBefore = TranslationContext.recordCount();
                List<MacroArgumentRestorer.Replacement> replacements =
                        CommandParseSupport.findReplacements(parser, materialized);
                replacements =
                        rewriteStructuredMacroArguments(replacements, structuredComponentMacros);
                if (replacements.isEmpty()) {
                    if (TranslationContext.hasOnlyCatalogEntriesSince(recordsBefore)) {
                        bindingTransaction.commit();
                    }
                    continue;
                }

                String candidate = materialized.render(replacements);
                if (!CommandParseSupport.isSafeGeneratedCommand(candidate)) {
                    CommandParseSupport.recordCommandWarning(
                            "function_component_rewrite",
                            sourceLine.source(),
                            "Generated component JSON could not be parsed; the original command was kept");
                    continue;
                }
                if (!CommandParseSupport.parsesExecutableCommand(
                        parser,
                        MacroArgumentRestorer.CommandLine.of(candidate, binding).text())) {
                    CommandParseSupport.recordCommandFailure(
                            "function_macro_restore",
                            candidate,
                            new IllegalArgumentException(
                                    "Restoring macro placeholders made the translated command invalid for its caller binding"));
                    continue;
                }
                rendered = candidate;
                bindingTransaction.commit();
            }
        }

        // An unsafe caller cannot be represented by the rendered source, even when another caller
        // supplied a parseable value. Do not silently choose a partial translation for only the
        // safe callers: the original macro command is the only lossless output for this case.
        if (unsafeBindingSeen) return CommandExtraction.unchanged(sourceLine.source());

        if (rendered == null) {
            return parsedBindings.isEmpty()
                    ? extractFallback(parser, sourceLine)
                    : CommandExtraction.unchanged(sourceLine.source());
        }

        // A replacement derived from one caller must still be valid for every other caller whose
        // arguments Brigadier could resolve. This catches a serializer producing a malformed
        // component while resolving a non-text macro.
        for (Map<String, String> binding : parsedBindings) {
            MacroArgumentRestorer.CommandLine reboundLine =
                    MacroArgumentRestorer.CommandLine.of(rendered, binding);
            if (reboundLine.unsafeResolvedMacro(structuredComponentMacros) != null
                    || !CommandParseSupport.isSafeGeneratedCommand(reboundLine.text())) {
                return CommandExtraction.unchanged(sourceLine.source());
            }
            String rebound = reboundLine.text();
            if (!CommandParseSupport.isValidCommand(parser, rebound)) {
                return CommandExtraction.unchanged(sourceLine.source());
            }
        }
        return CommandExtraction.changed(rendered);
    }

    private static Set<String> structuredComponentMacros(
            List<Map<String, String>> bindings, List<String> names) {
        Set<String> result = new HashSet<>(names);
        for (String name : names) {
            for (Map<String, String> binding : bindings) {
                String value = binding.get(name);
                if (!looksLikeComponentValue(value)) {
                    result.remove(name);
                    break;
                }
            }
        }
        return Set.copyOf(result);
    }

    /** Recognizes the component discriminators in the SNBT spelling stored by the call graph. */
    private static boolean looksLikeComponentValue(String value) {
        if (value == null || value.isBlank()) return false;
        try {
            Tag parsed = parseTag(value);
            return isComponentTag(parsed);
        } catch (Exception ignored) {
            return false;
        }
    }

    private static boolean isComponentTag(Tag tag) {
        if (tag instanceof CompoundTag compound) {
            return NbtUtils.getKeys(compound).stream()
                    .anyMatch(TEXT_COMPONENTS::contains);
        }
        if (tag instanceof ListTag list && !list.isEmpty()) {
            for (Tag child : list) {
                if (!isComponentTag(child)) return false;
            }
            return true;
        }
        return false;
    }

    private static Tag parseTag(String value) throws Exception {
        //? if >=1.21.5 {
        return TagParser.create(NbtOps.INSTANCE).parseFully(value);
        //?} else {
        /*return TagParser.parseTag(value);
        *///?}
    }

    private static List<MacroArgumentRestorer.Replacement> rewriteStructuredMacroArguments(
            List<MacroArgumentRestorer.Replacement> replacements,
            Set<String> structuredComponentMacros) {
        if (replacements.isEmpty() || structuredComponentMacros.isEmpty()) return replacements;
        List<MacroArgumentRestorer.Replacement> result = new ArrayList<>(replacements.size());
        for (MacroArgumentRestorer.Replacement replacement : replacements) {
            String value = replacement.value();
            try {
                JsonElement json = JsonParser.parseString(value);
                Map<String, String> sentinels = new LinkedHashMap<>();
                JsonElement rewritten =
                        replaceComponentMacroArguments(json, structuredComponentMacros, sentinels);
                if (sentinels.isEmpty()) {
                    result.add(replacement);
                    continue;
                }
                String serialized = rewritten.toString();
                for (Map.Entry<String, String> entry : sentinels.entrySet()) {
                    serialized =
                            serialized.replace(
                                    "\"" + entry.getKey() + "\"", "$(" + entry.getValue() + ")");
                }
                result.add(
                        new MacroArgumentRestorer.Replacement(
                                replacement.start(), replacement.end(), serialized));
            } catch (RuntimeException ignored) {
                // Non-JSON replacements (SNBT arguments handled by another visitor) are left to
                // the ordinary restoration path.
                result.add(replacement);
            }
        }
        return List.copyOf(result);
    }

    private static JsonElement replaceComponentMacroArguments(
            JsonElement element,
            Set<String> structuredComponentMacros,
            Map<String, String> sentinels) {
        if (element == null || element.isJsonNull()) return element;
        if (element.isJsonArray()) {
            var result = new com.google.gson.JsonArray();
            element.getAsJsonArray()
                    .forEach(
                            child ->
                                    result.add(
                                            replaceComponentMacroArguments(
                                                    child, structuredComponentMacros, sentinels)));
            return result;
        }
        if (!element.isJsonObject()) return element.deepCopy();

        JsonObject object = element.getAsJsonObject();
        if (object.size() == 1
                && object.has("text")
                && object.get("text").isJsonPrimitive()
                && object.get("text").getAsJsonPrimitive().isString()) {
            String text = object.get("text").getAsString();
            if (text.startsWith("$(") && text.endsWith(")")) {
                String name = text.substring(2, text.length() - 1);
                if (structuredComponentMacros.contains(name)) {
                    String sentinel = "__wtem_component_macro_" + sentinels.size() + "__";
                    sentinels.put(sentinel, name);
                    return new JsonPrimitive(sentinel);
                }
            }
        }

        JsonObject result = new JsonObject();
        object.entrySet()
                .forEach(
                        entry ->
                                result.add(
                                        entry.getKey(),
                                        replaceComponentMacroArguments(
                                                entry.getValue(),
                                                structuredComponentMacros,
                                                sentinels)));
        return result;
    }

    private static List<Map<String, String>> callChainBindings(
            MacroArgumentRestorer.CommandLine line) {
        MacroCallGraph graph = MACRO_CALL_GRAPH.get();
        String function = CURRENT_FUNCTION_ID.get();
        if (graph == null || function == null) return List.of();
        List<String> used = macroNames(line.source());
        if (used.isEmpty()) return List.of();

        List<Map<String, String>> result = new ArrayList<>();
        for (Map<String, String> binding : graph.bindings(function)) {
            Map<String, String> relevant = new LinkedHashMap<>();
            for (String name : used) {
                String value = binding.get(name);
                if (value != null) relevant.put(name, value);
            }
            if (!result.contains(relevant)) result.add(Map.copyOf(relevant));
        }
        return List.copyOf(result);
    }

    private static CommandExtraction extractFallback(
            CommandParseSupport.ParserContext parser,
            MacroArgumentRestorer.CommandLine line) {
        List<MacroArgumentRestorer.Replacement> replacements =
                CommandParseSupport.findReplacements(parser, line);
        if (replacements.isEmpty()) return CommandExtraction.unchanged(line.source());
        if (CommandParseSupport.isSafeMacroComponentFallback(line, replacements)) {
            String candidate = line.render(replacements);
            if (!CommandParseSupport.isSafeGeneratedCommand(candidate)) {
                CommandParseSupport.recordCommandWarning(
                        "function_component_rewrite",
                        line.source(),
                        "Generated component JSON could not be parsed; the original command was kept");
                return CommandExtraction.unchanged(line.source());
            }
            return CommandExtraction.changed(candidate);
        }
        if (!CommandParseSupport.isValidCommand(
                parser, CommandParseSupport.applyReplacements(line.text(), replacements))) {
            return CommandExtraction.unchanged(line.source());
        }
        String candidate = line.render(replacements);
        if (!CommandParseSupport.isSafeGeneratedCommand(candidate)) {
            CommandParseSupport.recordCommandWarning(
                    "function_component_rewrite",
                    line.source(),
                    "Generated component JSON could not be parsed; the original command was kept");
            return CommandExtraction.unchanged(line.source());
        }
        return CommandExtraction.changed(candidate);
    }

    static List<String> macroNames(String source) {
        List<String> names = new ArrayList<>();
        int cursor = 0;
        while ((cursor = source.indexOf("$(", cursor)) >= 0) {
            int end = source.indexOf(')', cursor + 2);
            if (end < 0) break;
            String name = source.substring(cursor + 2, end);
            if (!name.isBlank() && !names.contains(name)) names.add(name);
            cursor = end + 1;
        }
        return names;
    }

}
