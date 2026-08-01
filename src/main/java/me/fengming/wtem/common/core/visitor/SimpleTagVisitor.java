package me.fengming.wtem.common.core.visitor;

import net.minecraft.nbt.ByteArrayTag;
import net.minecraft.nbt.ByteTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.EndTag;
import net.minecraft.nbt.FloatTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.nbt.LongTag;
import net.minecraft.nbt.ShortTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.TagVisitor;

/**
 * @author FengMing
 */
@FunctionalInterface
public interface SimpleTagVisitor extends TagVisitor {
    SimpleTagVisitor INSTANCE = (tag) -> {};

    /**
     * Processing a CompoundTag used to represent an object, such as a block entity or entity. The
     * incoming tag should include the key of "id".
     *
     * @param tag the root tag.
     */
    @Override
    void visitCompound(CompoundTag tag);

    @Override
    default void visitString(StringTag tag) {}

    @Override
    default void visitByte(ByteTag tag) {}

    @Override
    default void visitShort(ShortTag tag) {}

    @Override
    default void visitInt(IntTag tag) {}

    @Override
    default void visitLong(LongTag tag) {}

    @Override
    default void visitFloat(FloatTag tag) {}

    @Override
    default void visitDouble(DoubleTag tag) {}

    @Override
    default void visitByteArray(ByteArrayTag tag) {}

    @Override
    default void visitIntArray(IntArrayTag tag) {}

    @Override
    default void visitLongArray(LongArrayTag tag) {}

    @Override
    default void visitList(ListTag tag) {}

    @Override
    default void visitEnd(EndTag tag) {}
}
