package me.fengming.wtem.common.core.handler.datapack.command;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import me.fengming.wtem.common.core.extraction.TranslationContext;
import net.minecraft.network.chat.Component;

/**
 * Materializes known caller bindings before ordinary command extraction and validates the restored
 * macro command against every known caller binding.
 *
 * @author FengMing
 */
final class MacroCommandMaterializer {
    private static final ThreadLocal<MacroCallGraph> MACRO_CALL_GRAPH = new ThreadLocal<>();
    private static final ThreadLocal<String> CURRENT_FUNCTION_ID = new ThreadLocal<>();

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
     * writes the extracted structure back onto the original source ranges. The written line therefore
     * keeps every {@code $(name)}, while validation re-applies the same caller binding to prove that
     * the restored command remains executable.
     */
    static CommandExtraction extract(
            CommandParseSupport.ParserContext parser,
            MacroArgumentRestorer.CommandLine sourceLine) {
        List<Map<String, String>> bindings = callChainBindings(sourceLine);
        if (bindings.isEmpty()) return extractFallback(parser, sourceLine);

        String rendered = null;
        List<Map<String, String>> parsedBindings = new ArrayList<>();
        List<String> lineMacros = macroNames(sourceLine.source());
        for (Map<String, String> binding : bindings) {
            MacroArgumentRestorer.CommandLine materialized =
                    MacroArgumentRestorer.CommandLine.of(sourceLine.source(), binding);
            if (!CommandParseSupport.parsesExecutableCommand(parser, materialized.text())) {
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
                if (replacements.isEmpty()) {
                    if (TranslationContext.hasOnlyCatalogEntriesSince(recordsBefore)) {
                        bindingTransaction.commit();
                    }
                    continue;
                }

                String candidate = materialized.render(replacements);
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

        if (rendered == null) {
            return parsedBindings.isEmpty()
                    ? extractFallback(parser, sourceLine)
                    : CommandExtraction.unchanged(sourceLine.source());
        }

        // A replacement derived from one caller must still be valid for every other caller whose
        // arguments Brigadier could resolve. This catches a serializer accidentally baking a
        // concrete macro value into the output instead of restoring $(name).
        for (Map<String, String> binding : parsedBindings) {
            String rebound =
                    MacroArgumentRestorer.CommandLine.of(rendered, binding).text();
            if (!CommandParseSupport.isValidCommand(parser, rebound)) {
                return CommandExtraction.unchanged(sourceLine.source());
            }
        }
        return CommandExtraction.changed(rendered);
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

    /** Retains the conservative JSON fallback for functions whose runtime arguments are unknown. */
    private static CommandExtraction extractFallback(
            CommandParseSupport.ParserContext parser,
            MacroArgumentRestorer.CommandLine line) {
        List<MacroArgumentRestorer.Replacement> replacements =
                CommandParseSupport.findReplacements(parser, line);
        if (replacements.isEmpty()) return CommandExtraction.unchanged(line.source());
        boolean fallbackJson = replacements.stream().anyMatch(MacroArgumentRestorer.Replacement::fallbackJson);
        if (!fallbackJson
                && !CommandParseSupport.isValidCommand(
                        parser, CommandParseSupport.applyReplacements(line.text(), replacements))) {
            return CommandExtraction.unchanged(line.source());
        }
        return CommandExtraction.changed(line.render(replacements));
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

    /**
     * Transforms a component argument that was parsed through macro stand-ins. Brigadier only gives
     * us the stand-in component (for example {@code "1"} where {@code $(reqwait)} was), so
     * translating that value would either lose the macro or create a catalogue entry for the
     * meaningless number. Re-read the source JSON and translate literal siblings in place instead.
     */
    static String translateMaskedComponent(String sourceArgument, Component parsed) {
        String trimmed = sourceArgument == null ? "" : sourceArgument.trim();
        if (trimmed.isEmpty()) return null;

        char wrapper = 0;
        String jsonSource = trimmed;
        if (trimmed.length() >= 2
                && ((trimmed.charAt(0) == '\'' && trimmed.charAt(trimmed.length() - 1) == '\'')
                        || (trimmed.charAt(0) == '"'
                                && trimmed.charAt(trimmed.length() - 1) == '"'))) {
            wrapper = trimmed.charAt(0);
            jsonSource = trimmed.substring(1, trimmed.length() - 1);
            if (wrapper == '"') {
                try {
                    // A double-quoted component is a JSON string containing JSON. Decode its
                    // escapes before handing the inner value to Gson.
                    jsonSource = JsonParser.parseString(trimmed).getAsString();
                } catch (RuntimeException ignored) {
                    return null;
                }
            }
        }

        JsonElement json;
        try {
            json = JsonParser.parseString(jsonSource);
        } catch (RuntimeException ignored) {
            return null;
        }

        MacroTransform transformed = transformMacroComponent(json, sourceArgument);
        if (!transformed.changed()) return null;

        String serialized = transformed.value().toString();
        if (wrapper == '\'') return "'" + serialized + "'";
        if (wrapper == '"') return new JsonPrimitive(serialized).toString();
        return serialized;
    }

    private static MacroTransform transformMacroComponent(
            JsonElement value, String warningResource) {
        if (value == null || value.isJsonNull()) return MacroTransform.unchanged(value);

        if (value.isJsonPrimitive()) {
            JsonPrimitive primitive = value.getAsJsonPrimitive();
            if (!primitive.isString()) return MacroTransform.unchanged(value);
            String literal = primitive.getAsString();
            String macro = exactMacro(literal);
            if (macro != null) {
                recordMacroWarning(macro, warningResource);
                JsonObject translated = new JsonObject();
                translated.addProperty("translate", literal);
                return MacroTransform.changed(translated);
            }
            if (literal.isBlank() || literal.contains("$(")) {
                return MacroTransform.unchanged(value);
            }
            JsonObject translated = new JsonObject();
            translated.addProperty("translate", TranslationContext.addEntry(literal));
            return MacroTransform.changed(translated);
        }

        if (value.isJsonArray()) {
            JsonArray array = value.getAsJsonArray();
            boolean changed = false;
            for (int i = 0; i < array.size(); i++) {
                MacroTransform child = transformMacroComponent(array.get(i), warningResource);
                if (!child.changed()) continue;
                array.set(i, child.value());
                changed = true;
            }
            return new MacroTransform(array, changed);
        }

        JsonObject object = value.getAsJsonObject();
        boolean changed = false;
        JsonElement text = object.get("text");
        if (text != null && text.isJsonPrimitive() && text.getAsJsonPrimitive().isString()) {
            String literal = text.getAsString();
            String macro = exactMacro(literal);
            if (macro != null) {
                // A runtime macro used as text is a translation key, not a literal component. It
                // must remain $(name) so Minecraft's macro expansion can provide the key later.
                object.remove("text");
                object.addProperty("translate", literal);
                recordMacroWarning(macro, warningResource);
                changed = true;
            } else if (!literal.isBlank() && !literal.contains("$(")) {
                object.remove("text");
                object.addProperty("translate", TranslationContext.addEntry(literal));
                changed = true;
            }
        }

        // Components can nest through contents, extra, hover/click events, and the dialog body
        // schema. Traversing every object value is intentional: fields unknown to this version's
        // codec should still retain translatable child components instead of being skipped.
        for (Map.Entry<String, JsonElement> entry : new ArrayList<>(object.entrySet())) {
            if ("text".equals(entry.getKey()) || "translate".equals(entry.getKey())) continue;
            // A primitive child such as color:"$(event_color)" is a property value, not a nested
            // component. Treating every primitive as a component would turn it into
            // {translate:"..."} and change the style semantics.
            if (!entry.getValue().isJsonObject() && !entry.getValue().isJsonArray()) continue;
            MacroTransform child = transformMacroComponent(entry.getValue(), warningResource);
            if (!child.changed()) continue;
            object.add(entry.getKey(), child.value());
            changed = true;
        }
        return new MacroTransform(object, changed);
    }

    private static String exactMacro(String value) {
        if (value == null || !value.startsWith("$(") || !value.endsWith(")")) return null;
        if (value.length() <= 3) return null;
        String name = value.substring(2, value.length() - 1);
        if (name.indexOf('(') >= 0 || name.indexOf(')') >= 0 || name.isBlank()) return null;
        return name;
    }

    private static void recordMacroWarning(String name, String resource) {
        recordMacroTemplateWarning("$(" + name + ")", resource);
    }

    static void recordMacroTemplateWarning(String template, String resource) {
        MacroCallGraph graph = MACRO_CALL_GRAPH.get();
        String function = CURRENT_FUNCTION_ID.get();
        List<String> names = macroNames(template);
        Set<String> known = new LinkedHashSet<>();
        if (graph != null && function != null) {
            for (Map<String, String> binding : graph.bindings(function)) {
                if (!binding.keySet().containsAll(names)) continue;
                String value = template;
                for (String name : names) {
                    value = value.replace("$(" + name + ")", binding.get(name));
                }
                if (value.contains("$(") || value.isBlank()) continue;
                if (TranslationContext.addCatalogEntry(value, value)) {
                    known.add(value);
                } else {
                    CommandParseSupport.recordCommandFailure(
                            "function_macro_component",
                            value,
                            new IllegalStateException(
                                    "A dynamic translation key conflicts with an existing catalog entry"));
                }
            }

            // Older or deliberately partial call indexes may know independent values without a
            // complete binding. They are still useful for the exact `translate:$(name)` case.
            if (known.isEmpty() && names.size() == 1 && template.equals("$(" + names.getFirst() + ")")) {
                for (String value : graph.values(function, names.getFirst())) {
                    if (TranslationContext.addCatalogEntry(value, value)) known.add(value);
                }
            }
        }
        String suffix =
                known.isEmpty()
                        ? " No statically known call-chain value was found."
                        : " Runtime translation keys were catalogued with source values: " + known;
        CommandParseSupport.recordCommandFailure(
                "function_macro_component",
                resource,
                new IllegalArgumentException(
                        "Macro text template " + template + " was emitted as a translate key."
                                + suffix));
    }

    private record MacroTransform(JsonElement value, boolean changed) {
        private static MacroTransform unchanged(JsonElement value) {
            return new MacroTransform(value, false);
        }

        private static MacroTransform changed(JsonElement value) {
            return new MacroTransform(value, true);
        }
    }
}
