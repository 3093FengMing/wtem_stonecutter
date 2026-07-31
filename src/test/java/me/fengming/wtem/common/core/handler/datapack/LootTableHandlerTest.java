package me.fengming.wtem.common.core.handler.datapack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import me.fengming.wtem.common.core.extraction.TranslationContext;
import me.fengming.wtem.common.util.ResourceIds;
import net.minecraft.SharedConstants;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.packs.resources.IoSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LootTableHandlerTest {
    @TempDir Path directory;

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
    void translatesTableLevelFunctions() throws IOException {
        assertTrue(
                handle(
                        """
                        {"type":"chest",
                         "functions":[{"function":"set_name","name":"Prize"}]}
                        """));

        assertEquals(Map.of("datapack.example.chest.set_name", "Prize"), snapshot());
        assertTrue(written().getAsJsonArray("functions")
                .get(0)
                .getAsJsonObject()
                .get("name")
                .isJsonObject());
    }

    @Test
    void translatesPoolFunctionsAndEntryFunctions() throws IOException {
        assertTrue(
                handle(
                        """
                        {"pools":[{
                          "functions":[{"function":"set_name","name":"Pool"}],
                          "entries":[{"type":"item","name":"minecraft:stick",
                            "functions":[{"function":"set_name","name":"Entry"}]}]}]}
                        """));

        assertEquals(
                Map.of(
                        "datapack.example.chest.set_name", "Pool",
                        "datapack.example.chest.set_name.1", "Entry"),
                snapshot());
    }

    @Test
    void recursesThroughEveryCompositeEntryType() {
        for (String type : new String[] {"group", "alternatives", "sequence", "minecraft:group"}) {
            TranslationContext.clear();
            TranslationContext.setKeepDuplicates(true);

            JsonObject entry =
                    json(
                            """
                            {"type":"%s","children":[
                              {"type":"item","name":"minecraft:stick",
                               "functions":[{"function":"set_name","name":"Deep"}]}]}
                            """
                                    .formatted(type));

            LootTableHandler.processLootEntry(entry);

            assertTrue(TranslationContext.snapshot().containsValue("Deep"), type);
        }
    }

    @Test
    void ignoresChildrenOfANonCompositeEntry() {
        JsonObject entry =
                json(
                        """
                        {"type":"item","name":"minecraft:stick","children":[
                          {"type":"item","functions":[{"function":"set_name","name":"Deep"}]}]}
                        """);

        LootTableHandler.processLootEntry(entry);

        // 'children' only carries entries for the composite types, so following it elsewhere would
        // invent a schema the game does not read.
        assertEquals(Map.of(), TranslationContext.snapshot());
    }

    @Test
    void leavesAnEntryWithoutAUsableTypeAlone() {
        for (String source :
                new String[] {
                    "{\"children\":[{\"type\":\"item\","
                            + "\"functions\":[{\"function\":\"set_name\",\"name\":\"Deep\"}]}]}",
                    "{\"type\":[],\"children\":[{\"type\":\"item\","
                            + "\"functions\":[{\"function\":\"set_name\",\"name\":\"Deep\"}]}]}"
                }) {
            TranslationContext.clear();
            TranslationContext.setKeepDuplicates(true);

            LootTableHandler.processLootEntry(json(source));

            assertEquals(Map.of(), TranslationContext.snapshot(), source);
        }
    }

    @Test
    void ignoresForeignNamespaceCompositeTypes() {
        JsonObject entry =
                json(
                        """
                        {"type":"example:group","children":[
                          {"type":"item","functions":[{"function":"set_name","name":"Deep"}]}]}
                        """);

        LootTableHandler.processLootEntry(entry);

        assertEquals(Map.of(), TranslationContext.snapshot());
    }

    @Test
    void leavesATableWithNothingTranslatableAlone() {
        assertFalse(
                handle(
                        """
                        {"type":"chest","pools":[{"rolls":1,
                          "entries":[{"type":"item","name":"minecraft:stick"}]}]}
                        """));

        assertFalse(Files.exists(this.directory.resolve("chest.json")));
        assertEquals(Map.of(), TranslationContext.snapshot());
    }

    @Test
    void toleratesPoolsAndEntriesOfTheWrongShape() {
        assertFalse(handle("{\"pools\":{}}"));
        assertFalse(handle("{\"pools\":[1,\"two\"]}"));
        assertFalse(handle("{\"pools\":[{\"entries\":{}}],\"functions\":{}}"));
    }

    @Test
    void ignoresARootThatIsNotAnObject() {
        assertFalse(handle("[\"not a table\"]"));
    }

    private boolean handle(String contents) {
        return new LootTableHandler(
                        rl -> this.directory.resolve(rl.getPath()),
                        ResourceHandler.Context.of(null, null, null, null))
                .handle(ResourceIds.create("example", "chest.json"), source(contents));
    }

    private JsonObject written() throws IOException {
        return JsonParser.parseString(Files.readString(this.directory.resolve("chest.json")))
                .getAsJsonObject();
    }

    private static Map<String, String> snapshot() {
        return TranslationContext.snapshot();
    }

    private static JsonObject json(String source) {
        return JsonParser.parseString(source).getAsJsonObject();
    }

    private static IoSupplier<InputStream> source(String contents) {
        return () -> new ByteArrayInputStream(contents.getBytes(StandardCharsets.UTF_8));
    }
}
