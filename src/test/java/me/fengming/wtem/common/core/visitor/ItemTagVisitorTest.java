package me.fengming.wtem.common.core.visitor;

import static me.fengming.wtem.common.config.ConfigOverride.withSkipped;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import java.util.List;
import java.util.Map;
import me.fengming.wtem.common.config.ConfigOverride;
import me.fengming.wtem.common.config.WtemConfig;
import me.fengming.wtem.common.core.extraction.TranslationContext;
import me.fengming.wtem.common.util.NbtUtils;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Boundary coverage for the data components carried by item stacks in a save file. */
class ItemTagVisitorTest {
    /** A book whose one page carries both the original text and its chat-filtered form. */
    private static final String FILTERED_BOOK =
            """
            {"id":"minecraft:written_book","components":{
              "minecraft:custom_name":"{\\"text\\":\\"Diary\\"}",
              "minecraft:written_book_content":{
                "title":{"raw":"Diary"},
                "pages":[{"raw":"{\\"text\\":\\"P0\\"}",
                          "filtered":"{\\"text\\":\\"P0f\\"}"}]}}}
            """;

    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @BeforeEach
    void setUp() {
        TranslationContext.clear();
        TranslationContext.setKey("block.chest");
        TranslationContext.setKeepDuplicates(true);
    }

    @AfterEach
    void tearDown() {
        TranslationContext.release();
    }

    @Test
    void translatesTheThreeTextComponentsOfAnItem() {
        Map<String, String> entries =
                visit(
                        """
                        {"id":"minecraft:stick","components":{
                          "minecraft:custom_name":"{\\"text\\":\\"Wand\\"}",
                          "minecraft:item_name":"{\\"text\\":\\"Rod\\"}",
                          "minecraft:lore":["{\\"text\\":\\"L0\\"}","{\\"text\\":\\"L1\\"}"]}}
                        """);

        assertEquals(
                Map.of(
                        "item.stick.1.name", "Wand",
                        "item.stick.1.item_name", "Rod",
                        "item.stick.1.lore.line0", "L0",
                        "item.stick.1.lore.line1", "L1"),
                entries);
    }

    @Test
    void skipsItemsWithoutAnId() {
        CompoundTag item = nbt("""
                {"components":{"minecraft:custom_name":"{\\"text\\":\\"Wand\\"}"}}
                """);
        ItemTagVisitor visitor = new ItemTagVisitor();

        item.accept(visitor);

        assertFalse(visitor.isChanged());
        assertEquals(Map.of(), TranslationContext.snapshot());
    }

    @Test
    void reportsNoChangeForItemsWithoutTranslatableComponents() {
        CompoundTag item = nbt("""
                {"id":"minecraft:stick","components":{"minecraft:damage":3}}
                """);
        ItemTagVisitor visitor = new ItemTagVisitor();

        item.accept(visitor);

        assertFalse(visitor.isChanged());
        assertEquals(Map.of(), TranslationContext.snapshot());
    }

    @Test
    void countsRepeatedItemTypesSeparately() {
        ItemTagVisitor visitor = new ItemTagVisitor();
        for (String text : List.of("First", "Second")) {
            nbt(
                            "{\"id\":\"minecraft:stick\",\"components\":{"
                                    + "\"minecraft:custom_name\":\"{\\\"text\\\":\\\""
                                    + text
                                    + "\\\"}\"}}")
                    .accept(visitor);
        }

        assertEquals(
                Map.of(
                        "item.stick.1.name", "First",
                        "item.stick.2.name", "Second"),
                TranslationContext.snapshot());
    }

    @Test
    void translatesBothTheRawAndTheFilteredFormOfABookPage() {
        Map<String, String> entries = visit(FILTERED_BOOK);

        assertEquals(
                Map.of(
                        "item.written_book.1.name", "Diary",
                        "book.1.content.page0", "P0",
                        "book.1.content.page0.filtered", "P0f"),
                entries);
    }

