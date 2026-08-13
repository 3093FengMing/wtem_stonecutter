package me.fengming.wtem.common.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonElement;
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

    @Test
    void parameterizesMacroTextInsteadOfPuttingTheMacroInTheTranslationKey() {
        JsonElement result =
                TranslationUtils.translateLiteral(
                        JsonParser.parseString("{\"text\":\"판매가: $(sale)원\"}"));

        assertEquals(
                "판매가: %s원",
                TranslationContext.snapshot().get("datapack.test.dialog"));
        assertEquals(
                "{\"translate\":\"datapack.test.dialog\",\"with\":[{\"text\":\"$(sale)\"}]}",
                result.toString());
    }

    @Test
    void propagatesChangesFromAnEventOnlyComponent() {
        JsonElement source =
                JsonParser.parseString(
                        "[{\"click_event\":{\"action\":\"run_command\",\"command\":\"/trigger test set 1\"},"
                                + "\"hover_event\":{\"action\":\"show_text\",\"value\":[{\"text\":\"Hover text\"}]}}]");

        JsonElement result = TranslationUtils.translateDecodedTree(source);

        assertFalse(result.equals(source), result::toString);
        assertTrue(result.toString().contains("\"translate\""), result::toString);
        assertEquals(Map.of("datapack.test.dialog", "Hover text"), TranslationContext.snapshot());
    }

    @Test
    void foldsAComponentArgumentBetweenLiteralSiblingsIntoOneTranslation() {
        JsonElement result =
                TranslationUtils.translateLiteral(
                        JsonParser.parseString(
                                "[{\"text\":\"A \"},{\"score\":{\"name\":\"a1\",\"objective\":\"b2\"}},{\"text\":\" B\"}]"));

        assertEquals(
                "A %s B",
                TranslationContext.snapshot().get("datapack.test.dialog"));
        assertEquals(
                "[{\"translate\":\"datapack.test.dialog\",\"with\":[{\"score\":{\"name\":\"a1\",\"objective\":\"b2\"}}]}]",
                result.toString());
    }

    @Test
    void preservesStyleBoundariesAfterCodecRoundTrip() {
        String source =
                "[{\"text\":\"第一位守护者正在\",\"color\":\"#ffffff\",\"shadow_color\":-16777216},"
                        + "{\"text\":\"沙漠\",\"color\":\"#e8f807\",\"shadow_color\":-16777216},"
                        + "{\"text\":\"等待着你。\",\"color\":\"#ffffff\",\"shadow_color\":-16777216}]";
        String result = TranslationUtils.translateLiteral(source, false);

        assertEquals(
                Map.of(
                        "datapack.test.dialog", "第一位守护者正在",
                        "datapack.test.dialog.1", "沙漠",
                        "datapack.test.dialog.2", "等待着你。"),
                TranslationContext.snapshot());

        JsonObject root = JsonParser.parseString(result).getAsJsonObject();
        assertEquals("datapack.test.dialog", root.get("translate").getAsString());
        assertEquals("#FFFFFF", root.get("color").getAsString());
        if (root.has("shadow_color")) {
            assertEquals(-16777216, root.get("shadow_color").getAsInt());
        }

        var extra = root.getAsJsonArray("extra");
        assertEquals(2, extra.size());
        assertEquals(
                "datapack.test.dialog.1",
                extra.get(0).getAsJsonObject().get("translate").getAsString());
        assertEquals("#E8F807", extra.get(0).getAsJsonObject().get("color").getAsString());
        assertEquals(
                "datapack.test.dialog.2",
                extra.get(1).getAsJsonObject().get("translate").getAsString());
        assertEquals("#FFFFFF", extra.get(1).getAsJsonObject().get("color").getAsString());
    }
}
