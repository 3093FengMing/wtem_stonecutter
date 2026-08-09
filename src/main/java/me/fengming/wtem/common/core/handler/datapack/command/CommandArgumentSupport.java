package me.fengming.wtem.common.core.handler.datapack.command;

import com.mojang.brigadier.context.CommandContextBuilder;
import com.mojang.brigadier.context.ParsedArgument;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import me.fengming.wtem.common.util.NbtUtils;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;

/** Small, schema-neutral helpers for reading values from Brigadier's parsed command tree. */
final class CommandArgumentSupport {
    private CommandArgumentSupport() {}

    static boolean containsCommandNode(
            CommandContextBuilder<CommandSourceStack> context, String name) {
        CommandContextBuilder<CommandSourceStack> current = context;
        while (current != null) {
            if (current.getNodes().stream()
                    .anyMatch(node -> name.equals(node.getNode().getName()))) return true;
            current = current.getChild();
        }
        return false;
    }

    static String argumentText(
            String command, ParsedArgument<CommandSourceStack, ?> argument) {
        return command.substring(argument.getRange().getStart(), argument.getRange().getEnd());
    }

    static ParsedArgument<CommandSourceStack, ?> findArgument(
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

    static Optional<String> findEntityId(
            CommandContextBuilder<CommandSourceStack> context, String command) {
        ParsedArgument<CommandSourceStack, ?> entityArgument = findArgument(context, "entity");
        if (entityArgument == null || command == null || command.isBlank()) {
            return Optional.empty();
        }
        String id = argumentText(command, entityArgument).trim();
        if (id.isBlank()) return Optional.empty();
        return Optional.of(id.indexOf(':') >= 0 ? id : "minecraft:" + id);
    }

    static String storageId(
            String command, ParsedArgument<CommandSourceStack, ?> target) {
        String targetText = argumentText(command, target).trim();
        if (targetText.startsWith("storage ")) {
            return firstToken(targetText.substring("storage ".length()));
        }

        // In command versions where `storage` is a literal node, Brigadier's target argument
        // starts at the identifier itself. Verify the preceding literal instead of guessing a
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

    static Map<String, String> storageValues(Tag tag) {
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

    private static String firstToken(String value) {
        int end = 0;
        while (end < value.length() && !Character.isWhitespace(value.charAt(end))) end++;
        return value.substring(0, end);
    }
}
