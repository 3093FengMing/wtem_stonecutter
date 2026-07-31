package me.fengming.wtem.common.core.handler.datapack.command;

import com.mojang.serialization.DynamicOps;
import java.util.Comparator;
import java.util.Optional;
import java.util.stream.Collectors;
import me.fengming.wtem.common.core.handler.BlockEntityWHandler;
import me.fengming.wtem.common.core.visitor.ItemTagVisitor;
import me.fengming.wtem.common.util.ResourceIds;
import net.minecraft.commands.arguments.blocks.BlockInput;
import net.minecraft.commands.arguments.item.ItemInput;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.TypedDataComponent;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;

/**
 * Converts parsed structured command arguments back into syntax accepted by Minecraft's parser.
 *
 * @author FengMing
 */
public final class StructuredCommandArgumentAdapter {
    private StructuredCommandArgumentAdapter() {}

    public static Optional<String> translateItem(
            ItemInput input, HolderLookup.Provider registries) {
        Holder<Item> item = input.item;
        DataComponentPatch patch = input.components;
        DynamicOps<Tag> ops = registries.createSerializationContext(NbtOps.INSTANCE);
        Tag encoded =
                DataComponentPatch.CODEC
                        .encodeStart(ops, patch)
                        .getOrThrow(IllegalStateException::new);
        if (!(encoded instanceof CompoundTag components)) {
            throw new IllegalStateException("Item component patch did not encode as a compound tag");
        }

        String itemId =
                item.unwrapKey()
                        .map(ResourceIds::key)
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "Item command argument has no registered item id"));
        ItemTagVisitor visitor = new ItemTagVisitor();
        visitor.visitComponents(itemId, components);
        if (!visitor.isChanged()) return Optional.empty();

        DataComponentPatch translatedPatch =
                DataComponentPatch.CODEC
                        .parse(ops, components)
                        .getOrThrow(IllegalStateException::new);
        return Optional.of(serializeItem(itemId, translatedPatch, ops));
    }

    public static Optional<String> translateBlock(BlockInput input, String sourceArgument) {
        CompoundTag sourceTag = input.tag;
        if (sourceTag == null || sourceTag.isEmpty()) return Optional.empty();

        CompoundTag translatedTag = sourceTag.copy();
        boolean temporaryId = !translatedTag.contains("id");
        if (temporaryId) {
            var blockHolder =
                    //? if >=26.1 {
                    input.getState().typeHolder();
                    //?} else
                    //input.getState().getBlockHolder();
            blockHolder.unwrapKey()
                    .map(ResourceIds::key)
                    .ifPresent(id -> translatedTag.putString("id", id));
        }

        if (!new BlockEntityWHandler().handle(translatedTag)) return Optional.empty();
        if (temporaryId) translatedTag.remove("id");

        int tagStart = sourceArgument.indexOf('{');
        if (tagStart < 0) {
            throw new IllegalStateException("Parsed block argument has NBT but no SNBT source");
        }
        return Optional.of(sourceArgument.substring(0, tagStart) + translatedTag);
    }

    private static String serializeItem(
            String itemId, DataComponentPatch patch, DynamicOps<Tag> ops) {
        StringBuilder result = new StringBuilder(itemId);
        // DataComponentPatch iterates in map order, which is not part of its contract. Sorting by
        // component id keeps repeated extractions of the same function byte-for-byte identical.
        String components =
                patch.entrySet().stream()
                        .map(entry -> serializeComponent(entry.getKey(), entry.getValue(), ops))
                        .sorted(Comparator.comparing(SerializedComponent::id))
                        .map(SerializedComponent::text)
                        .collect(Collectors.joining(","));
        if (!components.isEmpty()) result.append('[').append(components).append(']');
        return result.toString();
    }

    private static SerializedComponent serializeComponent(
            DataComponentType<?> type, Optional<?> value, DynamicOps<Tag> ops) {
        Identifier id = BuiltInRegistries.DATA_COMPONENT_TYPE.getKey(type);
        if (id == null) {
            throw new IllegalStateException("Item component type has no registered id: " + type);
        }
        if (value.isEmpty()) return new SerializedComponent(id.toString(), "!" + id);

        TypedDataComponent<?> component = TypedDataComponent.createUnchecked(type, value.get());
        Tag encoded = component.encodeValue(ops).getOrThrow(IllegalStateException::new);
        return new SerializedComponent(id.toString(), id + "=" + encoded);
    }

    private record SerializedComponent(String id, String text) {}
}
