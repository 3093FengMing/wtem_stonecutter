package me.fengming.wtem.common.core.handler.datapack.command;

import com.google.gson.JsonElement;
import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.context.CommandContextBuilder;
import com.mojang.brigadier.context.ParsedArgument;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import me.fengming.wtem.common.core.extraction.service.ExtractionDiagnostics;
import me.fengming.wtem.common.core.extraction.TranslationContext;
import me.fengming.wtem.common.core.visitor.EntityTagVisitor;
import me.fengming.wtem.common.util.NbtUtils;
import me.fengming.wtem.common.util.TranslationUtils;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.blocks.BlockInput;
import net.minecraft.commands.arguments.item.ItemInput;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistryAccess;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.TagParser;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
//? if >=1.21.6 {
import com.mojang.serialization.JsonOps;
import net.minecraft.server.dialog.Dialog;
//?}
//? if >=1.21.11
import net.minecraft.server.permissions.PermissionSet;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;

/** Shared Brigadier parsing, argument extraction, and command validation support.
 *
 * @author FengMing
 */
final class CommandParseSupport {
    private static final int MAX_COMMAND_LENGTH = 2_000_000;
    private static final ThreadLocal<ParserContext> COMMAND_PARSER = new ThreadLocal<>();
    private static final ThreadLocal<ExtractionDiagnostics> COMMAND_DIAGNOSTICS = new ThreadLocal<>();
    private static final Set<String> TEXT_COMPONENTS = Set.of("text", "translate", "score", "selector", "nbt", "keybind", "object");

    private CommandParseSupport() {}

    static void initializeParser(RegistryAccess registries, ExtractionDiagnostics diagnostics) {
        initializeParser((HolderLookup.Provider) registries, diagnostics);
    }

    static void initializeParser(
            HolderLookup.Provider registries, ExtractionDiagnostics diagnostics) {
        COMMAND_PARSER.set(createParserContext(registries));
        COMMAND_DIAGNOSTICS.set(diagnostics);
    }

    static void releaseParser() {
        COMMAND_PARSER.remove();
        COMMAND_DIAGNOSTICS.remove();
    }

    static ParserContext parserContext() {
        ParserContext parser = COMMAND_PARSER.get();
        if (parser != null) return parser;
        parser = createParserContext(VanillaRegistries.createLookup());
        COMMAND_PARSER.set(parser);
        return parser;
    }

    static CommandExtraction extractRegularCommand(
            ParserContext parser, MacroArgumentRestorer.CommandLine line) {
        List<MacroArgumentRestorer.Replacement> replacements = findReplacements(parser, line);
        if (replacements.isEmpty()) return CommandExtraction.unchanged(line.source());
        String candidate = line.render(replacements);
        if (!isSafeGeneratedCommand(candidate)) {
            recordCommandWarning(
                    "function_component_rewrite",
                    candidate,
                    "Generated component JSON could not be parsed; the original command was kept");
            return CommandExtraction.unchanged(line.source());
        }
        if (!isValidCommand(parser, applyReplacements(line.text(), replacements))) {
            return CommandExtraction.unchanged(line.source());
        }
        return CommandExtraction.changed(candidate);
    }

    /**
     * Uses the same command dispatcher as extraction to identify a function call. The returned
     * storage tokens are read from parsed argument boundaries only after Brigadier has accepted the
     * command; this keeps the call graph from treating arbitrary text as a valid invocation.
     */
    static MacroCallGraph.ParsedInvocation parseFunctionInvocation(
            ParserContext parser, String sourceLine) {
        // A dispatcher parse of a masked interpolation would describe the stand-in (for example
        // `1`) rather than the runtime function/storage value. Do not turn that placeholder into a
        // false caller edge; only concrete call arguments belong in the static graph.
        if (sourceLine.contains("$(")) return null;
        MacroCallGraph.ParsedInvocation nested =
                parseNestedFunctionInvocation(parser, sourceLine);
        if (nested != null) return nested;
        MacroArgumentRestorer.CommandLine line =
                MacroArgumentRestorer.CommandLine.of(sourceLine.trim());
        String command = line.text();
        if (command.isBlank()) return null;
        ParseResults<CommandSourceStack> results;
        try {
            results = parser.commands().getDispatcher().parse(command, parser.source());
        } catch (RuntimeException ignored) {
            return null;
        }
        if (results.getReader().canRead()) return null;

        ParsedArgument<CommandSourceStack, ?> name = null;
        boolean functionNode = false;
        CommandContextBuilder<CommandSourceStack> context = results.getContext();
        CommandContextBuilder<CommandSourceStack> rootContext = context;
        while (context != null) {
            functionNode |=
                    context.getNodes().stream()
                            .anyMatch(node -> "function".equals(node.getNode().getName()));
            ParsedArgument<CommandSourceStack, ?> candidate = context.getArguments().get("name");
            if (candidate != null) name = candidate;
            context = context.getChild();
        }
        if (!functionNode || name == null) return null;

        String target = CommandArgumentSupport.argumentText(command, name);
        ParsedArgument<CommandSourceStack, ?> storageArgument =
                CommandArgumentSupport.findArgument(rootContext, "source", "storage", "storageId");
        ParsedArgument<CommandSourceStack, ?> pathArgument =
                CommandArgumentSupport.findArgument(rootContext, "path");
        if (storageArgument == null) {
            return new MacroCallGraph.ParsedInvocation(target, null, null);
        }

        return new MacroCallGraph.ParsedInvocation(
                target,
                CommandArgumentSupport.argumentText(command, storageArgument),
                pathArgument == null
                        ? null
                        : CommandArgumentSupport.argumentText(command, pathArgument));
    }

