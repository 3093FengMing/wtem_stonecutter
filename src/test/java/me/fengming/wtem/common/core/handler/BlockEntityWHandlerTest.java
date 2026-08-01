package me.fengming.wtem.common.core.handler;

import static me.fengming.wtem.common.config.ConfigOverride.withSkipped;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import java.util.Map;
import me.fengming.wtem.common.config.WtemConfig;
import me.fengming.wtem.common.core.extraction.TranslationContext;
import me.fengming.wtem.common.core.handler.datapack.command.FunctionHandler;
import me.fengming.wtem.common.util.NbtUtils;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Boundary coverage for the block-entity half of a save file. Fixtures are written as JSON and
 * converted through {@code CompoundTag.CODEC} so the same source works on every supported version.
 */
class BlockEntityWHandlerTest {
    /** A sign whose every line carries both the original text and its chat-filtered form. */
    private static final String FILTERED_SIGN =
            """
            {"id":"minecraft:sign",
             "front_text":{"messages":["{\\"text\\":\\"F0\\"}"],
                           "filtered_messages":["{\\"text\\":\\"F0f\\"}"]},
             "back_text":{"messages":["{\\"text\\":\\"B0\\"}"],
                          "filtered_messages":["{\\"text\\":\\"B0f\\"}"]}}
            """;

