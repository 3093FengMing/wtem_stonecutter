//~ nbt_api

package me.fengming.wtem.common.util;

import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import java.util.Set;
import me.fengming.wtem.common.Wtem;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;

/** Version-independent, type-safe access to NBT values.
 * @author FengMing*/
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
