package me.fengming.wtem.common.core.handler.datapack.command;

import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.context.CommandContextBuilder;
import com.mojang.brigadier.context.ParsedArgument;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import me.fengming.wtem.common.core.extraction.service.ExtractionDiagnostics;
import me.fengming.wtem.common.core.extraction.TranslationContext;
import me.fengming.wtem.common.core.visitor.EntityTagVisitor;
import me.fengming.wtem.common.util.NbtUtils;
import me.fengming.wtem.common.util.ResourceIds;
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
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
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
        if (!isValidCommand(parser, applyReplacements(line.text(), replacements))) {
            return CommandExtraction.unchanged(line.source());
        }
        return CommandExtraction.changed(line.render(replacements));
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

        String target = argumentText(command, name);
        ParsedArgument<CommandSourceStack, ?> storageArgument =
                findArgument(rootContext, "source", "storage", "storageId");
        ParsedArgument<CommandSourceStack, ?> pathArgument = findArgument(rootContext, "path");
        if (storageArgument == null) {
            return new MacroCallGraph.ParsedInvocation(target, null, null);
        }

        return new MacroCallGraph.ParsedInvocation(
                target,
                argumentText(command, storageArgument),
                pathArgument == null ? null : argumentText(command, pathArgument));
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
        boolean modify = containsCommandNode(context, "modify");
        boolean merge = containsCommandNode(context, "merge");
        if (!containsCommandNode(context, "data") || (!modify && !merge)) return null;
        // Only `data modify ... set value` replaces a path with this exact literal. Treating
        // append/prepend/insert/merge as an assignment would feed a value into a caller that never
        // actually receives that shape. `data merge storage ...` is the separate root merge form.
        if (modify
                && (!containsCommandNode(context, "set")
                        || !containsCommandNode(context, "value"))) {
            return null;
        }

        ParsedArgument<CommandSourceStack, ?> target = findArgument(context, "target");
        ParsedArgument<CommandSourceStack, ?> path = findArgument(context, "path", "targetPath");
        ParsedArgument<CommandSourceStack, ?> value =
                findArgument(context, "value", "nbt", "tag");
        if (target == null || value == null) return null;

        String storageId = storageId(command, target);
        if (storageId == null || storageId.isBlank()) return null;

        Object parsedValue = value.getResult();
        if (!(parsedValue instanceof Tag tag)) return null;
        Map<String, String> values = storageValues(tag);
        if (values.isEmpty()) return null;

        return new MacroCallGraph.StorageAssignment(
                storageId,
                path == null ? "" : argumentText(command, path),
                Map.copyOf(values));
    }

    static MacroCallGraph.StorageAssignment parseStorageAssignment(String sourceLine) {
        return parseStorageAssignment(parserContext(), sourceLine);
    }

    static List<MacroArgumentRestorer.Replacement> findReplacements(
            ParserContext parser, MacroArgumentRestorer.CommandLine line) {
        String command = line.text();
        try {
            recordSelectorNameWarnings(command);
            ParseResults<CommandSourceStack> results =
                    parser.commands().getDispatcher().parse(command, parser.source());
            if (results.getReader().canRead()) {
                // A masked interpolation is only a plausible stand-in, not necessarily a valid one,
                // so a macro line that fails to parse is expected rather than a defect to report.
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
            Map<String, MacroArgumentRestorer.Replacement> replacements = new LinkedHashMap<>();

            CommandContextBuilder<CommandSourceStack> context = results.getContext();
            while (context != null) {
                for (Map.Entry<String, ParsedArgument<CommandSourceStack, ?>> entry :
                        context.getArguments().entrySet()) {
                    ParsedArgument<CommandSourceStack, ?> argument = entry.getValue();
                    String rangeKey =
                            argument.getRange().getStart()
                                    + ":"
                                    + argument.getRange().getEnd();
                    if (replacements.containsKey(rangeKey)) continue;

                    boolean masked =
                            line.isMasked(
                                    argument.getRange().getStart(), argument.getRange().getEnd());
                    boolean unresolvedMask =
                            masked
                                    && line.hasUnresolvedMask(
                                            argument.getRange().getStart(),
                                            argument.getRange().getEnd());
                    // An unresolved stand-in cannot safely be serialized into a structured
                    // argument. A real caller value can: the replacement is processed normally
                    // and restoreMacros below puts the corresponding $(name) back afterwards.
                    if (unresolvedMask
                            && !(argument.getResult() instanceof Component)) {
                        continue;
                    }

                    String sourceArgument =
                            line.sourceArgument(
                                    argument.getRange().getStart(), argument.getRange().getEnd());

                    try (var transaction = TranslationContext.beginTransaction()) {
                        int recordsBefore = TranslationContext.recordCount();
                        Set<String> catalogKeysBefore = TranslationContext.snapshot().keySet();
                        MacroArgumentRestorer.Replacement replacement =
                                createReplacement(
                                        context,
                                        entry.getKey(),
                                        argument,
                                        sourceArgument,
                                        parser.registries(),
                                        unresolvedMask);
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
            if (ordered.isEmpty() && line.macro()) return findMacroJsonReplacements(line);
            return List.copyOf(ordered);
        } catch (RuntimeException exception) {
            if (!line.macro()) {
                recordCommandFailure("function_command", command, exception);
                return List.of();
            }
            return findMacroJsonReplacements(line);
        }
    }

    /**
     * Best-effort extraction for macro commands whose command-specific codec rejects the masked
     * value. Mojang's dispatcher has changed the inline dialog grammar several times; the JSON
     * component itself is stable, so scan balanced JSON and transform that argument independently.
     */
    private static List<MacroArgumentRestorer.Replacement> findMacroJsonReplacements(
            MacroArgumentRestorer.CommandLine line) {
        String command = line.text();
        for (int start = 0; start < command.length(); start++) {
            char opening = command.charAt(start);
            if (opening != '{' && opening != '[') continue;
            int end = balancedJsonEnd(command, start, opening);
            if (end < 0) continue;

            String sourceArgument = line.sourceArgument(start, end);
            if (!looksLikeComponentJson(sourceArgument)) continue;
            String replacement = MacroCommandMaterializer.translateMaskedComponent(sourceArgument, null);
            if (replacement == null) continue;
            return List.of(new MacroArgumentRestorer.Replacement(start, end, replacement, true));
        }
        return List.of();
    }

    private static int balancedJsonEnd(String command, int start, char opening) {
        char closing = opening == '{' ? '}' : ']';
        char quote = 0;
        boolean escaped = false;
        int depth = 0;
        for (int i = start; i < command.length(); i++) {
            char c = command.charAt(i);
            if (quote != 0) {
                if (escaped) escaped = false;
                else if (c == '\\') escaped = true;
                else if (c == quote) quote = 0;
                continue;
            }
            if (c == '"') {
                quote = c;
            } else if (c == opening) {
                depth++;
            } else if (c == closing && --depth == 0) {
                return i + 1;
            }
        }
        return -1;
    }

    private static boolean looksLikeComponentJson(String source) {
        return source.contains("\"text\"")
                || source.contains("\"translate\"")
                || source.contains("\"contents\"")
                || source.contains("\"title\"")
                || source.contains("\"body\"");
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
        String masked =
                value.replaceAll(
                        "(\"(?:color|shadow_color)\"\\s*:\\s*\")\\$\\([^)]*\\)",
                        "$1white");
        return masked.replaceAll("\\$\\([^)]*\\)", "1");
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

    static void recordCommandFailure(String scope, String command, Throwable cause) {
        ExtractionDiagnostics diagnostics = COMMAND_DIAGNOSTICS.get();
        if (diagnostics != null) diagnostics.record(scope, command, cause);
    }

    private static MacroArgumentRestorer.Replacement createReplacement(
            CommandContextBuilder<CommandSourceStack> context,
            String argumentName,
            ParsedArgument<CommandSourceStack, ?> argument,
            String sourceArgument,
            HolderLookup.Provider registries,
            boolean unresolvedMask) {
        Object value = argument.getResult();
        String replacement;

        switch (value) {
            case Component component -> {
                if (unresolvedMask) {
                    replacement =
                            MacroCommandMaterializer.translateMaskedComponent(sourceArgument, component);
                    if (replacement == null) return null;
                } else {
                    Component translated = TranslationUtils.translateLiteral(component);
                    if (translated == component) return null;
                    replacement = TranslationUtils.translateToJson(translated);
                }
            }
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
                    when "nbt".equals(argumentName) && containsCommandNode(context, "summon") -> {
                CompoundTag translatedTag = compound.copy();
                boolean temporaryId = !translatedTag.contains("id");
                if (temporaryId) {
                    findEntityId(context).ifPresent(id -> translatedTag.putString("id", id));
                }
                EntityTagVisitor entityVisitor = new EntityTagVisitor();
                translatedTag.accept(entityVisitor);
                if (!entityVisitor.isChanged()) return null;
                if (temporaryId) translatedTag.remove("id");
                replacement = translatedTag.toString();
            }
            case StringTag stringTag -> {
                String translated = TranslationUtils.translateLiteral(stringTag.toString(), false);
                if (translated.equals(stringTag.toString())) return null;
                replacement = translated;
            }
            case ListTag listTag -> {
                ChangeTracker tracker = new ChangeTracker();
                ListTag tag = listTag.copy();
                for (int i = 0; i < listTag.size(); i++) {
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
                argument.getRange().getStart(), argument.getRange().getEnd(), replacement, false);
    }

    private static boolean containsCommandNode(
            CommandContextBuilder<CommandSourceStack> context, String name) {
        CommandContextBuilder<CommandSourceStack> current = context;
        while (current != null) {
            if (current.getNodes().stream()
                    .anyMatch(node -> name.equals(node.getNode().getName()))) return true;
            current = current.getChild();
        }
        return false;
    }

    private static java.util.Optional<String> findEntityId(
            CommandContextBuilder<CommandSourceStack> context) {
        ParsedArgument<CommandSourceStack, ?> entityArgument =
                context.getArguments().get("entity");
        if (entityArgument == null || !(entityArgument.getResult() instanceof Holder<?> holder)) {
            return java.util.Optional.empty();
        }
        return holder.unwrapKey().map(ResourceIds::key);
    }

    private static String argumentText(
            String command, ParsedArgument<CommandSourceStack, ?> argument) {
        return command.substring(argument.getRange().getStart(), argument.getRange().getEnd());
    }

    private static ParsedArgument<CommandSourceStack, ?> findArgument(
            CommandContextBuilder<CommandSourceStack> context, String... names) {
        CommandContextBuilder<CommandSourceStack> current = context;
        while (current != null) {
            for (String name : names) {
                ParsedArgument<CommandSourceStack, ?> argument = current.getArguments().get(name);
                if (argument != null) return argument;
            }
            current = current.getChild();
        }
        return null;
    }

    private static String storageId(
            String command, ParsedArgument<CommandSourceStack, ?> target) {
        String targetText = argumentText(command, target).trim();
        if (targetText.startsWith("storage ")) {
            return firstToken(targetText.substring("storage ".length()));
        }

        // In command versions where `storage` is a literal node, Brigadier's target argument
        // starts at the identifier itself. Verify the preceding literal rather than guessing a
        // storage command from arbitrary text.
        int start = target.getRange().getStart();
        while (start > 0 && Character.isWhitespace(command.charAt(start - 1))) start--;
        int literalEnd = start;
        while (start > 0 && !Character.isWhitespace(command.charAt(start - 1))) start--;
        if ("storage".equals(command.substring(start, literalEnd))) {
            return command.substring(target.getRange().getStart(), target.getRange().getEnd());
        }
        return null;
    }

    private static String firstToken(String value) {
        int end = 0;
        while (end < value.length() && !Character.isWhitespace(value.charAt(end))) end++;
        return value.substring(0, end);
    }

    private static Map<String, String> storageValues(Tag tag) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("$value", storageValue(tag));
        flattenStorageValues(values, "", tag);
        return values;
    }

    private static void flattenStorageValues(
            Map<String, String> values, String prefix, Tag tag) {
        if (!(tag instanceof CompoundTag compound)) return;
        for (String key : NbtUtils.getKeys(compound)) {
            Tag child = compound.get(key);
            if (child == null) continue;
            String path = prefix.isEmpty() ? key : prefix + "." + key;
            values.put(path, storageValue(child));
            flattenStorageValues(values, path, child);
        }
    }

    private static String storageValue(Tag tag) {
        return tag instanceof StringTag stringTag
                ? NbtUtils.getStringValue(stringTag)
                : tag.toString();
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
