//~ nbt_api

package me.fengming.wtem.common.util;

import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.Set;
import me.fengming.wtem.common.Wtem;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NumericTag;
import net.minecraft.nbt.StringTag;

/**
 * @author FengMing
 */
public final class NbtUtils {
    private NbtUtils() {}

    public static String getString(CompoundTag compound, String name) {
        if (compound == null || name == null || !(compound.get(name) instanceof StringTag value))
            return "";
        return value.value();
    }

    public static String getString(ListTag list, int index) {
        if (list == null
                || index < 0
                || index >= list.size()
                || !(list.get(index) instanceof StringTag value)) {
            return "";
        }
        return value.value();
    }

    public static CompoundTag getCompound(CompoundTag compound, String name) {
        CompoundTag child = findCompound(compound, name);
        return child == null ? new CompoundTag() : child;
    }

    public static CompoundTag getCompound(ListTag list, int index) {
        if (list == null || index < 0 || index >= list.size()) return new CompoundTag();
        return list.get(index) instanceof CompoundTag child ? child : new CompoundTag();
    }

    public static CompoundTag getCompoundPath(CompoundTag compound, String path) {
        CompoundTag result = findCompoundPath(compound, path);
        return result == null ? new CompoundTag() : result;
    }

    public static ListTag getList(CompoundTag compound, String name) {
        if (compound == null || name == null) return new ListTag();
        return compound.get(name) instanceof ListTag list ? list : new ListTag();
    }

    public static ListTag getList(CompoundTag compound, String name, int elementType) {
        ListTag list = getList(compound, name);
        if (!list.isEmpty() && list.get(0).getId() != elementType) return new ListTag();
        return list;
    }

    public static Set<String> getKeys(CompoundTag compound) {
        return compound == null ? Set.of() : compound.keySet();
    }

    /**
     * Reads a whole number, empty when the field is absent or holds something else.
     *
     * <p>Absence is reported rather than defaulted because the callers use these to describe where
     * something was found, and a missing coordinate has to read as unknown instead of as the origin.
     */
    public static OptionalInt getInt(CompoundTag compound, String name) {
        if (compound == null || name == null || !(compound.get(name) instanceof NumericTag value)) {
            return OptionalInt.empty();
        }
        return OptionalInt.of(value.intValue());
    }

    /** Reads a fractional number from a list, empty when the index is absent or holds something else. */
    public static OptionalDouble getDouble(ListTag list, int index) {
        if (list == null
                || index < 0
                || index >= list.size()
                || !(list.get(index) instanceof NumericTag value)) {
            return OptionalDouble.empty();
        }
        return OptionalDouble.of(value.doubleValue());
    }

    public static CompoundTag fromJson(JsonObject json) {
        var result = CompoundTag.CODEC.parse(JsonOps.INSTANCE, json).result();
        if (result.isEmpty()) {
            Wtem.LOGGER.warn("Couldn't parse JSON to a compound tag: {}", json);
            return new CompoundTag();
        }
        return result.get();
    }

    public static JsonObject toJson(CompoundTag compound) {
        var result = CompoundTag.CODEC.encodeStart(JsonOps.INSTANCE, compound).result();
        if (result.isEmpty()) {
            Wtem.LOGGER.warn("Couldn't encode a compound tag as JSON: {}", compound);
            return new JsonObject();
        }
        return result.get().getAsJsonObject();
    }

    static CompoundTag findCompound(CompoundTag compound, String name) {
        if (compound == null || name == null) return null;
        return compound.get(name) instanceof CompoundTag child ? child : null;
    }

    static CompoundTag findCompoundPath(CompoundTag compound, String path) {
        if (compound == null || path == null) return null;
        if (path.isEmpty()) return compound;

        CompoundTag current = compound;
        for (String name : path.split("\\.")) {
            current = findCompound(current, name);
            if (current == null) return null;
        }
        return current;
    }
}
