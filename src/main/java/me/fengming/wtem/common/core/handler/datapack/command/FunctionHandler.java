package me.fengming.wtem.common.core.handler.datapack.command;

import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.context.CommandContextBuilder;
import com.mojang.brigadier.context.ParsedArgument;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import me.fengming.wtem.common.core.extraction.ExtractionDiagnostics;
import me.fengming.wtem.common.core.extraction.TranslationContext;
import me.fengming.wtem.common.core.handler.datapack.HandlerFactory;
import me.fengming.wtem.common.core.handler.datapack.NonExtraResourceHandler;
import me.fengming.wtem.common.core.visitor.EntityTagVisitor;
import me.fengming.wtem.common.util.*;
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
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.IoSupplier;
//? if >=1.21.11
import net.minecraft.server.permissions.PermissionSet;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;

/**
 * Extracts components and NBT literals from commands while preserving every function line.
 *
 * @author FengMing
 */
public class FunctionHandler extends NonExtraResourceHandler {
    public static final HandlerFactory FACTORY = FunctionHandler::new;
    private static final int MAX_COMMAND_LENGTH = 2_000_000;
    private static final ThreadLocal<ParserContext> COMMAND_PARSER = new ThreadLocal<>();
    private static final ThreadLocal<ExtractionDiagnostics> COMMAND_DIAGNOSTICS = new ThreadLocal<>();

    public FunctionHandler(Function<Identifier, Path> filePath, Context context) {
        super("function", filePath, context);
    }

    @Override
    protected String fileExtension() {
        return ".mcfunction";
    }

    @Override
    protected boolean innerHandle(Identifier rl, IoSupplier<InputStream> supplier) {
        FunctionResult result = processFunction(supplier);
        if (result.changed()) ResourceIo.writeString(getFilePath(rl), result.value());
        return result.changed();
    }

    private static FunctionResult processFunction(IoSupplier<InputStream> supplier) {
        String source;
        try (InputStream input = supplier.get()) {
            source = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }

        return processSource(source);
    }

    public static String processFunction(String source) {
        return processSource(source).value();
    }

    private static FunctionResult processSource(String source) {
        FunctionSource sourceFile = FunctionSource.parse(source);
        ProcessedLines processed = processLines(sourceFile.lines());
        return processed.changed()
                ? new FunctionResult(sourceFile.render(processed.lines()), true)
                : new FunctionResult(source, false);
    }

    public static String processFunction(List<String> lines) {
        return String.join("\n", processLines(lines).lines());
    }

    private static ProcessedLines processLines(List<String> lines) {
        ParserContext parser = parserContext();
        List<String> modified = new ArrayList<>(lines);
        boolean changed = false;

        for (int i = 0; i < lines.size(); i++) {
            FunctionSource.LogicalCommand logicalLine =
                    FunctionSource.LogicalCommand.read(lines, i);
            i = logicalLine.lastLineIndex();

            String source = logicalLine.value();
            if (source.isEmpty() || source.startsWith("#")) continue;

            CommandLine line = CommandLine.of(source);
            if (line.text().isEmpty()) continue;

            try (var transaction = TranslationContext.beginTransaction()) {
                List<Replacement> replacements = findReplacements(parser, line);
                if (replacements.isEmpty()) continue;

                // Validation has to run against the masked form, because the interpolations a macro
                // line carries are not valid command syntax on their own.
                if (!isValidCommand(parser, applyReplacements(line.text(), replacements))) continue;

                logicalLine.write(modified, line.render(replacements));
                transaction.commit();
                changed = true;
            }
        }
        return new ProcessedLines(List.copyOf(modified), changed);
    }

    public static void initializeParser(
            RegistryAccess registries, ExtractionDiagnostics diagnostics) {
        COMMAND_PARSER.set(createParserContext(registries));
        COMMAND_DIAGNOSTICS.set(diagnostics);
    }

    public static void releaseParser() {
        COMMAND_PARSER.remove();
        COMMAND_DIAGNOSTICS.remove();
    }

    private static ParserContext parserContext() {
        ParserContext parser = COMMAND_PARSER.get();
        if (parser != null) return parser;
        parser = createParserContext(VanillaRegistries.createLookup());
        COMMAND_PARSER.set(parser);
        return parser;
    }

    private static ParserContext createParserContext(RegistryAccess registries) {
        return createParserContext((net.minecraft.core.HolderLookup.Provider) registries);
    }