    static MacroCallGraph.ParsedInvocation parseFunctionInvocation(String sourceLine) {
        return parseFunctionInvocation(parserContext(), sourceLine);
    }

    /**
     * Parses a static {@code data modify/merge storage ...} command with the same Brigadier
     * dispatcher used for ordinary extraction. The value argument is already an NBT {@link Tag},
     * so no regular expression or ad-hoc SNBT parser is involved in propagating call-chain values.
     */
    static MacroCallGraph.StorageAssignment parseStorageAssignment(
            ParserContext parser, String sourceLine) {
        // The value written by a macro command is runtime data, not a compile-time literal.
        if (sourceLine.contains("$(")) return null;
        MacroCallGraph.StorageAssignment nested =
                parseNestedStorageAssignment(parser, sourceLine);
        if (nested != null) return nested;
        MacroArgumentRestorer.CommandLine line =
                MacroArgumentRestorer.CommandLine.of(sourceLine.trim());
        String command = line.text();
        if (command.isBlank()) return null;

        ParseResults<CommandSourceStack> results;
        try {
            results = parser.commands().getDispatcher().parse(command, parser.source());
        } catch (RuntimeException ignored) {
            return null;
        }
        if (results.getReader().canRead()) return null;

        CommandContextBuilder<CommandSourceStack> context = results.getContext();
        boolean modify = CommandArgumentSupport.containsCommandNode(context, "modify");
        boolean merge = CommandArgumentSupport.containsCommandNode(context, "merge");
        if (!CommandArgumentSupport.containsCommandNode(context, "data")
                || (!modify && !merge)) return null;
        // Only `data modify ... set value` replaces a path with this exact literal. Treating
        // append/prepend/insert/merge as an assignment would feed a value into a caller that never
        // actually receives that shape. `data merge storage ...` is the separate root merge form.
        if (modify
                && (!CommandArgumentSupport.containsCommandNode(context, "set")
                        || !CommandArgumentSupport.containsCommandNode(context, "value"))) {
            return null;
        }

        ParsedArgument<CommandSourceStack, ?> target =
                CommandArgumentSupport.findArgument(context, "target");
        ParsedArgument<CommandSourceStack, ?> path =
                CommandArgumentSupport.findArgument(context, "path", "targetPath");
        ParsedArgument<CommandSourceStack, ?> value =
                CommandArgumentSupport.findArgument(context, "value", "nbt", "tag");
        if (target == null || value == null) return null;

        String storageId = CommandArgumentSupport.storageId(command, target);
        if (storageId == null || storageId.isBlank()) return null;

        Object parsedValue = value.getResult();
        if (!(parsedValue instanceof Tag tag)) return null;
        Map<String, String> values = CommandArgumentSupport.storageValues(tag);
        if (values.isEmpty()) return null;

        return new MacroCallGraph.StorageAssignment(
                storageId,
                path == null ? "" : CommandArgumentSupport.argumentText(command, path),
                Map.copyOf(values));
    }

    static MacroCallGraph.StorageAssignment parseStorageAssignment(String sourceLine) {
        return parseStorageAssignment(parserContext(), sourceLine);
    }

    /** Records a warning for a literal string written directly into command storage. */
    private static void recordBareStorageStringWarning(
            String command, CommandContextBuilder<CommandSourceStack> context) {
        if (!CommandArgumentSupport.containsCommandNode(context, "data")
                || !CommandArgumentSupport.containsCommandNode(context, "modify")
                || !CommandArgumentSupport.containsCommandNode(context, "storage")
                || !CommandArgumentSupport.containsCommandNode(context, "set")
                || !CommandArgumentSupport.containsCommandNode(context, "value")) {
            return;
        }
        ParsedArgument<CommandSourceStack, ?> value =
                CommandArgumentSupport.findArgument(context, "value", "nbt", "tag");
        if (value == null || !(value.getResult() instanceof StringTag)) return;
        recordCommandWarning(
                "function_storage_string",
                command,
                "A bare string written directly to data storage may be user-facing text and needs manual review");
    }

    /**
     * The execute condition grammar is context-sensitive to the command source.  A static
     * extraction source has no selected entity, so some otherwise valid score predicates stop the
     * top-level Brigadier parse before it reaches {@code run}.  The command after {@code run} is
     * still an ordinary command and can be parsed independently; the caller graph only needs that
     * command's arguments, while the condition prefix is retained separately by MacroCallGraph.
     */
    private static MacroCallGraph.ParsedInvocation parseNestedFunctionInvocation(
            ParserContext parser, String sourceLine) {
        String nested = nestedCommand(sourceLine);
        return nested == null ? null : parseFunctionInvocation(parser, nested);
    }

