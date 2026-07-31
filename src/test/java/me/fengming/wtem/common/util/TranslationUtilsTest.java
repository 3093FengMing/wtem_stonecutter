package me.fengming.wtem.common.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.Map;
import me.fengming.wtem.common.core.extraction.TranslationContext;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TranslationUtilsTest {
    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @BeforeEach
    void setUp() {
        TranslationContext.clear();
        TranslationContext.setKey("datapack.test.dialog");
        TranslationContext.setKeepDuplicates(true);
    }

    @AfterEach
    void tearDown() {
        TranslationContext.release();
    }

    @Test
    void namesWildcardKeysAfterTheResolvedIndex() {
        JsonObject json =
                JsonParser.parseString(
                                """
                                {"body":[{"contents":"First"},{"contents":"Second"}]}
                                """)
                        .getAsJsonObject();

        assertTrue(TranslationUtils.translateJsonElement(json, "body[*].contents"));

        assertEquals(
                Map.of(
                        "datapack.test.dialog.body.0.contents", "First",
                        "datapack.test.dialog.body.1.contents", "Second"),
                TranslationContext.snapshot());
    }

    @Test
    void namesExplicitIndexKeysAfterTheIndex() {
        JsonObject json =
                JsonParser.parseString("{\"pages\":[{\"raw\":\"Only\"}]}").getAsJsonObject();

        assertTrue(TranslationUtils.translateJsonElement(json, "pages[0].raw"));

        assertEquals(
                Map.of("datapack.test.dialog.pages.0.raw", "Only"),
                TranslationContext.snapshot());
    }

    @Test
    void stripsNamespacesAndSlashesFromKeySegments() {
        JsonObject json =
                JsonParser.parseString(
                                """
                                {"attributes":{"minecraft:gameplay/bed_rule":{"error_message":"Nope"}}}
                                """)
                        .getAsJsonObject();

        assertTrue(
                TranslationUtils.translateJsonElement(
                        json, "attributes.minecraft:gameplay/bed_rule.error_message"));

        assertEquals(
                Map.of(
                        "datapack.test.dialog.attributes.gameplay.bed_rule.error_message",
                        "Nope"),
                TranslationContext.snapshot());
    }

    @Test
    void leavesMissingPathsUntouched() {
        JsonObject json = JsonParser.parseString("{\"title\":\"Kept\"}").getAsJsonObject();

        assertFalse(TranslationUtils.translateJsonElement(json, "body[*].contents"));

        assertEquals("Kept", json.get("title").getAsString());
        assertEquals(Map.of(), TranslationContext.snapshot());
    }

    @Test
    void rollsBackKeysWhenTheTargetIsNotAComponent() {
        JsonObject json = JsonParser.parseString("{\"title\":{\"unknown\":1}}").getAsJsonObject();

        assertFalse(TranslationUtils.translateJsonElement(json, "title"));

        assertEquals(Map.of(), TranslationContext.snapshot());
        assertEquals("datapack.test.dialog", TranslationContext.getKey());
    }
}
