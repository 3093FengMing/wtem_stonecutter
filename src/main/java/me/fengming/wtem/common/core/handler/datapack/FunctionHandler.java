package me.fengming.wtem.common.core.handler.datapack;

import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.context.CommandContextBuilder;
import com.mojang.brigadier.context.ParsedArgument;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import me.fengming.wtem.common.core.handler.BlockEntityWHandler;
import me.fengming.wtem.common.core.visitor.EntityTagVisitor;
import me.fengming.wtem.common.core.visitor.ItemTagVisitor;
import me.fengming.wtem.common.util.ResourceIo;
import me.fengming.wtem.common.util.TranslationUtils;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
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

/** Extracts components and NBT literals from commands while preserving every function line.
 * @author FengMing*/
public class FunctionHandler extends NonExtraResourceHandler {
    public static final HandlerFactory FACTORY = FunctionHandler::new;
    private static final ThreadLocal<ParserContext> COMMAND_PARSER =
            ThreadLocal.withInitial(FunctionHandler::createParserContext);

    public FunctionHandler(Function<Identifier, Path> filePath, Context context) {
        super("function", filePath, context);
    }

    @Override
    protected void innerHandle(Identifier rl, IoSupplier<InputStream> supplier) {
        ResourceIo.writeString(getFilePath(rl), processFunction(supplier));
    }

    private static String processFunction(IoSupplier<InputStream> supplier) {
        List<String> lines;
        try (BufferedReader bufferedReader =
                new BufferedReader(
                        new InputStreamReader(supplier.get(), StandardCharsets.UTF_8))) {
            lines = bufferedReader.lines().toList();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return processFunction(lines);
    }

    public static String processFunction(List<String> lines) {
        ParserContext parser = COMMAND_PARSER.get();
        List<String> modified = new ArrayList<>();

        for (int i = 0; i < lines.size(); i++) {
            String logicalLine = lines.get(i);
            while (endsWithContinuation(logicalLine)) {
                if (++i >= lines.size()) {
                    throw new IllegalArgumentException("Line continuation at end of file");
                }
                logicalLine = removeContinuation(logicalLine) + lines.get(i).stripLeading();
            }

            String stripped = logicalLine.stripLeading();
            if (stripped.isEmpty() || stripped.startsWith("#") || stripped.startsWith("$")) {
                modified.add(logicalLine);
                continue;
            }

            modified.add(processCommand(parser.commands(), parser.source(), logicalLine));
        }
        return String.join("\n", modified);
    }

    public static void releaseParser() {
        COMMAND_PARSER.remove();
    }

    private static ParserContext createParserContext() {
        Commands commands =
                new Commands(
                        Commands.CommandSelection.ALL,
                        Commands.createValidationContext(VanillaRegistries.createLookup()));
        return new ParserContext(commands, createCommandSource());
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

    private static String processCommand(
            Commands commands, CommandSourceStack source, String command) {
        try {
            ParseResults<CommandSourceStack> results =
                    commands.getDispatcher().parse(command, source);
            Map<String, Replacement> replacements = new LinkedHashMap<>();

            CommandContextBuilder<CommandSourceStack> context = results.getContext();
            while (context != null) {
                for (ParsedArgument<CommandSourceStack, ?> argument :
                        context.getArguments().values()) {
                    Replacement replacement = createReplacement(argument);
                    if (replacement == null) continue;
                    replacements.put(
                            replacement.start() + ":" + replacement.end(), replacement);
                }
                context = context.getChild();
            }

            List<Replacement> ordered = new ArrayList<>(replacements.values());
            ordered.sort(Comparator.comparingInt(Replacement::start).reversed());
            StringBuilder result = new StringBuilder(command);
            for (Replacement replacement : ordered) {
                result.replace(
                        replacement.start(), replacement.end(), replacement.value());
            }
            return result.toString();
        } catch (RuntimeException ignored) {
            return command;
        }
    }

    private static Replacement createReplacement(
            ParsedArgument<CommandSourceStack, ?> argument) {
        Object value = argument.getResult();
        String replacement;

        if (value instanceof Component component) {
            Component translated = TranslationUtils.translateLiteral(component);
            if (translated == component) return null;
            replacement = TranslationUtils.translateToJson(translated);
        } else if (value instanceof CompoundTag compound) {
            EntityTagVisitor entityVisitor = new EntityTagVisitor();
            ItemTagVisitor itemVisitor = new ItemTagVisitor();
            compound.accept(entityVisitor);
            compound.accept(itemVisitor);
            boolean changed = new BlockEntityWHandler().handle(compound);
            changed |= entityVisitor.isChanged() || itemVisitor.isChanged();
            if (!changed) return null;
            replacement = compound.toString();
        } else {
            return null;
        }

        return new Replacement(
                argument.getRange().getStart(), argument.getRange().getEnd(), replacement);
    }

    private static boolean endsWithContinuation(String line) {
        return line.stripTrailing().endsWith("\\");
    }

    private static String removeContinuation(String line) {
        String stripped = line.stripTrailing();
        return stripped.substring(0, stripped.length() - 1);
    }

    private record Replacement(int start, int end, String value) {}

    private record ParserContext(Commands commands, CommandSourceStack source) {}
}
