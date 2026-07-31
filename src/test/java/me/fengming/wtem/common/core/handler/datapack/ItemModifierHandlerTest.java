package me.fengming.wtem.common.core.handler.datapack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import me.fengming.wtem.common.core.extraction.TranslationContext;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ItemModifierHandlerTest {
    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @BeforeEach
    void setUp() {
        TranslationContext.clear();
        TranslationContext.setKey("datapack.test.item_modifier");
        TranslationContext.setKeepDuplicates(true);
    }

    @AfterEach
    void tearDown() {
        TranslationContext.release();
    }

    @Test
    void translatesUnnamespacedSetName() {
        JsonObject modifier =
                JsonParser.parseString("{\"function\":\"set_name\",\"name\":\"Sword\"}")
                        .getAsJsonObject();

        ItemModifierHandler.processItemModifier(modifier);

        assertTrue(modifier.get("name").isJsonObject(), modifier.toString());
        assertEquals(
                "Sword",
                TranslationContext.snapshot().get("datapack.test.item_modifier.set_name"));
    }

    @Test
    void keysLoreLinesSeparately() {
        JsonObject modifier =
                JsonParser.parseString(
                                "{\"function\":\"minecraft:set_lore\",\"lore\":[\"One\",\"Two\"]}")
                        .getAsJsonObject();

        ItemModifierHandler.processItemModifier(modifier);

        var snapshot = TranslationContext.snapshot();
        assertEquals("One", snapshot.get("datapack.test.item_modifier.set_lore.line0"));
        assertEquals("Two", snapshot.get("datapack.test.item_modifier.set_lore.line1"));
    }

    @Test
    void ignoresForeignNamespaceFunctions() {
        String source = "{\"function\":\"example:set_name\",\"name\":\"Sword\"}";
        JsonObject modifier = JsonParser.parseString(source).getAsJsonObject();

        ItemModifierHandler.processItemModifier(modifier);

        assertEquals(JsonParser.parseString(source), modifier);
        assertTrue(TranslationContext.snapshot().isEmpty());
    }

    @Test
    void leavesSetNameWithoutNameFieldUntouched() {
        String source = "{\"function\":\"minecraft:set_name\"}";
        JsonObject modifier = JsonParser.parseString(source).getAsJsonObject();

        ItemModifierHandler.processItemModifier(modifier);

        assertEquals(JsonParser.parseString(source), modifier);
    }

    @Test
    void leavesSetComponentsWithoutTextUntouched() {
        String source =
                "{\"function\":\"minecraft:set_components\","
                        + "\"components\":{\"minecraft:damage\":1}}";
        JsonObject modifier = JsonParser.parseString(source).getAsJsonObject();

        ItemModifierHandler.processItemModifier(modifier);

        assertEquals(JsonParser.parseString(source), modifier);
        assertTrue(TranslationContext.snapshot().isEmpty());
    }
}