    @Test
    void leavesFilteredBookPagesAloneWhenConfigured() {
        withSkipped(new WtemConfig.Skipped(false, true), () ->
                assertEquals(
                        Map.of(
                                "item.written_book.1.name", "Diary",
                                "book.1.content.page0", "P0"),
                        visit(FILTERED_BOOK)));
    }

    @Test
    void leavesBookAndQuillPagesUntranslated() {
        // writable_book_content pages are plain strings, so they cannot carry a translatable node.
        CompoundTag item = nbt("""
                {"id":"minecraft:writable_book","components":{
                  "minecraft:writable_book_content":{"pages":[{"raw":"Notes"}]}}}
                """);
        ItemTagVisitor visitor = new ItemTagVisitor();

        item.accept(visitor);

        assertFalse(visitor.isChanged());
        assertEquals(Map.of(), TranslationContext.snapshot());
    }

    @Test
    void derivesACustomNameFromTheBookTitleWhenThereIsNone() {
        CompoundTag item = nbt("""
                {"id":"minecraft:written_book","components":{
                  "minecraft:written_book_content":{"title":{"raw":"Diary"},"pages":[]}}}
                """);

        ItemTagVisitor visitor = new ItemTagVisitor();
        item.accept(visitor);

        assertTrue(visitor.isChanged());
        assertEquals(Map.of("book.1.title", "Diary"), TranslationContext.snapshot());
        CompoundTag components = NbtUtils.getCompound(item, "components");
        assertTrue(components.contains("minecraft:custom_name"));
    }

    @Test
    void translatesAttributeModifierDisplayNames() {
        Map<String, String> entries =
                visit(
                        """
                        {"id":"minecraft:stick","components":{
                          "minecraft:attribute_modifiers":[
                            {"id":"minecraft:base","type":"minecraft:attack_damage","amount":1.0,
                             "operation":"add_value",
                             "display":{"type":"override","value":"{\\"text\\":\\"Sharp\\"}"}}]}}
                        """);

        assertEquals(
                Map.of("item.stick.1.attribute_modifier.0.display", "Sharp"), entries);
    }

    @Test
    void visitsItemsNestedInEveryItemCarryingComponent() {
        assertEquals(
                Map.of("item.stick.1.name", "Wand"),
                visit(
                        """
                        {"id":"minecraft:bundle","components":{"minecraft:bundle_contents":[
                          {"id":"minecraft:stick","components":{
                            "minecraft:custom_name":"{\\"text\\":\\"Wand\\"}"}}]}}
                        """));

        setUp();
        assertEquals(
                Map.of("item.arrow.1.name", "Bolt"),
                visit(
                        """
                        {"id":"minecraft:crossbow","components":{
                          "minecraft:charged_projectiles":[{"id":"minecraft:arrow","components":{
                            "minecraft:custom_name":"{\\"text\\":\\"Bolt\\"}"}}]}}
                        """));

        setUp();
        assertEquals(
                Map.of("item.bowl.1.name", "Dish"),
                visit(
                        """
                        {"id":"minecraft:mushroom_stew","components":{
                          "minecraft:use_remainder":{"id":"minecraft:bowl","components":{
                            "minecraft:custom_name":"{\\"text\\":\\"Dish\\"}"}}}}
                        """));

        setUp();
        assertEquals(
                Map.of("item.stick.1.name", "Wand"),
                visit(
                        """
                        {"id":"minecraft:chest","components":{"minecraft:container":[
                          {"slot":0,"item":{"id":"minecraft:stick","components":{
                            "minecraft:custom_name":"{\\"text\\":\\"Wand\\"}"}}}]}}
                        """));
    }

