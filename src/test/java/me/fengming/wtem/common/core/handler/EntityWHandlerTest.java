package me.fengming.wtem.common.core.handler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import java.util.List;
import java.util.Map;
import me.fengming.wtem.common.core.extraction.TranslationContext;
import me.fengming.wtem.common.util.NbtUtils;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Boundary coverage for the entity half of a save file, including nested items and trades.
 */
class EntityWHandlerTest {
    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @BeforeEach
    void setUp() {
        TranslationContext.clear();
        TranslationContext.setKeepDuplicates(true);
    }

    @AfterEach
    void tearDown() {
        TranslationContext.release();
    }

    @Test
    void namesEntitiesAfterTheirType() {
        CompoundTag zombie = nbt("""
                {"id":"minecraft:zombie","CustomName":"{\\"text\\":\\"Walker\\"}"}
                """);

        assertTrue(new EntityWHandler().handle(zombie));

        assertEquals(Map.of("entity.zombie.1.name", "Walker"), TranslationContext.snapshot());
    }

    @Test
    void fallsBackToUnknownWhenTheIdIsMissing() {
        CompoundTag tag = nbt("{\"CustomName\":\"{\\\"text\\\":\\\"Nameless\\\"}\"}");

        assertTrue(new EntityWHandler().handle(tag));

        assertEquals(Map.of("entity.unknown.1.name", "Nameless"), TranslationContext.snapshot());
    }

    @Test
    void rollsBackWhenNothingIsTranslatable() {
        CompoundTag zombie = nbt("{\"id\":\"minecraft:zombie\",\"Health\":20.0}");

        assertFalse(new EntityWHandler().handle(zombie));

        assertEquals(Map.of(), TranslationContext.snapshot());
    }

    @Test
    void translatesTheExtraTextFieldOfSpecialEntities() {
        assertEquals(
                Map.of("text_display.1.text", "Hint"),
                extractFresh(
                        """
                        {"id":"minecraft:text_display","text":"{\\"text\\":\\"Hint\\"}"}
                        """));
        assertEquals(
                Map.of("command_block_minecart.1.last_output", "Done"),
                extractFresh(
                        """
                        {"id":"minecraft:command_block_minecart",
                         "LastOutput":"{\\"text\\":\\"Done\\"}"}
                        """));
        assertEquals(
                Map.of("mannequin.1.description", "Statue"),
                extractFresh(
                        """
                        {"id":"minecraft:mannequin","description":"{\\"text\\":\\"Statue\\"}"}
                        """));
    }

    @Test
    void visitsNestedPassengersRecursively() {
        CompoundTag boat = nbt("""
                {"id":"minecraft:boat","CustomName":"{\\"text\\":\\"Ferry\\"}",
                 "Passengers":[{"id":"minecraft:villager",
                    "CustomName":"{\\"text\\":\\"Rider\\"}",
                    "Passengers":[{"id":"minecraft:parrot",
                       "CustomName":"{\\"text\\":\\"Polly\\"}"}]}]}
                """);

        assertTrue(new EntityWHandler().handle(boat));

        assertEquals(
                Map.of(
                        "entity.boat.1.name", "Ferry",
                        "entity.villager.1.name", "Rider",
                        "entity.parrot.1.name", "Polly"),
                TranslationContext.snapshot());
    }

    @Test
    void visitsEveryModernEquipmentSlot() {
        CompoundTag zombie = nbt("""
                {"id":"minecraft:zombie","equipment":{
                  "mainhand":{"id":"minecraft:stick","components":{
                    "minecraft:custom_name":"{\\"text\\":\\"Wand\\"}"}},
                  "head":{"id":"minecraft:leather_helmet","components":{
                    "minecraft:custom_name":"{\\"text\\":\\"Cap\\"}"}}}}
                """);

        assertTrue(new EntityWHandler().handle(zombie));

        assertEquals(
                Map.of(
                        "item.stick.1.name", "Wand",
                        "item.leather_helmet.1.name", "Cap"),
                TranslationContext.snapshot());
    }

