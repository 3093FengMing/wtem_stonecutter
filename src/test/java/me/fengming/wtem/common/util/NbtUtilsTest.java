package me.fengming.wtem.common.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Round-trip coverage for the NBT/JSON conversion helpers. */
class NbtUtilsTest {
    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void encodesAListOfCompoundsAsAnArray() {
        ListTag list = NbtUtils.getList(compound("""
                {"Items":[{"id":"minecraft:stick"},{"id":"minecraft:book"}]}
                """), "Items");

        assertEquals(
                JsonParser.parseString("[{\"id\":\"minecraft:stick\"},{\"id\":\"minecraft:book\"}]"),
                NbtUtils.toJson(list));
    }

    @Test
    void encodesAListOfStringsAsAnArray() {
        ListTag list = NbtUtils.getList(compound("{\"Lore\":[\"first\",\"second\"]}"), "Lore");

        assertEquals(JsonParser.parseString("[\"first\",\"second\"]"), NbtUtils.toJson(list));
    }

    @Test
    void producesAnEmptyArrayForNothingToEncode() {
        assertTrue(NbtUtils.toJson(new ListTag()).isEmpty());
        assertTrue(NbtUtils.toJson((ListTag) null).isEmpty());
    }

    @Test
    void survivesARoundTripThroughACompound() {
        CompoundTag original = compound("""
                {"Items":[{"id":"minecraft:stick","count":2}]}
                """);

        CompoundTag restored = NbtUtils.fromJson(NbtUtils.toJson(original));

        assertEquals(original, restored);
        assertEquals(
                NbtUtils.toJson(NbtUtils.getList(original, "Items")),
                NbtUtils.toJson(NbtUtils.getList(restored, "Items")));
    }

    private static CompoundTag compound(String json) {
        return NbtUtils.fromJson(JsonParser.parseString(json).getAsJsonObject());
    }
}