    private static MacroCallGraph.StorageAssignment parseNestedStorageAssignment(
            ParserContext parser, String sourceLine) {
        String nested = nestedCommand(sourceLine);
        return nested == null ? null : parseStorageAssignment(parser, nested);
    }

    private static String nestedCommand(String sourceLine) {
        String command = sourceLine == null ? "" : sourceLine.trim();
        if (!command.startsWith("execute ")) return null;
        int run = executeRunIndex(command);
        if (run < 0) return null;
        int start = run + " run ".length();
        while (start < command.length() && Character.isWhitespace(command.charAt(start))) start++;
        return start >= command.length() ? null : command.substring(start);
    }

    private static int executeRunIndex(String command) {
        char quote = 0;
        boolean escaped = false;
        int squareDepth = 0;
        int compoundDepth = 0;
        for (int i = 0; i < command.length() - 4; i++) {
            char character = command.charAt(i);
            if (quote != 0) {
                if (escaped) escaped = false;
                else if (character == '\\') escaped = true;
                else if (character == quote) quote = 0;
                continue;
            }
            if (character == '\'' || character == '"') {
                quote = character;
                continue;
            }
            if (character == '[') squareDepth++;
            else if (character == ']') squareDepth = Math.max(0, squareDepth - 1);
            else if (character == '{') compoundDepth++;
            else if (character == '}') compoundDepth = Math.max(0, compoundDepth - 1);
            if (squareDepth == 0
                    && compoundDepth == 0
                    && command.startsWith(" run ", i)) return i;
        }
        return -1;
    }

    static List<MacroArgumentRestorer.Replacement> findReplacements(
            ParserContext parser, MacroArgumentRestorer.CommandLine line) {
        String command = line.text();
        try {
            recordSelectorNameWarnings(command);
            ParseResults<CommandSourceStack> results =
                    parser.commands().getDispatcher().parse(command, parser.source());
            ParsedCommand parsedCommand = new ParsedCommand(command, results, null);
            if (results.getReader().canRead()) {
                ParsedCommand normalized = normalizeLegacyNbtCommand(parser, command);
                if (normalized != null) {
                    parsedCommand = normalized;
                    results = normalized.results();
                } else {
                    ParsedCommand nested = parseNestedCommand(parser, command);
                    if (nested != null) {
                        parsedCommand = nested;
                        results = nested.results();
                    } else {
                        MacroArgumentRestorer.Replacement componentFallback =
                                findMacroArgumentReplacement(line);
                        if (componentFallback != null) return List.of(componentFallback);

                        // A masked interpolation is only a plausible stand-in, not necessarily a valid
                        // one, so a macro line that fails to parse is expected rather than a defect to
                        // report.
                        if (!line.macro()) {
                            recordCommandFailure(
                                    "function_parse",
                                    command,
                                    new IllegalArgumentException(
                                            "Command parser stopped at character "
                                                    + results.getReader().getCursor()));
                            return List.of();
                        }
                    }
                }
            }
            if (!line.macro()) recordBareStorageStringWarning(line.source(), results.getContext());
            Map<String, MacroArgumentRestorer.Replacement> replacements = new LinkedHashMap<>();

            CommandContextBuilder<CommandSourceStack> context = results.getContext();
            while (context != null) {
                for (Map.Entry<String, ParsedArgument<CommandSourceStack, ?>> entry :
                        context.getArguments().entrySet()) {
                    ParsedArgument<CommandSourceStack, ?> argument = entry.getValue();
                    int argumentStart =
                            parsedCommand.sourceOffset(argument.getRange().getStart());
                    int argumentEnd = parsedCommand.sourceOffset(argument.getRange().getEnd());
                    String rangeKey =
                            argumentStart
                                    + ":"
                                    + argumentEnd;
                    if (replacements.containsKey(rangeKey)) continue;

                    // A masked value is still parsed by the ordinary Brigadier argument codec.
                    // It may be an unresolved runtime value, but structured arguments such as NBT
                    // lists can contain static component siblings that are safe to extract. The
                    // source-to-parser ranges in CommandLine restore every mask afterwards.

                    String sourceArgument =
                            line.translationArgument(argumentStart, argumentEnd);

                    try (var transaction = TranslationContext.beginTransaction()) {
                        int recordsBefore = TranslationContext.recordCount();
                        Set<String> catalogKeysBefore = TranslationContext.snapshot().keySet();
                        MacroArgumentRestorer.Replacement replacement =
                                        createReplacement(
                                            parsedCommand.text(),
                                            context,
                                            entry.getKey(),
                                            argument,
                                            argumentStart,
                                            argumentEnd,
                                            sourceArgument,
                                            line.materializedArgument(argumentStart, argumentEnd),
                                            parser.registries());
                        if (replacement == null) {
                            replacement =
                                    CommandPatternSupport.createReplacement(
                                            context,
                                            entry.getKey(),
                                            argument,
                                            argumentStart,
                                            argumentEnd,
                                            sourceArgument);
                        }
                        if (replacement == null) {
                            if (TranslationContext.hasOnlyCatalogEntriesSince(recordsBefore)) {
                                transaction.commit();
                            }
                            continue;
                        }
                        replacement =
                                line.restoreMacros(
                                        replacement, catalogKeysBefore, recordsBefore);
                        if (replacement == null) continue;
                        transaction.commit();
                        replacements.put(rangeKey, replacement);
                    }
                }
                context = context.getChild();
            }

            List<MacroArgumentRestorer.Replacement> ordered =
                    new ArrayList<>(replacements.values());
            ordered.sort(Comparator.comparingInt(MacroArgumentRestorer.Replacement::start).reversed());
            return List.copyOf(ordered);
        } catch (RuntimeException exception) {
            if (!line.macro()) {
                recordCommandFailure("function_command", command, exception);
                return List.of();
            }
            // A macro parse failure is expected when no caller binding makes the command concrete.
            // Do not inspect the command as ad-hoc JSON: the caller graph/materializer is the only
            // supported way to make a macro command ordinary and parseable.
            return List.of();
        }
    }

