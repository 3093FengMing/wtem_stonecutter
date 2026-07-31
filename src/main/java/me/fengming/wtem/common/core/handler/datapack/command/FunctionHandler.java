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
import me.fengming.wtem.common.util.ResourceIo;
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

            String command = logicalLine.value();
            if (command.isEmpty() || command.startsWith("#") || command.startsWith("$")) continue;

            try (var transaction = TranslationContext.beginTransaction()) {
                List<Replacement> replacements = findReplacements(parser, command);
                if (replacements.isEmpty()) continue;

                String updated = applyReplacements(command, replacements);
                if (!isValidCommand(parser, updated)) continue;

                logicalLine.write(modified, updated);
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

    private static List<Replacement> findReplacements(ParserContext parser, String command) {
        try {
            ParseResults<CommandSourceStack> results =
                    parser.commands().getDispatcher().parse(command, parser.source());
            if (results.getReader().canRead()) {
                ExtractionDiagnostics diagnostics = COMMAND_DIAGNOSTICS.get();
                if (diagnostics != null) {
                    diagnostics.record(
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

                    try (var transaction = TranslationContext.beginTransaction()) {
                        Replacement replacement =
                                createReplacement(
                                        context,
                                        entry.getKey(),
                                        argument,
                                        command.substring(
                                                argument.getRange().getStart(),
                                                argument.getRange().getEnd()),
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
            ExtractionDiagnostics diagnostics = COMMAND_DIAGNOSTICS.get();
            if (diagnostics != null) {
                diagnostics.record("function_command", command, exception);
            }
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

        if (value instanceof Component component) {
            Component translated = TranslationUtils.translateLiteral(component);
            if (translated == component) return null;
            replacement = TranslationUtils.translateToJson(translated);
        } else if (value instanceof ItemInput itemInput) {
            replacement =
                    StructuredCommandArgumentAdapter.translateItem(itemInput, registries)
                            .orElse(null);
            if (replacement == null) return null;
        } else if (value instanceof BlockInput blockInput) {
            replacement =
                    StructuredCommandArgumentAdapter.translateBlock(blockInput, sourceArgument)
                            .orElse(null);
            if (replacement == null) return null;
        } else if (value instanceof CompoundTag compound
                && "nbt".equals(argumentName)
                && containsCommandNode(context, "summon")) {
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
        } else {
            return null;
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

    private record Replacement(int start, int end, String value) {}

    private record ParserContext(
            Commands commands, CommandSourceStack source, HolderLookup.Provider registries) {}

    private record FunctionResult(String value, boolean changed) {}

    private record ProcessedLines(List<String> lines, boolean changed) {}

}