    private static ParserContext createParserContext(
            HolderLookup.Provider registries) {
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

    private static List<Replacement> findReplacements(ParserContext parser, CommandLine line) {
        String command = line.text();
        try {
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
                }
                return List.of();
            }
            Map<String, Replacement> replacements = new LinkedHashMap<>();

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

                    // Replacing an argument drops its original text. Where that text carries an
                    // interpolation the substitution would delete the macro variable, so such an
                    // argument is left alone even though it parsed cleanly against the mask.
                    if (line.isMasked(
                            argument.getRange().getStart(), argument.getRange().getEnd())) {
                        continue;
                    }

                    String sourceArgument =
                            command.substring(
                                    argument.getRange().getStart(), argument.getRange().getEnd());

                    try (var transaction = TranslationContext.beginTransaction()) {
                        Replacement replacement =
                                createReplacement(
                                        context,
                                        entry.getKey(),
                                        argument,
                                        sourceArgument,
                                        parser.registries());
                        if (replacement == null) continue;
                        transaction.commit();
                        replacements.put(rangeKey, replacement);
                    }
                }
                context = context.getChild();
            }

            List<Replacement> ordered = new ArrayList<>(replacements.values());
            ordered.sort(Comparator.comparingInt(Replacement::start).reversed());
            return List.copyOf(ordered);
        } catch (RuntimeException exception) {
            if (!line.macro()) recordCommandFailure("function_command", command, exception);
            return List.of();
        }
    }

    private static String applyReplacements(String command, List<Replacement> replacements) {
        StringBuilder updated = new StringBuilder(command);
        for (Replacement replacement : replacements) {
            updated.replace(replacement.start(), replacement.end(), replacement.value());
        }
        return updated.toString();
    }

    private static boolean isValidCommand(ParserContext parser, String command) {
        if (command.length() > MAX_COMMAND_LENGTH) {
            recordCommandFailure(
                    "function_reparse",
                    command.substring(0, Math.min(command.length(), 512)),
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

    private static void recordCommandFailure(String scope, String command, Throwable cause) {
        ExtractionDiagnostics diagnostics = COMMAND_DIAGNOSTICS.get();
        if (diagnostics != null) diagnostics.record(scope, command, cause);
    }

    private static Replacement createReplacement(
            CommandContextBuilder<CommandSourceStack> context,
            String argumentName,
            ParsedArgument<CommandSourceStack, ?> argument,
            String sourceArgument,
            HolderLookup.Provider registries) {
        Object value = argument.getResult();
        String replacement;

        switch (value) {
            case Component component -> {
                Component translated = TranslationUtils.translateLiteral(component);
                if (translated == component) return null;
                replacement = TranslationUtils.translateToJson(translated);
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
            case CompoundTag compound when "nbt".equals(argumentName) && containsCommandNode(context, "summon") -> {
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

        return new Replacement(
                argument.getRange().getStart(), argument.getRange().getEnd(), replacement);
    }

    private static boolean containsCommandNode(
            CommandContextBuilder<CommandSourceStack> context, String name) {
        return context.getNodes().stream()
                .anyMatch(node -> name.equals(node.getNode().getName()));
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

    /**
     * A function line presented to the command parser, with macro interpolations masked out.
     *
     * <p>A macro line is an ordinary command behind a {@code $} marker, except that {@code $(name)}
     * interpolations stand where the grammar expects a concrete value. The dispatcher rejects those
     * outright, which is why macro lines used to be skipped entirely. Replacing each interpolation
     * with an equal-length placeholder gives the parser something it can accept while keeping every
     * argument at its original offset, so the ranges it reports still address {@code source}.
     *
     * @param source the line exactly as it appears in the function file
     * @param text the form handed to the parser: marker dropped, interpolations masked
     * @param masks offsets of the masked regions, in ascending order
     */
    private record CommandLine(String source, String text, List<Range> masks) {
        private static final String MARKER = "$";
        private static final String VARIABLE_PREFIX = "$(";
        // A repeated '1' is the least restrictive filler: it satisfies numeric slots, including the
        // ones that reject zero, and is also valid inside a string or a resource location, whereas a
        // letter would rule the numeric slots out. A long enough interpolation still overflows a
        // numeric slot, which simply leaves the line untranslated.
        private static final char MASK_CHARACTER = '1';

        static CommandLine of(String source) {
            if (!source.startsWith(MARKER)) return new CommandLine(source, source, List.of());

            String body = source.substring(MARKER.length());
            StringBuilder masked = new StringBuilder(body);
            List<Range> masks = new ArrayList<>();
            int start = body.indexOf(VARIABLE_PREFIX);
            while (start >= 0) {
                int end = body.indexOf(')', start);
                if (end < 0) break;
                end++;

                masks.add(new Range(start, end));
                for (int i = start; i < end; i++) masked.setCharAt(i, MASK_CHARACTER);
                start = body.indexOf(VARIABLE_PREFIX, end);
            }
            return new CommandLine(source, masked.toString(), List.copyOf(masks));
        }

        boolean macro() {
            return this.source.startsWith(MARKER);
        }

        /** Reports whether {@code [start, end)} overlaps a masked interpolation. */
        boolean isMasked(int start, int end) {
            return this.masks.stream().anyMatch(mask -> mask.start() < end && start < mask.end());
        }

        /** Applies {@code replacements} to the original line rather than to the masked form. */
        String render(List<Replacement> replacements) {
            String body = applyReplacements(this.source.substring(offset()), replacements);
            return this.macro() ? MARKER + body : body;
        }

        private int offset() {
            return this.macro() ? MARKER.length() : 0;
        }
    }

    private record Range(int start, int end) {}

    private record Replacement(int start, int end, String value) {}

    private record ParserContext(
            Commands commands, CommandSourceStack source, HolderLookup.Provider registries) {}

    private record FunctionResult(String value, boolean changed) {}

    private record ProcessedLines(List<String> lines, boolean changed) {}

}