    static String applyReplacements(
            String command, List<MacroArgumentRestorer.Replacement> replacements) {
        StringBuilder updated = new StringBuilder(command);
        for (MacroArgumentRestorer.Replacement replacement : replacements) {
            // Replacement values for a masked component intentionally retain $(name). Validation
            // must put legal stand-ins back before Brigadier sees them; otherwise a dynamic color
            // such as $(event_color) would fail validation even though its source command is valid
            // at runtime.
            updated.replace(
                    replacement.start(),
                    replacement.end(),
                    maskMacrosForValidation(replacement.value()));
        }
        return updated.toString();
    }

    private static String maskMacrosForValidation(String value) {
        if (value == null || !value.contains("$(")) return value;
        // Do not use a regular expression here.  A macro can be either a quoted scalar (where a
        // plain `1` is the legal stand-in) or a raw text-component element in `with` (where the
        // stand-in must itself be a component object).  The small lexer below preserves escaped
        // quotes and only recognizes a closing parenthesis belonging to the current marker.
        StringBuilder masked = new StringBuilder(value.length());
        char quote = 0;
        boolean escaped = false;
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (quote != 0) {
                if (character == '$'
                        && index + 2 < value.length()
                        && value.charAt(index + 1) == '(') {
                    int close = value.indexOf(')', index + 2);
                    if (close >= 0) {
                        masked.append('1');
                        index = close;
                        continue;
                    }
                }
                masked.append(character);
                if (escaped) escaped = false;
                else if (character == '\\') escaped = true;
                else if (character == quote) quote = 0;
                continue;
            }
            if (character == '\'' || character == '"') {
                quote = character;
                masked.append(character);
                continue;
            }
            if (character != '$'
                    || index + 2 >= value.length()
                    || value.charAt(index + 1) != '(') {
                masked.append(character);
                continue;
            }
            int close = value.indexOf(')', index + 2);
            if (close < 0) {
                masked.append(character);
                continue;
            }
            int previous = index - 1;
            while (previous >= 0 && Character.isWhitespace(value.charAt(previous))) previous--;
            int next = close + 1;
            while (next < value.length() && Character.isWhitespace(value.charAt(next))) next++;
            boolean componentElement =
                    previous >= 0
                            && (value.charAt(previous) == '['
                                    || value.charAt(previous) == ','
                                    || value.charAt(previous) == ':')
                            && next < value.length()
                            && (value.charAt(next) == ']'
                                    || value.charAt(next) == ','
                                    || value.charAt(next) == '}');
            if (componentElement) masked.append("{\"text\":\"1\"}");
            else masked.append('1');
            index = close;
        }
        return masked.toString();
    }

    static boolean parsesExecutableCommand(ParserContext parser, String command) {
        if (command.length() > MAX_COMMAND_LENGTH) return false;
        try {
            ParseResults<CommandSourceStack> results =
                    parser.commands().getDispatcher().parse(command, parser.source());
            CommandContextBuilder<CommandSourceStack> context = results.getContext();
            while (context.getChild() != null) context = context.getChild();
            return !results.getReader().canRead() && context.getCommand() != null;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    static boolean isValidCommand(ParserContext parser, String command) {
        if (command.length() > MAX_COMMAND_LENGTH) {
            recordCommandFailure(
                    "function_reparse",
                    command.substring(0, 512),
                    new IllegalStateException(
                            "Generated command is too long: " + command.length() + " characters"));
            return false;
        }

        try {
            ParseResults<CommandSourceStack> results =
                    parser.commands().getDispatcher().parse(command, parser.source());
            CommandContextBuilder<CommandSourceStack> context = results.getContext();
            while (context.getChild() != null) context = context.getChild();

            if (!results.getReader().canRead() && context.getCommand() != null) return true;
            if (isValidNestedExecute(parser, command)) return true;

            String reason =
                    results.getReader().canRead()
                            ? "Generated command parser stopped at character "
                                    + results.getReader().getCursor()
                            : "Generated command does not resolve to an executable command";
            recordCommandFailure("function_reparse", command, new IllegalArgumentException(reason));
        } catch (RuntimeException exception) {
            recordCommandFailure("function_reparse", command, exception);
        }
        return false;
    }

    /** Validates the command after execute/run when the static source cannot satisfy its prefix. */
    private static boolean isValidNestedExecute(ParserContext parser, String command) {
        return parseNestedCommand(parser, command) != null;
    }

    /**
     * Parses the command after an {@code execute ... run} prefix when the static command source
     * cannot satisfy the prefix's entity-dependent conditions. Brigadier still validates the
     * nested command, and the offset table lets its arguments be rewritten in the original line.
     */
    private static ParsedCommand parseNestedCommand(ParserContext parser, String command) {
        int run = executeRunIndex(command);
        if (run < 0) return null;
        int nestedStart = run + " run ".length();
        while (nestedStart < command.length()
                && Character.isWhitespace(command.charAt(nestedStart))) nestedStart++;
        if (nestedStart >= command.length()) return null;

        String nested = command.substring(nestedStart);
        try {
            ParseResults<CommandSourceStack> results =
                    parser.commands().getDispatcher().parse(nested, parser.source());
            CommandContextBuilder<CommandSourceStack> context = results.getContext();
            while (context.getChild() != null) context = context.getChild();
            if (results.getReader().canRead() || context.getCommand() == null) return null;

            List<Integer> offsets = new ArrayList<>(nested.length() + 1);
            for (int i = 0; i <= nested.length(); i++) offsets.add(nestedStart + i);
            return new ParsedCommand(nested, results, List.copyOf(offsets));
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    /**
     * Older SNBT readers reject JSON-style control escapes such as {@code \n} inside a quoted
     * string, even though those spellings are common in text-component NBT written by datapacks.
     * Retry only the parser input with those escapes decoded to their actual control characters.
     * The source-offset table lets the resulting replacement still target the original command
     * range when decoding shortened a two-character escape to one character.
     */
    private static ParsedCommand normalizeLegacyNbtCommand(
            ParserContext parser, String command) {
        NormalizedCommand normalized = normalizeLegacyNbtEscapes(command);
        if (normalized == null) return null;

        ParseResults<CommandSourceStack> results =
                parser.commands().getDispatcher().parse(normalized.text(), parser.source());
        if (results.getReader().canRead()) return null;
        return new ParsedCommand(normalized.text(), results, normalized.sourceOffsets());
    }

    private static NormalizedCommand normalizeLegacyNbtEscapes(String command) {
        StringBuilder normalized = new StringBuilder(command.length());
        List<Integer> sourceOffsets = new ArrayList<>(command.length() + 1);
        sourceOffsets.add(0);
        char quote = 0;
        boolean escaped = false;
        boolean changed = false;

        for (int sourceIndex = 0; sourceIndex < command.length(); ) {
            char character = command.charAt(sourceIndex);
            if (quote != 0) {
                if (escaped) {
                    normalized.append(character);
                    sourceIndex++;
                    sourceOffsets.add(sourceIndex);
                    escaped = false;
                    continue;
                }

                if (character == '\\' && sourceIndex + 1 < command.length()) {
                    char escapedCharacter = command.charAt(sourceIndex + 1);
                    char decoded = switch (escapedCharacter) {
                        case 'n' -> '\n';
                        case 'r' -> '\r';
                        case 't' -> '\t';
                        case 'b' -> '\b';
                        case 'f' -> '\f';
                        default -> 0;
                    };
                    if (decoded != 0) {
                        normalized.append(decoded);
                        sourceIndex += 2;
                        sourceOffsets.add(sourceIndex);
                        changed = true;
                        continue;
                    }

                    normalized.append(character);
                    sourceIndex++;
                    sourceOffsets.add(sourceIndex);
                    escaped = true;
                    continue;
                }

                normalized.append(character);
                sourceIndex++;
                sourceOffsets.add(sourceIndex);
                if (character == quote) quote = 0;
                continue;
            }

            normalized.append(character);
            sourceIndex++;
            sourceOffsets.add(sourceIndex);
            if (character == '\'' || character == '"') quote = character;
        }

        return changed
                ? new NormalizedCommand(normalized.toString(), List.copyOf(sourceOffsets))
                : null;
    }

    static boolean isSafeMacroComponentFallback(
            MacroArgumentRestorer.CommandLine line,
            List<MacroArgumentRestorer.Replacement> replacements) {
        return line.macro()
                && CommandJsonSupport.isComponentCommand(line.source())
                && replacements.stream()
                .anyMatch(replacement -> replacement.value().contains("\"translate\""));
    }

    /** Validates the JSON component subtree after replacements have been rendered to source. */
    static boolean isSafeGeneratedCommand(String command) {
        var range = CommandJsonSupport.locateComponentJson(command);
        if (range == null) return true;
        return CommandJsonSupport.parseWithBareComponentMacros(
                        command.substring(range.start(), range.end()))
                .isPresent();
    }

    static void recordCommandFailure(String scope, String command, Throwable cause) {
        ExtractionDiagnostics diagnostics = COMMAND_DIAGNOSTICS.get();
        if (diagnostics != null) {
            diagnostics.record(scope, FunctionHandler.diagnosticLocation(command), cause);
        }
    }

    static void recordCommandWarning(String scope, String location, String message) {
        ExtractionDiagnostics diagnostics = COMMAND_DIAGNOSTICS.get();
        if (diagnostics != null) {
            diagnostics.recordWarning(
                    scope, FunctionHandler.diagnosticLocation(location), message);
        }
    }

    private static MacroArgumentRestorer.Replacement createReplacement(
            String command,
            CommandContextBuilder<CommandSourceStack> context,
            String argumentName,
            ParsedArgument<CommandSourceStack, ?> argument,
            int replacementStart,
            int replacementEnd,
            String sourceArgument,
            String materializedArgument,
            HolderLookup.Provider registries) {
        Object value = argument.getResult();
        String replacement;

        switch (value) {
            case Component component -> {
                Optional<JsonElement> sourceJson = CommandJsonSupport.parse(sourceArgument);
                if (sourceJson.isPresent()) {
                    JsonElement translated = TranslationUtils.translateLiteral(sourceJson.get());
                    if (translated == sourceJson.get()) return null;
                    replacement = translated.toString();
                } else {
                    Component translated = TranslationUtils.translateLiteral(component);
                    if (translated == component) return null;
                    replacement = TranslationUtils.translateToJson(translated);
                }
            }
            //? if >=1.21.6 {
            case Holder<?> holder when holder.value() instanceof Dialog dialog -> {
                // Keep the already materialized source tree when it is JSON.  The Dialog codec has
                // validated this tree, but its canonical encoder intentionally drops unknown fields
                // such as datapack-defined `description`.  Those fields are still component-bearing
                // text and must remain available to the schema-neutral translator.
                var encoded = CommandJsonSupport.parse(materializedArgument).orElseGet(
                        () ->
                                Dialog.DIRECT_CODEC
                                        .encodeStart(JsonOps.INSTANCE, dialog)
                                        .getOrThrow(IllegalStateException::new));
                var source = CommandJsonSupport.parse(sourceArgument).orElse(encoded);
                var translated = TranslationUtils.translateDecodedTree(source);
                if (translated == source) return null;
                replacement = translated.toString();
            }
            //?}
            case ItemInput itemInput -> {
                replacement =
                        StructuredCommandArgumentAdapter.translateItem(itemInput, registries)
                                .orElse(null);
                if (replacement == null) return null;
            }
            case BlockInput blockInput -> {
                replacement =
                        StructuredCommandArgumentAdapter.translateBlock(blockInput, sourceArgument)
                                .orElse(null);
                if (replacement == null) return null;
            }
            case CompoundTag compound
                    when CommandArgumentSupport.containsCommandNode(context, "summon") -> {
                CompoundTag translatedTag = compound.copy();
                boolean temporaryId = !translatedTag.contains("id");
                if (temporaryId) {
                    CommandArgumentSupport.findEntityId(context, command)
                            .ifPresent(id -> translatedTag.putString("id", id));
                }
                EntityTagVisitor entityVisitor = new EntityTagVisitor();
                translatedTag.accept(entityVisitor);
                boolean changed = entityVisitor.isChanged();
                // EntityTagVisitor knows the full entity schema.  The text-only pass is also
                // needed for newer display entities whose arbitrary NBT text field is a component
                // sequence rather than the single component shape used by older entities.
                changed |= translateTextNbtFields(translatedTag);
                if (!changed) return null;
                if (temporaryId) translatedTag.remove("id");
                replacement = translatedTag.toString();
            }
            case CompoundTag compound when isTextOnlyNbtArgument(context, argumentName) -> {
                CompoundTag translatedTag = compound.copy();
                if (!translateTextNbtFields(translatedTag)) return null;
                replacement = translatedTag.toString();
            }
            // A bare SNBT string is not a text component by itself.  In particular, storage
            // assignments commonly carry macro values such as `red`, identifiers, and counters;
            // translating those strings into component objects makes a later `with storage` macro
            // emit `{translate:...}` inside a quoted JSON scalar and the game rejects the command.
            // Restrict the legacy scalar/list path to the one NBT field whose schema is known to be
            // a text-display component.  Component arguments (tellraw/title/dialog, item lore,
            // etc.) are decoded through the Component branch above and remain unaffected.
            case StringTag stringTag when isTextNbtValueArgument(command, context) -> {
                String translated = TranslationUtils.translateLiteral(stringTag.toString(), false);
                if (translated.equals(stringTag.toString())) return null;
                replacement = translated;
            }
            case ListTag listTag when isTextNbtValueArgument(command, context) || isComponentList(listTag) -> {
                ChangeTracker tracker = new ChangeTracker();
                ListTag tag = sourceList(sourceArgument, listTag);
                for (int i = 0; i < tag.size(); i++) {
                    tracker.add(TranslationUtils.translateNbtComponent(tag, i, "list." + i));
                }
                if (!tracker.isChanged()) return null;
                replacement = NbtUtils.toJson(tag).toString();
            }
            case null, default -> {
                return null;
            }
        }

        return new MacroArgumentRestorer.Replacement(
                replacementStart, replacementEnd, replacement);
    }

    private static ListTag sourceList(String sourceArgument, ListTag fallback) {
        if (sourceArgument == null || sourceArgument.isBlank()) return fallback.copy();
        try {
            //? if >=1.21.5 {
            Tag parsed = TagParser.create(NbtOps.INSTANCE).parseFully(sourceArgument);
            //?} else {
            /*Tag parsed = fallback.copy();
            *///?}
            return parsed instanceof ListTag list ? list : fallback.copy();
        } catch (Exception ignored) {
            return fallback.copy();
        }
    }

    /**
     * Translates only fields literally named {@code text} in command NBT payloads.
     *
     * <p>Function arguments and {@code data merge entity} payloads are open schemas.  Guessing
     * that every string in them is visible text would turn values such as {@code money:125} or
     * {@code color:red} into translation entries.  A field named {@code text} is the one useful
     * piece of provenance available without knowing where the called function will consume the
     * payload, so the traversal deliberately stays that narrow while still following nested
     * compounds and lists.
     */
    private static boolean translateTextNbtFields(CompoundTag root) {
        return translateTextNbtFields(root, "");
    }

    private static boolean translateTextNbtFields(CompoundTag compound, String path) {
        boolean changed = false;
        for (String name : new ArrayList<>(NbtUtils.getKeys(compound))) {
            Tag child = compound.get(name);
            if (child == null) continue;

            String keyPath = path.isBlank() ? name : path + "." + name;
            if ("text".equals(name)) {
                changed |= TranslationUtils.translateNbtComponent(compound, name, keyPath);
                child = compound.get(name);
                if (child == null) continue;
            }
            changed |= translateTextNbtChildren(child, keyPath);
        }
        return changed;
    }

    private static boolean translateTextNbtChildren(Tag tag, String path) {
        if (tag instanceof CompoundTag compound) {
            return translateTextNbtFields(compound, path);
        }
        if (!(tag instanceof ListTag list)) return false;

        boolean changed = false;
        for (int i = 0; i < list.size(); i++) {
            Tag child = list.get(i);
            if (child instanceof CompoundTag compound) {
                changed |= translateTextNbtFields(compound, path + "." + i);
            } else if (child instanceof ListTag nested) {
                changed |= translateTextNbtChildren(nested, path + "." + i);
            }
        }
        return changed;
    }

    private static boolean isTextOnlyNbtArgument(
            CommandContextBuilder<CommandSourceStack> context, String argumentName) {
        // The guard is intentionally command-shape based rather than a generic CompoundTag rule:
        // item/block/entity payloads have richer schema visitors and must not enter this heuristic.
        if (CommandArgumentSupport.containsCommandNode(context, "data")) {
            return CommandArgumentSupport.containsCommandNode(context, "merge")
                    && CommandArgumentSupport.containsCommandNode(context, "entity");
        }
        return CommandArgumentSupport.containsCommandNode(context, "function");
    }

    /**
     * Extracts a component-bearing JSON argument when a macro placeholder makes Brigadier stop
     * before the end of an otherwise balanced command.  Caller materialization remains the first
     * path; this fallback is only for unresolved macros whose internal parser stand-in is not a
     * legal value for the command argument (for example {@code color=$(event_color)}).
     *
     * <p>The source and masked ranges are both derived from {@link MacroArgumentRestorer.CommandLine}
     * and the replacement is rendered through the same range mapper as ordinary arguments.  This
     * keeps equal macro names in separate fields from affecting one another and avoids global text
     * replacement.
     */
    private static MacroArgumentRestorer.Replacement findMacroArgumentReplacement(
            MacroArgumentRestorer.CommandLine line) {
        if (!CommandJsonSupport.isComponentCommand(line.source())) return null;
        var materialized = CommandJsonSupport.locateComponentJson(line.text());
        if (materialized == null) return null;

        Optional<JsonElement> materializedJson =
                CommandJsonSupport.parse(
                        line.text().substring(materialized.start(), materialized.end()));
        Optional<JsonElement> sourceJson =
                CommandJsonSupport.parse(
                        line.translationArgument(materialized.start(), materialized.end()));
        if (materializedJson.isEmpty() || sourceJson.isEmpty()) return null;

        try (var transaction = TranslationContext.beginTransaction()) {
            // Parsing the materialized value verifies that the candidate is balanced JSON. The
            // source tree deliberately keeps unresolved macros so text fields can become `with`
            // arguments instead of leaking the internal stand-in into the language catalog.
            JsonElement translated = CommandJsonSupport.looksLikeDialogSchema(sourceJson.get())
                    ? TranslationUtils.translateDecodedTree(sourceJson.get())
                    : TranslationUtils.translateLiteral(sourceJson.get());
            if (translated == sourceJson.get()) return null;
            transaction.commit();
            return new MacroArgumentRestorer.Replacement(
                    materialized.start(), materialized.end(), translated.toString());
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static boolean isTextNbtValueArgument(
            String command, CommandContextBuilder<CommandSourceStack> context) {
        if (!CommandArgumentSupport.containsCommandNode(context, "data")
                || !CommandArgumentSupport.containsCommandNode(context, "modify")
                || !CommandArgumentSupport.containsCommandNode(context, "entity")) {
            return false;
        }
        ParsedArgument<CommandSourceStack, ?> path =
                CommandArgumentSupport.findArgument(context, "path", "targetPath");
        if (path == null) return false;
        String value = CommandArgumentSupport.argumentText(command, path).trim();
        while ((value.startsWith("\"") && value.endsWith("\""))
                || (value.startsWith("'") && value.endsWith("'"))) {
            value = value.substring(1, value.length() - 1).trim();
        }
        // Text-display entities use the `text` component field.  Do not infer visibility from a
        // generic string/list in storage or an arbitrary entity path.
        int lastSegment = value.lastIndexOf('.') + 1;
        String segment = value.substring(lastSegment);
        int index = segment.indexOf('[');
        if (index >= 0) segment = segment.substring(0, index);
        return "text".equals(segment);
    }

    private static boolean isComponentList(ListTag list) {
        if (list == null || list.isEmpty()) return false;
        for (Tag child : list) {
            if (!(child instanceof CompoundTag compound)) return false;
            boolean component =
                    NbtUtils.getKeys(compound).stream()
                            .anyMatch(TEXT_COMPONENTS::contains);
            if (!component) return false;
        }
        return true;
    }

    /** A selector name identifies a runtime target and is never a translatable component. */
    private static void recordSelectorNameWarnings(String command) {
        for (int cursor = 0; cursor < command.length(); cursor++) {
            if (command.charAt(cursor) != '@'
                    || cursor + 1 >= command.length()
                    || "pares".indexOf(command.charAt(cursor + 1)) < 0) {
                continue;
            }

            int open = cursor + 2;
            if (open >= command.length() || command.charAt(open) != '[') continue;
            int close = selectorEnd(command, open);
            if (close < 0) continue;

            String options = command.substring(open + 1, close);
            if (hasSelectorNameOption(options)) {
                String selector = command.substring(cursor, close + 1);
                recordCommandFailure(
                        "function_selector_name",
                        selector,
                        new IllegalArgumentException(
                                "Target selector name= is runtime-specific and was left unchanged"));
            }
            cursor = close;
        }
    }

    private static int selectorEnd(String command, int open) {
        char quote = 0;
        boolean escaped = false;
        int depth = 0;
        for (int i = open; i < command.length(); i++) {
            char c = command.charAt(i);
            if (quote != 0) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == quote) {
                    quote = 0;
                }
                continue;
            }
            if (c == '\'' || c == '"') {
                quote = c;
            } else if (c == '[') {
                depth++;
            } else if (c == ']' && --depth == 0) {
                return i;
            }
        }
        return -1;
    }

    private static boolean hasSelectorNameOption(String options) {
        char quote = 0;
        boolean escaped = false;
        int start = 0;
        for (int i = 0; i <= options.length(); i++) {
            char c = i == options.length() ? ',' : options.charAt(i);
            if (quote != 0) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == quote) {
                    quote = 0;
                }
                continue;
            }
            if (c == '\'' || c == '"') {
                quote = c;
                continue;
            }
            if (c != ',') continue;

            String option = options.substring(start, i).trim();
            int equals = option.indexOf('=');
            if (equals > 0 && "name".equals(option.substring(0, equals).trim())) return true;
            start = i + 1;
        }
        return false;
    }

    private static ParserContext createParserContext(RegistryAccess registries) {
        return createParserContext((HolderLookup.Provider) registries);
    }

    private static ParserContext createParserContext(HolderLookup.Provider registries) {
        Commands commands =
                new Commands(
                        Commands.CommandSelection.ALL,
                        Commands.createValidationContext(registries));
        return new ParserContext(commands, createCommandSource(), registries);
    }

    private static CommandSourceStack createCommandSource() {
        return new CommandSourceStack(
                CommandSource.NULL,
                Vec3.ZERO,
                Vec2.ZERO,
                null,
                //? if >=1.21.11 {
                PermissionSet.ALL_PERMISSIONS,
                //?} else
                //3,
                "WTEM",
                CommonComponents.EMPTY,
                null,
                null);
    }

    record ParserContext(
            Commands commands, CommandSourceStack source, HolderLookup.Provider registries) {}

    private record ParsedCommand(
            String text,
            ParseResults<CommandSourceStack> results,
            List<Integer> sourceOffsets) {
        int sourceOffset(int parserOffset) {
            if (sourceOffsets == null) return parserOffset;
            if (parserOffset < 0 || parserOffset >= sourceOffsets.size()) return parserOffset;
            return sourceOffsets.get(parserOffset);
        }
    }

    private record NormalizedCommand(String text, List<Integer> sourceOffsets) {}

    private static final class ChangeTracker {
        private boolean changed;

        void add(boolean changed) {
            this.changed |= changed;
        }

        boolean isChanged() {
            return this.changed;
        }
    }
}
