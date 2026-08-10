package me.fengming.wtem.common.core.handler.datapack.command;

import com.mojang.brigadier.context.CommandContextBuilder;
import com.mojang.brigadier.context.ParsedArgument;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import me.fengming.wtem.common.core.extraction.TranslationContext;
import me.fengming.wtem.common.core.extraction.pattern.DataPath;
import me.fengming.wtem.common.core.extraction.pattern.ExtractionPatterns;
import me.fengming.wtem.common.util.NbtUtils;
import me.fengming.wtem.common.util.TranslationUtils;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;

/**
 * Applies user command selectors to Brigadier's already parsed argument tree.
 *
 * @author FengMing
 */
final class CommandPatternSupport {
    private CommandPatternSupport() {}

    static MacroArgumentRestorer.Replacement createReplacement(
            CommandContextBuilder<CommandSourceStack> context,
            String argumentName,
            ParsedArgument<CommandSourceStack, ?> argument,
            int replacementStart,
            int replacementEnd,
            String sourceArgument) {
        for (ExtractionPatterns.CommandRule rule : TranslationContext.config().patterns().commands()) {
            if (!matches(rule, context, argumentName, argument)) continue;

            if (rule.kind() == ExtractionPatterns.ValueKind.PLAIN_STRING) {
                String text = plainText(argument.getResult(), sourceArgument);
                if (text.isBlank()) return null;
                try (var ignored = TranslationContext.push(argumentName)) {
                    TranslationContext.addCatalogEntry(text);
                    TranslationContext.recordWarning(
                            "pattern_command_string",
                            "A command argument selected by a plain_string pattern remains unchanged and needs manual review");
                }
                return null;
            }

            String translated = translateComponent(
                    argument,
                    sourceArgument,
                    rule.dataPath(),
                argumentName);
            if (translated == null || translated.equals(sourceArgument)) return null;
            return new MacroArgumentRestorer.Replacement(
                    replacementStart, replacementEnd, translated);
        }
        return null;
    }

    private static boolean matches(
            ExtractionPatterns.CommandRule rule,
            CommandContextBuilder<CommandSourceStack> context,
            String argumentName,
            ParsedArgument<CommandSourceStack, ?> argument) {
        if (!CommandArgumentSupport.containsCommandNode(context, rule.command())) return false;
        for (String literal : rule.literals()) {
            if (!CommandArgumentSupport.containsCommandNode(context, literal)) return false;
        }
        if (!rule.argument().isBlank() && !rule.argument().equals(argumentName)) return false;
        return rule.argumentIndex() <= 0 || argumentPosition(context, argument) == rule.argumentIndex();
    }

    private static int argumentPosition(
            CommandContextBuilder<CommandSourceStack> context,
            ParsedArgument<CommandSourceStack, ?> wanted) {
        Map<String, ParsedArgument<CommandSourceStack, ?>> arguments = new LinkedHashMap<>();
        CommandContextBuilder<CommandSourceStack> current = context;
        while (current != null) {
            arguments.putAll(current.getArguments());
            current = current.getChild();
        }
        List<ParsedArgument<CommandSourceStack, ?>> ordered =
                arguments.values().stream()
                        .distinct()
                        .sorted(Comparator.comparingInt(value -> value.getRange().getStart()))
                        .toList();
        int index = ordered.indexOf(wanted);
        return index < 0 ? -1 : index + 1;
    }

    private static String translateComponent(
            ParsedArgument<CommandSourceStack, ?> argument,
            String sourceArgument,
            DataPath dataPath,
            String keyPath) {
        Object parsed = argument.getResult();
        if (dataPath != null && parsed instanceof Tag tag) {
            Tag translated = translateNbtPath(tag, dataPath, keyPath);
            return translated == null ? null : translated.toString();
        }

        Optional<com.google.gson.JsonElement> json = CommandJsonSupport.parse(sourceArgument);
        if (json.isPresent()) {
            com.google.gson.JsonElement translated = TranslationUtils.translateLiteral(json.get());
            return translated == json.get() ? null : translated.toString();
        }
        if (parsed instanceof Component component) {
            Component translated = TranslationUtils.translateLiteral(component);
            return translated == component ? null : TranslationUtils.translateToJson(translated);
        }
        if (parsed instanceof Tag tag) {
            Tag translated = translateNbtTag(tag, keyPath);
            return translated == null ? null : translated.toString();
        }
        return null;
    }

    private static Tag translateNbtTag(Tag original, String keyPath) {
        CompoundTag wrapper = new CompoundTag();
        wrapper.put("value", original.copy());
        if (!TranslationUtils.translateNbtComponent(wrapper, "value", keyPath)) return null;
        return wrapper.get("value");
    }

    private static Tag translateNbtPath(Tag original, DataPath path, String keyPath) {
        Tag copy = original.copy();
        Tag translated = translateNbtPath(copy, path.segments(), 0, keyPath);
        return translated == copy ? null : translated;
    }

    private static Tag translateNbtPath(
            Tag current,
            List<DataPath.Segment> segments,
            int segmentIndex,
            String keyPath) {
        if (segmentIndex >= segments.size()) {
            Tag translated = translateNbtTag(current, keyPath);
            return translated == null ? current : translated;
        }
        DataPath.Segment segment = segments.get(segmentIndex);
        if (segment instanceof DataPath.KeySegment key) {
            if (!(current instanceof CompoundTag compound)) return current;
            if (!key.wildcard()) {
                Tag child = compound.get(key.name());
                if (child == null) return current;
                Tag translated =
                        translateNbtPath(
                                child,
                                segments,
                                segmentIndex + 1,
                                keyPath + "." + key.name());
                if (translated == child) return current;
                CompoundTag result = compound.copy();
                result.put(key.name(), translated);
                return result;
            }
            CompoundTag result = compound.copy();
            boolean changed = false;
            for (String name : new ArrayList<>(NbtUtils.getKeys(compound))) {
                Tag child = compound.get(name);
                Tag translated =
                        translateNbtPath(
                                child,
                                segments,
                                segmentIndex + 1,
                                keyPath + "." + name);
                if (translated == child) continue;
                result.put(name, translated);
                changed = true;
            }
            return changed ? result : current;
        }

        if (!(segment instanceof DataPath.IndexSegment index) || !(current instanceof ListTag list)) {
            return current;
        }
        if (!index.wildcard()) {
            if (index.index() < 0 || index.index() >= list.size()) return current;
            Tag child = list.get(index.index());
            Tag translated =
                    translateNbtPath(
                            child,
                            segments,
                            segmentIndex + 1,
                            keyPath + "." + index.index());
            if (translated == child) return current;
            ListTag result = list.copy();
            if (!result.setTag(index.index(), translated)) return current;
            return result;
        }

        ListTag result = list.copy();
        boolean changed = false;
        for (int indexValue = 0; indexValue < list.size(); indexValue++) {
            Tag child = list.get(indexValue);
            Tag translated =
                    translateNbtPath(
                            child,
                            segments,
                            segmentIndex + 1,
                            keyPath + "." + indexValue);
            if (translated == child || !result.setTag(indexValue, translated)) continue;
            changed = true;
        }
        return changed ? result : current;
    }

    private static String plainText(Object parsed, String source) {
        if (parsed instanceof StringTag value) return NbtUtils.getStringValue(value);
        if (parsed instanceof String text) return text;
        if (source == null) return "";
        String result = source.trim();
        if (result.length() >= 2
                && ((result.startsWith("\"") && result.endsWith("\""))
                        || (result.startsWith("'") && result.endsWith("'")))) {
            return result.substring(1, result.length() - 1);
        }
        return result;
    }
}