    @Test
    void visitsEntityDataStoredOnItems() {
        assertEquals(
                Map.of("entity.zombie.1.name", "Walker"),
                visit(
                        """
                        {"id":"minecraft:zombie_spawn_egg","components":{
                          "minecraft:entity_data":{"id":"minecraft:zombie",
                            "CustomName":"{\\"text\\":\\"Walker\\"}"}}}
                        """));

        setUp();
        assertEquals(
                Map.of("entity.cod.1.name", "Fish"),
                visit(
                        """
                        {"id":"minecraft:cod_bucket","components":{
                          "minecraft:bucket_entity_data":{"id":"minecraft:cod",
                            "CustomName":"{\\"text\\":\\"Fish\\"}"}}}
                        """));

        setUp();
        assertEquals(
                Map.of("entity.bee.1.name", "Buzz"),
                visit(
                        """
                        {"id":"minecraft:beehive","components":{"minecraft:bees":[
                          {"entity_data":{"id":"minecraft:bee",
                            "CustomName":"{\\"text\\":\\"Buzz\\"}"}}]}}
                        """));
    }

    @Test
    void rebuildsTheKeyOfANestedBlockEntityByDefault() {
        // A shulker box in a chest names itself from scratch, so the same box produces the same key
        // no matter where it was found.
        assertEquals(
                Map.of("container.shulker_box.1.name", "Loot"),
                visit(
                        """
                        {"id":"minecraft:shulker_box","components":{
                          "minecraft:block_entity_data":{"id":"minecraft:shulker_box",
                            "CustomName":"{\\"text\\":\\"Loot\\"}"}}}
                        """));
    }

    @Test
    void extendsTheItemKeyForANestedBlockEntityWhenConfigured() {
        ConfigOverride.run(
                new WtemConfig(
                        Map.of(),
                        Map.of(),
                        WtemConfig.KeyReuse.DEFAULT,
                        WtemConfig.KeyNaming.DEFAULT,
                        WtemConfig.DEFAULT_NBT_MAX_DEPTH,
                        false,
                        WtemConfig.Skipped.DEFAULT,
                        Map.of(),
                        WtemConfig.DEFAULT_LANGUAGE_FILE),
                () ->
                        assertEquals(
                                Map.of("item.shulker_box.1.container.shulker_box.1.name", "Loot"),
                                visit(
                                        """
                                        {"id":"minecraft:shulker_box","components":{
                                          "minecraft:block_entity_data":{"id":"minecraft:shulker_box",
                                            "CustomName":"{\\"text\\":\\"Loot\\"}"}}}
                                        """)));
    }

    @Test
    void keepsTheCallerKeyForComponentPatchesWithoutAnOwningItem() {
        CompoundTag components = nbt("""
                {"minecraft:custom_name":"{\\"text\\":\\"Digger\\"}"}
                """);

        ItemTagVisitor visitor = new ItemTagVisitor();
        visitor.visitComponents(components);

        assertTrue(visitor.isChanged());
        assertEquals(Map.of("block.chest.name", "Digger"), TranslationContext.snapshot());
    }

    @Test
    void usesTheItemKeyStrategyForKeyedComponentPatches() {
        CompoundTag components = nbt("""
                {"minecraft:custom_name":"{\\"text\\":\\"Digger\\"}"}
                """);

        ItemTagVisitor visitor = new ItemTagVisitor();
        visitor.visitComponents("minecraft:diamond_pickaxe", components);

        assertTrue(visitor.isChanged());
        assertEquals(
                Map.of("item.diamond_pickaxe.1.name", "Digger"), TranslationContext.snapshot());
    }

    @Test
    void namesUnidentifiedKeyedPatchesUnknown() {
        CompoundTag components = nbt("""
                {"minecraft:custom_name":"{\\"text\\":\\"Digger\\"}"}
                """);

        ItemTagVisitor visitor = new ItemTagVisitor();
        visitor.visitComponents("", components);

        assertEquals(Map.of("item.unknown.1.name", "Digger"), TranslationContext.snapshot());
    }

    @Test
    void ignoresEmptyComponentPatches() {
        ItemTagVisitor visitor = new ItemTagVisitor();

        visitor.visitComponents(new CompoundTag());
        visitor.visitComponents("minecraft:stick", new CompoundTag());
        new CompoundTag().accept(visitor);

        assertFalse(visitor.isChanged());
        assertEquals(Map.of(), TranslationContext.snapshot());
    }

    private static Map<String, String> visit(String json) {
        ItemTagVisitor visitor = new ItemTagVisitor();
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
