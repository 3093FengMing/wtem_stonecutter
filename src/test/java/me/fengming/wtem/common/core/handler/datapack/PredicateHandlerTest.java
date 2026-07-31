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

class PredicateHandlerTest {
    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @BeforeEach
    void setUp() {
        TranslationContext.clear();
        TranslationContext.setKey("datapack.test.predicate");
    }

    @AfterEach
    void tearDown() {
        TranslationContext.release();
    }

    @Test
    void translatesMatchToolCustomName() {
        JsonObject predicate =
                JsonParser.parseString(
                                """
                                {"condition":"match_tool","predicate":{"components":{
                                  "minecraft:custom_name":"{\\"text\\":\\"Digger\\"}"}}}
                                """)
                        .getAsJsonObject();

        PredicateHandler.processPredicate(predicate);

        assertTrue(TranslationContext.snapshot().containsValue("Digger"), predicate.toString());
    }

    @Test
    void leavesMatchToolWithoutPredicateUntouched() {
        String source = "{\"condition\":\"minecraft:match_tool\"}";
        JsonObject predicate = JsonParser.parseString(source).getAsJsonObject();

        PredicateHandler.processPredicate(predicate);

        assertEquals(JsonParser.parseString(source), predicate);
    }

    @Test
    void leavesMatchToolWithoutComponentsUntouched() {
        String source = "{\"condition\":\"match_tool\",\"predicate\":{\"count\":1}}";
        JsonObject predicate = JsonParser.parseString(source).getAsJsonObject();

        PredicateHandler.processPredicate(predicate);

        assertEquals(JsonParser.parseString(source), predicate);
    }

    @Test
    void leavesUntranslatableComponentsUntouched() {
        String source =
                "{\"condition\":\"match_tool\",\"predicate\":{\"components\":"
                        + "{\"minecraft:damage\":3}}}";
        JsonObject predicate = JsonParser.parseString(source).getAsJsonObject();

        PredicateHandler.processPredicate(predicate);

        assertEquals(JsonParser.parseString(source), predicate);
        assertTrue(TranslationContext.snapshot().isEmpty());
    }

    @Test
    void leavesCompositeWithoutTermsUntouched() {
        String source = "{\"condition\":\"any_of\"}";
        JsonObject predicate = JsonParser.parseString(source).getAsJsonObject();

        PredicateHandler.processPredicate(predicate);

        assertEquals(JsonParser.parseString(source), predicate);
    }

    @Test
    void ignoresForeignNamespaceConditions() {
        String source =
                "{\"condition\":\"example:match_tool\",\"predicate\":{\"components\":"
                        + "{\"minecraft:custom_name\":\"{\\\"text\\\":\\\"Digger\\\"}\"}}}";
        JsonObject predicate = JsonParser.parseString(source).getAsJsonObject();

        PredicateHandler.processPredicate(predicate);

        assertEquals(JsonParser.parseString(source), predicate);
        assertTrue(TranslationContext.snapshot().isEmpty());
    }
}