    /** A command block holding both a cached output and a command with text of its own. */
    private static final String COMMAND_BLOCK =
            """
            {"id":"minecraft:command_block",
             "LastOutput":"{\\"text\\":\\"Done\\"}",
             "Command":"title @a title {\\"text\\":\\"Welcome\\"}"}
            """;

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
        FunctionHandler.releaseParser();
        TranslationContext.release();
    }

    @Test
    void namesContainerBlockEntitiesUnderTheContainerPrefix() {
        CompoundTag chest = nbt("""
                {"id":"minecraft:chest","CustomName":"{\\"text\\":\\"Loot\\"}"}
                """);

        assertTrue(new BlockEntityWHandler().handle(chest));

        assertEquals(Map.of("container.chest.1.name", "Loot"), TranslationContext.snapshot());
    }

    @Test
    void namesNonContainerBlockEntitiesUnderTheBlockEntityPrefix() {
        CompoundTag beacon = nbt("""
                {"id":"minecraft:beacon","CustomName":"{\\"text\\":\\"Tower\\"}"}
                """);

        assertTrue(new BlockEntityWHandler().handle(beacon));

        assertEquals(Map.of("block_entity.beacon.1.name", "Tower"), TranslationContext.snapshot());
    }

    @Test
    void acceptsTheLowerCaseCustomNameSpelling() {
        CompoundTag chest = nbt("""
                {"id":"minecraft:chest","custom_name":"{\\"text\\":\\"Loot\\"}"}
                """);

        assertTrue(new BlockEntityWHandler().handle(chest));

        assertEquals(Map.of("container.chest.1.name", "Loot"), TranslationContext.snapshot());
    }

    @Test
    void fallsBackToUnknownWhenTheIdIsMissing() {
        CompoundTag tag = nbt("{\"CustomName\":\"{\\\"text\\\":\\\"Nameless\\\"}\"}");

        assertTrue(new BlockEntityWHandler().handle(tag));

        assertEquals(
                Map.of("block_entity.unknown.1.name", "Nameless"), TranslationContext.snapshot());
    }

    @Test
    void countsRepeatedBlockEntityTypesSeparately() {
        BlockEntityWHandler handler = new BlockEntityWHandler();

        assertTrue(handler.handle(nbt("""
                {"id":"minecraft:chest","CustomName":"{\\"text\\":\\"First\\"}"}
                """)));
        assertTrue(handler.handle(nbt("""
                {"id":"minecraft:chest","CustomName":"{\\"text\\":\\"Second\\"}"}
                """)));

        assertEquals(
                Map.of(
                        "container.chest.1.name", "First",
                        "container.chest.2.name", "Second"),
                TranslationContext.snapshot());
    }

    @Test
    void rollsBackWhenNothingIsTranslatable() {
        CompoundTag chest = nbt("{\"id\":\"minecraft:chest\",\"Lock\":\"key\"}");

        assertFalse(new BlockEntityWHandler().handle(chest));

        assertEquals(Map.of(), TranslationContext.snapshot());
    }

    @Test
    void visitsItemsStoredInContainers() {
        CompoundTag chest = nbt("""
                {"id":"minecraft:chest","Items":[
                  {"id":"minecraft:diamond_sword","components":{
                    "minecraft:custom_name":"{\\"text\\":\\"Fang\\"}"}}]}
                """);

        assertTrue(new BlockEntityWHandler().handle(chest));

        assertEquals(
                Map.of("item.diamond_sword.1.name", "Fang"), TranslationContext.snapshot());
    }

    @Test
    void visitsTheSingleItemFieldOfEachSpecialBlockEntity() {
        Map<String, String> fixtures =
                Map.of(
                        "jukebox", "RecordItem",
                        "lectern", "Book",
                        "brushable_block", "item",
                        "decorated_pot", "item");

        fixtures.forEach(
                (id, field) -> {
                    TranslationContext.clear();
                    TranslationContext.setKeepDuplicates(true);
                    CompoundTag tag =
                            nbt(
                                    "{\"id\":\"minecraft:"
                                            + id
                                            + "\",\""
                                            + field
                                            + "\":{\"id\":\"minecraft:paper\",\"components\":{"
                                            + "\"minecraft:custom_name\":\"{\\\"text\\\":\\\"Note\\\"}\"}}}");

                    assertTrue(new BlockEntityWHandler().handle(tag), id);
                    assertEquals(
                            Map.of("item.paper.1.name", "Note"), TranslationContext.snapshot(), id);
                });
    }

    @Test
    void visitsItemsNestedInsideABlockEntityStoredOnAnItem() {
        // A shulker box in a chest: the item visitor hands the block entity data back to this
        // handler, which then visits the items inside it.
        CompoundTag chest = nbt("""
                {"id":"minecraft:chest","Items":[
                  {"id":"minecraft:shulker_box","components":{
                    "minecraft:block_entity_data":{"id":"minecraft:shulker_box","Items":[
                      {"id":"minecraft:stick","components":{
                        "minecraft:custom_name":"{\\"text\\":\\"Wand\\"}"}}]}}}]}
                """);

        assertTrue(new BlockEntityWHandler().handle(chest));

        assertEquals(Map.of("item.stick.1.name", "Wand"), TranslationContext.snapshot());
    }

    @Test
    void coversBothSignSidesAndBothMessageLists() {
        assertTrue(new BlockEntityWHandler().handle(nbt(FILTERED_SIGN)));

        assertEquals(
                Map.of(
                        "sign.1.front_text.0", "F0",
                        "sign.1.front_text.0.filtered", "F0f",
                        "sign.1.back_text.0", "B0",
                        "sign.1.back_text.0.filtered", "B0f"),
                TranslationContext.snapshot());
    }

    @Test
    void leavesFilteredSignMessagesAloneWhenConfigured() {
        withSkipped(
                new WtemConfig.Skipped(false, true),
                () -> {
                    assertTrue(new BlockEntityWHandler().handle(nbt(FILTERED_SIGN)));

                    assertEquals(
                            Map.of(
                                    "sign.1.front_text.0", "F0",
                                    "sign.1.back_text.0", "B0"),
                            TranslationContext.snapshot());
                });
    }

    @Test
    void treatsHangingSignsLikeSigns() {
        CompoundTag sign = nbt("""
                {"id":"minecraft:hanging_sign",
                 "front_text":{"messages":["{\\"text\\":\\"Shop\\"}"]}}
                """);

        assertTrue(new BlockEntityWHandler().handle(sign));

        assertEquals(Map.of("sign.1.front_text.0", "Shop"), TranslationContext.snapshot());
    }

    @Test
    void translatesCommandBlockOutputAndCommand() {
        CompoundTag commandBlock = nbt(COMMAND_BLOCK);

        assertTrue(new BlockEntityWHandler().handle(commandBlock));

        Map<String, String> entries = TranslationContext.snapshot();
        assertEquals("Done", entries.get("command_block.1.last_output"));
        assertEquals("Welcome", entries.get("block.command_block"));
        assertTrue(NbtUtils.getString(commandBlock, "Command").contains("translate"));
    }

    @Test
    void leavesTheCommandBlockOutputAloneWhenConfigured() {
        withSkipped(
                new WtemConfig.Skipped(true, false),
                () -> {
                    CompoundTag commandBlock = nbt(COMMAND_BLOCK);

                    // The command itself still carries text, so the block is still worth rewriting.
                    assertTrue(new BlockEntityWHandler().handle(commandBlock));

                    assertEquals(
                            Map.of("block.command_block", "Welcome"),
                            TranslationContext.snapshot());
                    assertEquals(
                            "{\"text\":\"Done\"}", NbtUtils.getString(commandBlock, "LastOutput"));
                });
    }

    @Test
    void readsBeeEntityDataDirectlyForBothHiveTypes() {
        // BeehiveBlockEntity.Occupant serialises the entity compound straight into 'entity_data',
        // merging the entity id at the top level. There is no nested 'entity' wrapper here.
        for (String hiveId : new String[] {"minecraft:beehive", "minecraft:bee_nest"}) {
            TranslationContext.clear();
            TranslationContext.setKeepDuplicates(true);
            CompoundTag hive = nbt("""
                    {"id":"%s","bees":[
                      {"entity_data":{"id":"minecraft:bee","CustomName":"{\\"text\\":\\"Buzz\\"}"}}]}
                    """.formatted(hiveId));

            assertTrue(new BlockEntityWHandler().handle(hive), hiveId);

            assertEquals(Map.of("entity.bee.1.name", "Buzz"), TranslationContext.snapshot(), hiveId);
        }
    }

    @Test
    void coversBothMobSpawnerEntitySources() {
        CompoundTag spawner = nbt("""
                {"id":"minecraft:mob_spawner",
                 "SpawnData":{"entity":{"id":"minecraft:zombie",
                    "CustomName":"{\\"text\\":\\"Walker\\"}"}},
                 "SpawnPotentials":[{"data":{"entity":{"id":"minecraft:skeleton",
                    "CustomName":"{\\"text\\":\\"Bones\\"}"}}}]}
                """);

        assertTrue(new BlockEntityWHandler().handle(spawner));

        assertEquals(
                Map.of(
                        "entity.zombie.1.name", "Walker",
                        "entity.skeleton.1.name", "Bones"),
                TranslationContext.snapshot());
    }

    @Test
    void coversTrialSpawnerNormalAndOminousConfigurations() {
        CompoundTag spawner = nbt("""
                {"id":"minecraft:trial_spawner",
                 "spawn_data":{"entity":{"id":"minecraft:zombie",
                    "CustomName":"{\\"text\\":\\"Walker\\"}"}},
                 "normal_config":{"spawn_potentials":[{"data":{"entity":{
                    "id":"minecraft:skeleton","CustomName":"{\\"text\\":\\"Bones\\"}"}}}]},
                 "ominous_config":{"spawn_potentials":[{"data":{"entity":{
                    "id":"minecraft:breeze","CustomName":"{\\"text\\":\\"Gust\\"}"}}}]}}
                """);

        assertTrue(new BlockEntityWHandler().handle(spawner));

        assertEquals(
                Map.of(
                        "entity.zombie.1.name", "Walker",
                        "entity.skeleton.1.name", "Bones",
                        "entity.breeze.1.name", "Gust"),
                TranslationContext.snapshot());
    }

    private static CompoundTag nbt(String json) {
        CompoundTag tag = NbtUtils.fromJson(JsonParser.parseString(json).getAsJsonObject());
        assertFalse(tag.isEmpty(), json);
        return tag;
    }
}
