package me.fengming.wtem.common.core.visitor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
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
 * Covers the visitor itself, as reached from places other than the entity half of a region file:
 * spawner data, bees, and spawn eggs all hand it a bare entity compound.
 */
class EntityTagVisitorTest {
    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @BeforeEach
    void setUp() {
        TranslationContext.clear();
        TranslationContext.setKey("block.mob_spawner");
        TranslationContext.setKeepDuplicates(true);
    }

    @AfterEach
    void tearDown() {
        TranslationContext.release();
    }

    @Test
    void namesAnEntityAfterItsTypeRegardlessOfTheCallerKey() {
        assertEquals(
                Map.of("entity.zombie.1.name", "Walker"),
                visit(
                        """
                        {"id":"minecraft:zombie","CustomName":"{\\"text\\":\\"Walker\\"}"}
                        """));
    }

    @Test
    void fallsBackToUnknownForAnEntityWithoutAnId() {
        assertEquals(
                Map.of("entity.unknown.1.name", "Nameless"),
                visit("{\"CustomName\":\"{\\\"text\\\":\\\"Nameless\\\"}\"}"));
    }

    @Test
    void ignoresAnEmptyCompound() {
        EntityTagVisitor visitor = new EntityTagVisitor();

        new CompoundTag().accept(visitor);

        assertFalse(visitor.isChanged());
        assertEquals(Map.of(), TranslationContext.snapshot());
    }

    @Test
    void reportsNoChangeWhenNothingIsTranslatable() {
        CompoundTag entity = nbt("{\"id\":\"minecraft:zombie\",\"Health\":20.0}");
        EntityTagVisitor visitor = new EntityTagVisitor();

        entity.accept(visitor);

        assertFalse(visitor.isChanged());
        assertEquals(Map.of(), TranslationContext.snapshot());
    }

    @Test
    void countsRepeatedEntityTypesSeparately() {
        EntityTagVisitor visitor = new EntityTagVisitor();
        for (String text : new String[] {"First", "Second"}) {
            nbt(
                            "{\"id\":\"minecraft:zombie\",\"CustomName\":\"{\\\"text\\\":\\\""
                                    + text
                                    + "\\\"}\"}")
                    .accept(visitor);
        }

        assertEquals(
                Map.of(
                        "entity.zombie.1.name", "First",
                        "entity.zombie.2.name", "Second"),
                TranslationContext.snapshot());
    }

    @Test
    void translatesTheExtraTextFieldOnlyForTheOwningEntityType() {
        // The field names overlap across entity types, so a zombie carrying a 'text' field must not
        // be mistaken for a text display.
        assertEquals(
                Map.of("text_display.1.text", "Hint"),
                visit("{\"id\":\"minecraft:text_display\",\"text\":\"{\\\"text\\\":\\\"Hint\\\"}\"}"));

        setUp();
        CompoundTag zombie =
                nbt("{\"id\":\"minecraft:zombie\",\"text\":\"{\\\"text\\\":\\\"Hint\\\"}\"}");
        EntityTagVisitor visitor = new EntityTagVisitor();
        zombie.accept(visitor);
        assertFalse(visitor.isChanged());
    }

    @Test
    void visitsPassengersAndTheirItemsAndTrades() {
        assertEquals(
                Map.of(
                        "entity.boat.1.name", "Ferry",
                        "entity.villager.1.name", "Trader",
                        "item.emerald.1.name", "Cost",
                        "item.stick.1.name", "Wand"),
                visit(
                        """
                        {"id":"minecraft:boat","CustomName":"{\\"text\\":\\"Ferry\\"}",
                         "Passengers":[{"id":"minecraft:villager",
                           "CustomName":"{\\"text\\":\\"Trader\\"}",
                           "equipment":{"mainhand":{"id":"minecraft:stick","components":{
                             "minecraft:custom_name":"{\\"text\\":\\"Wand\\"}"}}},
                           "Offers":{"Recipes":[{"buy":{"id":"minecraft:emerald","components":{
                             "minecraft:custom_name":"{\\"text\\":\\"Cost\\"}"}}}]}}]}
                        """));
    }

    @Test
    void reportsAChangeThatOnlyHappenedInsideANestedItem() {
        // The entity itself has no text, so the change has to travel up from the item visitor.
        assertEquals(
                Map.of("item.stick.1.name", "Wand"),
                visit(
                        """
                        {"id":"minecraft:zombie","Items":[{"id":"minecraft:stick","components":{
                          "minecraft:custom_name":"{\\"text\\":\\"Wand\\"}"}}]}
                        """));
    }

    private static Map<String, String> visit(String json) {
        EntityTagVisitor visitor = new EntityTagVisitor();
        nbt(json).accept(visitor);
        assertTrue(visitor.isChanged(), json);
        return TranslationContext.snapshot();
    }

    private static CompoundTag nbt(String json) {
        CompoundTag tag = NbtUtils.fromJson(JsonParser.parseString(json).getAsJsonObject());
        assertFalse(tag.isEmpty(), json);
        return tag;
    }
}