    @Test
    void visitsEveryLegacyItemListField() {
        for (String field : List.of("Items", "Inventory", "ArmorItems", "HandItems")) {
            Map<String, String> entries =
                    extractFresh(
                            "{\"id\":\"minecraft:zombie\",\""
                                    + field
                                    + "\":[{\"id\":\"minecraft:stick\",\"components\":{"
                                    + "\"minecraft:custom_name\":\"{\\\"text\\\":\\\"Wand\\\"}\"}}]}");

            assertEquals(Map.of("item.stick.1.name", "Wand"), entries, field);
        }
    }

    @Test
    void visitsEverySingleItemField() {
        for (String field :
                List.of("Item", "item", "FireworksItem", "SaddleItem", "weapon", "body_armor_item")) {
            Map<String, String> entries =
                    extractFresh(
                            "{\"id\":\"minecraft:zombie\",\""
                                    + field
                                    + "\":{\"id\":\"minecraft:stick\",\"components\":{"
                                    + "\"minecraft:custom_name\":\"{\\\"text\\\":\\\"Wand\\\"}\"}}}");

            assertEquals(Map.of("item.stick.1.name", "Wand"), entries, field);
        }
    }

    @Test
    void visitsAllThreeTradeSlots() {
        CompoundTag villager = nbt("""
                {"id":"minecraft:villager","Offers":{"Recipes":[{
                  "buy":{"id":"minecraft:emerald","components":{
                    "minecraft:custom_name":"{\\"text\\":\\"Cost\\"}"}},
                  "buyB":{"id":"minecraft:paper","components":{
                    "minecraft:custom_name":"{\\"text\\":\\"Extra\\"}"}},
                  "sell":{"id":"minecraft:book","components":{
                    "minecraft:custom_name":"{\\"text\\":\\"Reward\\"}"}}}]}}
                """);

        assertTrue(new EntityWHandler().handle(villager));

        assertEquals(
                Map.of(
                        "item.emerald.1.name", "Cost",
                        "item.paper.1.name", "Extra",
                        "item.book.1.name", "Reward"),
                TranslationContext.snapshot());
    }

    @Test
    void stopsRecursingBeyondTheTraversalDepthLimit() {
        // The guard allows 32 nested visits. Each passenger consumes one level, so an entity chain
        // longer than the limit must leave its deepest links untouched instead of overflowing.
        StringBuilder json = new StringBuilder();
        int depth = 40;
        for (int i = 0; i < depth; i++) {
            json.append("{\"id\":\"minecraft:zombie\",\"CustomName\":\"{\\\"text\\\":\\\"Level")
                    .append(i)
                    .append("\\\"}\",\"Passengers\":[");
        }
        json.append("{\"id\":\"minecraft:parrot\"}");
        json.append("]}".repeat(depth));

        assertTrue(new EntityWHandler().handle(nbt(json.toString())));

        Map<String, String> entries = TranslationContext.snapshot();
        assertTrue(entries.containsValue("Level0"), entries::toString);
        assertTrue(entries.containsValue("Level31"), entries::toString);
        assertFalse(entries.containsValue("Level32"), entries::toString);
        assertEquals(32, entries.size(), entries::toString);
    }

    /** Extracts one fixture in isolation, so per-type counters start from 1 for each case. */
    private static Map<String, String> extractFresh(String json) {
        TranslationContext.clear();
        TranslationContext.setKeepDuplicates(true);
        assertTrue(new EntityWHandler().handle(nbt(json)), json);
        return TranslationContext.snapshot();
    }

    private static CompoundTag nbt(String json) {
        CompoundTag tag = NbtUtils.fromJson(JsonParser.parseString(json).getAsJsonObject());
        assertFalse(tag.isEmpty(), json);
        return tag;
    }
}
