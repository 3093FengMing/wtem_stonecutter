package me.fengming.wtem.common.core.handler;

import static me.fengming.wtem.common.config.ConfigOverride.withSkipped;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import java.util.Map;
import me.fengming.wtem.common.config.WtemConfig;
import me.fengming.wtem.common.core.extraction.TranslationContext;
import me.fengming.wtem.common.core.extraction.manifest.ExtractionRecord;
import me.fengming.wtem.common.core.handler.datapack.command.FunctionHandler;
import me.fengming.wtem.common.util.NbtUtils;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.TagParser;
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
        WtemConfig.initialize(new WtemConfig(
            WtemConfig.DEFAULT.stages(),
            WtemConfig.DEFAULT.resources(),
            WtemConfig.DEFAULT.keyReuse(),
            WtemConfig.DEFAULT.keyNaming(),
            WtemConfig.DEFAULT.nbtMaxDepth(),
            WtemConfig.DEFAULT.rebuildNestedKeys(),
            new WtemConfig.Skipped(false, true),
            WtemConfig.DEFAULT.skippedPaths(),
            WtemConfig.DEFAULT.builtinEntries(),
            WtemConfig.DEFAULT.languageFile()));
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
    void visitsItemsStoredByTheModernShelfBlockEntity() {
        CompoundTag shelf = nbt("""
                {"id":"minecraft:shelf","x":12,"y":64,"z":-4,"Items":[
                  {"id":"minecraft:stick","Slot":0b,"components":{
                    "minecraft:custom_name":"{\\"text\\":\\"Shelf item one\\"}"}},
                  {"id":"minecraft:paper","Slot":1b,"components":{
                    "minecraft:item_name":"{\\"text\\":\\"Shelf item two\\"}"}}
                ]}
                """);

        assertTrue(new BlockEntityWHandler().handle(shelf));

        assertEquals(
                Map.of(
                        "item.stick.1.name", "Shelf item one",
                        "item.paper.1.item_name", "Shelf item two"),
                TranslationContext.snapshot());
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
    void catalogsWritableBookPagesStoredInALecternWithoutCorruptingThem() {
        // Regression fixture derived from:
        // /setblock 2 0 53 minecraft:lectern[...] {Book:{components:{...}}}
        String firstPage =
                " 붕어빵 기계는 우클릭을 하여 반죽을 구울 수 있다.\n\n"
                        + " 왼쪽 흰색 종을 우클릭하여 장사를 시작할 수 있다.\n"
                        + "붕어빵을 15번 팔면 장사가 종료되며 쉴 수 있다.\n\n"
                        + " 흰색 종 옆 책을 우클릭 하여 현재 메뉴를 볼 수 있다.\n\n"
                        + " 노란색 종을 우클릭하여 메뉴를 거절할 수 있다.";
        String secondPage =
                "[ 붕어빵 결합 ]\n\n 종 옆에 있는 화로를 활용하여 붕어빵을 제조 할 수 있다."
                        + "\n\n 화로 위에 붕어빵 1개를 던지고 알맞는 아이템을 던지면 새로운 붕어빵"
                        + " 반죽이 완성된다.\n\nex) 붕어빵 1개+슈크림 1개";
        CompoundTag lectern =
                nbt(
                        """
                        {"id":"minecraft:lectern","Book":{
                          "components":{"minecraft:writable_book_content":{"pages":[
                            {"raw":" 붕어빵 기계는 우클릭을 하여 반죽을 구울 수 있다.\\n\\n 왼쪽 흰색 종을 우클릭하여 장사를 시작할 수 있다.\\n붕어빵을 15번 팔면 장사가 종료되며 쉴 수 있다.\\n\\n 흰색 종 옆 책을 우클릭 하여 현재 메뉴를 볼 수 있다.\\n\\n 노란색 종을 우클릭하여 메뉴를 거절할 수 있다."},
                            {"raw":"[ 붕어빵 결합 ]\\n\\n 종 옆에 있는 화로를 활용하여 붕어빵을 제조 할 수 있다.\\n\\n 화로 위에 붕어빵 1개를 던지고 알맞는 아이템을 던지면 새로운 붕어빵 반죽이 완성된다.\\n\\nex) 붕어빵 1개+슈크림 1개"}
                          ]}},"count":1,"id":"minecraft:writable_book"},"Page":1}
                        """);

        // Nothing in the world can be rewritten: writable pages only accept plain strings.
        assertFalse(new BlockEntityWHandler().handle(lectern));

        assertEquals(
                Map.of(
                        "writable_book.1.content.page0", firstPage,
                        "writable_book.1.content.page1", secondPage),
                TranslationContext.snapshot());
        assertTrue(TranslationContext.records().stream().noneMatch(ExtractionRecord::replaced));

        CompoundTag book = NbtUtils.getCompound(lectern, "Book");
        CompoundTag content =
                NbtUtils.getCompound(
                        NbtUtils.getCompound(book, "components"),
                        "minecraft:writable_book_content");
        assertEquals(
                firstPage,
                NbtUtils.getString(NbtUtils.getCompound(NbtUtils.getList(content, "pages"), 0), "raw"));
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

        assertEquals(
                Map.of("item.shulker_box.1.item.stick.1.name", "Wand"),
                TranslationContext.snapshot());
    }

    @Test
    void coversBothSignSidesAndBothMessageLists() {
        withSkipped(new WtemConfig.Skipped(true, false), () -> {
            assertTrue(new BlockEntityWHandler().handle(nbt(FILTERED_SIGN)));

            assertEquals(
                Map.of(
                    "sign.1.front_text.0", "F0",
                    "sign.1.front_text.0.filtered", "F0f",
                    "sign.1.back_text.0", "B0",
                    "sign.1.back_text.0.filtered", "B0f"),
                TranslationContext.snapshot());
        });
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
    void writesSignTranslationsAsNbtComponentsRatherThanJsonInsideStrings() {
        String command =
                "setblock -7 17 6 minecraft:birch_wall_sign[facing=west,waterlogged=false]"
                        + "{back_text:{color:\"black\",has_glowing_text:0b,messages:[\"\",\"\",\"\",\"\"]},"
                        + "components:{},front_text:{color:\"black\",has_glowing_text:1b,messages:[\"\",\"Milk purchase (5)\",\"[ 1500 ]\",\"\"]},"
                        + "is_waxed:0b}";

        String result = FunctionHandler.processFunction(command);

        assertTrue(result.contains("translate"), result);
        //? if >=1.21.5 {
        assertFalse(result.contains("'{\"translate\":"), result);
        assertFalse(result.contains("\"{\\\"translate\\\":\""), result);
        //?}
        assertEquals(
                Map.of(
                        "sign.1.front_text.1", "Milk purchase (5)",
                        "sign.1.front_text.2", "[ 1500 ]"),
                TranslationContext.snapshot());
    }

    @Test
    void keepsSerializedEmptySignLinesBlank() throws CommandSyntaxException {
        TranslationContext.setBuiltinEntries(WtemConfig.DEFAULT.builtinEntries());
        String command =
                "setblock 1182 71 1045 minecraft:birch_sign[rotation=10,waterlogged=false]"
                        + "{back_text:{color:\"black\",has_glowing_text:0b,messages:['\"\"','\"\"','\"\"','\"\"']},"
                        + "front_text:{color:\"red\",has_glowing_text:1b,messages:['\"\"','\"\u041a \u0412 \u0410 \u0421\"','\"-----------}\"','\"\"']},"
                        + "is_waxed:0b}";

        String result = FunctionHandler.processFunction(command);

        Map<String, String> entries = TranslationContext.snapshot();
        assertEquals(2, TranslationContext.extractedEntryCount(), entries::toString);
        assertEquals("", entries.get("wtem.blank"));
        assertEquals("\u041a \u0412 \u0410 \u0421", entries.get("sign.1.front_text.1"));
        assertEquals("-----------}", entries.get("sign.1.front_text.2"));
        assertFalse(entries.containsValue("\"\""), entries::toString);
        assertFalse(result.contains("sign.1.front_text.0"), result);
        assertFalse(result.contains("sign.1.front_text.3"), result);

        int tagStart = result.indexOf('{');
        assertTrue(tagStart >= 0, result);
        //~ if >=1.21.5 '.parseTag' -> '.parseCompoundFully'
        CompoundTag translated = TagParser.parseCompoundFully(result.substring(tagStart));
        assertSerializedBlankLines(translated, "back_text", 0, 1, 2, 3);
        assertSerializedBlankLines(translated, "front_text", 0, 3);
    }

    private static void assertSerializedBlankLines(
            CompoundTag sign, String side, int... indexes) {
        ListTag messages = NbtUtils.getList(NbtUtils.getCompound(sign, side), "messages");
        for (int index : indexes) {
            assertEquals("\"\"", NbtUtils.getString(messages, index), sign::toString);
        }
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
