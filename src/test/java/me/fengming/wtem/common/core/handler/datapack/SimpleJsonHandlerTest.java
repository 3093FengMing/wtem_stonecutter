package me.fengming.wtem.common.core.handler.datapack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import me.fengming.wtem.common.core.extraction.TranslationContext;
import me.fengming.wtem.common.config.WtemConfig;
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

class SimpleJsonHandlerTest {
    @TempDir Path directory;

    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @BeforeEach
    void setUp() {
        TranslationContext.clear();
    }

    @AfterEach
    void restoreConfig() {
        WtemConfig.initialize(WtemConfig.DEFAULT);
    }

    @AfterEach
    void tearDown() {
        TranslationContext.release();
    }

    @Test
    void translatesEveryConfiguredTargetPath() throws IOException {
        SimpleJsonHandler handler = handler("display.title", "display.description");

        assertTrue(
                handler.handle(
                        id("advancement.json"),
                        source(
                                """
                                {"display":{"title":{"text":"Stone Age"},
                                            "description":{"text":"Mine stone"}}}
                                """)));

        assertEquals(
                Map.of(
                        "datapack.example.advancement.display.title", "Stone Age",
                        "datapack.example.advancement.display.description", "Mine stone"),
                TranslationContext.snapshot());
        JsonObject written = read(id("advancement.json"));
        assertEquals(
                "datapack.example.advancement.display.title",
                written.getAsJsonObject("display")
                        .getAsJsonObject("title")
                        .get("translate")
                        .getAsString());
    }

    @Test
    void keysWildcardElementsByTheirIndex() {
        SimpleJsonHandler handler = handler("body[*].contents");

        assertTrue(
                handler.handle(
                        id("dialog.json"),
                        source(
                                """
                                {"body":[{"contents":{"text":"First"}},
                                         {"contents":{"text":"Second"}}]}
                                """)));

        assertEquals(
                Map.of(
                        "datapack.example.dialog.body.0.contents", "First",
                        "datapack.example.dialog.body.1.contents", "Second"),
                TranslationContext.snapshot());
        assertTrue(Files.exists(this.directory.resolve("dialog.json")));
    }

    @Test
    void extractsDialogDescriptionsAtEverySupportedLevel() {
        SimpleJsonHandler handler =
                handler(
                        "description",
                        "body[*].description",
                        "inputs[*].description",
                        "actions[*].description");

        assertTrue(
                handler.handle(
                        id("dialog.json"),
                        source(
                                """
                                {"description":{"text":"Dialog description"},
                                 "body":[{"description":{"text":"Body description"}}],
                                 "inputs":[{"description":{"text":"Input description"}}],
                                 "actions":[{"description":{"text":"Action description"}}]}
                                """)));

        assertEquals(
                Map.of(
                        "datapack.example.dialog.description", "Dialog description",
                        "datapack.example.dialog.body.0.description", "Body description",
                        "datapack.example.dialog.inputs.0.description", "Input description",
                        "datapack.example.dialog.actions.0.description", "Action description"),
                TranslationContext.snapshot());
    }

    @Test
    void extractsDialogComponentArraysAndActionEvents() throws IOException {
        SimpleJsonHandler handler =
                handler(
                        "description",
                        "body[*].contents",
                        "actions[*].label",
                        "actions[*].action");

        assertTrue(
                handler.handle(
                        id("dialog.json"),
                        source(
                                """
                                {"description":{"type":"minecraft:plain_message","contents":[{"text":"Description"}]},
                                 "body":[{"contents":[{"text":"First body"},{"text":"Second body"}]}],
                                 "actions":[{"label":{"text":"Label","hoverEvent":{"action":"show_text","contents":{"text":"Hover"}},"clickEvent":{"action":"run_command","command":"title @s title {\\"text\\":\\"Click command\\"}"}},"action":{"type":"run_command","command":"title @s title {\\"text\\":\\"Action command\\"}"}}]}
                                """)));

        assertTrue(TranslationContext.snapshot().containsValue("Description"));
        assertTrue(TranslationContext.snapshot().containsValue("First bodySecond body"));
        assertTrue(TranslationContext.snapshot().containsValue("Label"));
        assertTrue(TranslationContext.snapshot().containsValue("Hover"));
        assertTrue(TranslationContext.snapshot().containsValue("Click command"), TranslationContext.snapshot()::toString);
        assertTrue(TranslationContext.snapshot().containsValue("Action command"), TranslationContext.snapshot()::toString);
        JsonObject written = read(id("dialog.json"));
        String serialized = written.toString();
        assertTrue(serialized.contains("translate"), serialized);
        assertFalse(serialized.contains("\"text\":\"Description\""), serialized);
    }

    @Test
    void leavesTheFileAloneWhenNoTargetMatches() {
        SimpleJsonHandler handler = handler("display.title");

        assertFalse(handler.handle(id("advancement.json"), source("{\"criteria\":{}}")));

        // Nothing was rewritten, so the resource must not be republished into the world directory.
        assertFalse(Files.exists(this.directory.resolve("advancement.json")));
        assertEquals(Map.of(), TranslationContext.snapshot());
    }

    @Test
    void appliesAUserJsonPatternAfterTheBuiltInTargets() {
        JsonObject config = WtemConfig.DEFAULT.toJson(List.of("advancement"));
        config.add(
                "patterns",
                JsonParser.parseString(
                        "{\"json\":[{\"resource\":\"advancement\",\"path\":\"custom.title\"}]}"));
        WtemConfig.initialize(WtemConfig.fromJson(config));
        SimpleJsonHandler handler = handler();

        assertTrue(
                handler.handle(
                        id("advancement.json"),
                        source("{\"custom\":{\"title\":{\"text\":\"Configured title\"}}}")));

        assertEquals(
                "Configured title",
                TranslationContext.snapshot().get("datapack.example.advancement.custom.title"));
    }

    @Test
    void catalogsAnExplicitPlainJsonStringWithoutWritingAComponent() {
        JsonObject config = WtemConfig.DEFAULT.toJson(List.of("advancement"));
        config.add(
                "patterns",
                JsonParser.parseString(
                        "{\"json\":[{\"resource\":\"advancement\",\"path\":\"custom.label\",\"kind\":\"plain_string\"}]}"));
        WtemConfig.initialize(WtemConfig.fromJson(config));
        SimpleJsonHandler handler = handler();
        Identifier resource = id("advancement.json");

        assertFalse(
                handler.handle(
                        resource,
                        source("{\"custom\":{\"label\":\"Visible label\"}}")));
        assertEquals(
                "Visible label",
                TranslationContext.snapshot().get("datapack.example.advancement.custom.label"));
        assertFalse(Files.exists(this.directory.resolve("advancement.json")));
    }

    @Test
    void customOnlyHandlersCanSelectAnArrayRoot() {
        JsonObject config = WtemConfig.DEFAULT.toJson(List.of("custom"));
        config.add(
                "patterns",
                JsonParser.parseString(
                        "{\"json\":[{\"resource\":\"custom\",\"path\":\"[0].label\"}]}"));
        WtemConfig.initialize(WtemConfig.fromJson(config));
        SimpleJsonHandler handler =
                new SimpleJsonHandler(
                        "custom",
                        rl -> this.directory.resolve(rl.getPath()),
                        ResourceHandler.Context.of(List.of(), null, null, null));

        assertTrue(
                handler.handle(
                        id("custom.json"), source("[{\"label\":{\"text\":\"Array label\"}}]")));
        assertEquals("Array label", TranslationContext.snapshot().get("datapack.example.custom.0.label"));
    }

    @Test
    void rejectsAResourceThatIsNotAJsonObject() {
        SimpleJsonHandler handler = handler("display.title");
        Identifier rl = id("advancement.json");

        assertThrows(
                IllegalStateException.class, () -> handler.innerHandle(rl, source("[\"a\"]")));
    }

    @Test
    void rejectsAHandlerWithoutTargetPaths() {
        SimpleJsonHandler handler =
                new SimpleJsonHandler(
                        "advancement",
                        rl -> this.directory.resolve(rl.getPath()),
                        ResourceHandler.Context.of(null, null, null, null));
        Identifier rl = id("advancement.json");

        // The factory always supplies targets, so an absent list is a wiring mistake rather than a
        // resource that happens to have nothing to translate.
        assertThrows(IllegalStateException.class, () -> handler.innerHandle(rl, source("{}")));
    }

    @Test
    void reportsAFailureAsASkippedResource() {
        SimpleJsonHandler handler = handler("display.title");

        // handle wraps innerHandle, so a malformed resource is skipped instead of aborting the run.
        assertFalse(handler.handle(id("advancement.json"), source("[\"a\"]")));
    }

    private SimpleJsonHandler handler(String... targetPaths) {
        return new SimpleJsonHandler(
                "advancement",
                rl -> this.directory.resolve(rl.getPath()),
                ResourceHandler.Context.of(List.of(targetPaths), null, null, null));
    }

    private JsonObject read(Identifier rl) throws IOException {
        return JsonParser.parseString(Files.readString(this.directory.resolve(rl.getPath())))
                .getAsJsonObject();
    }

    private static Identifier id(String path) {
        return ResourceIds.create("example", path);
    }

    private static IoSupplier<InputStream> source(String contents) {
        return () -> new ByteArrayInputStream(contents.getBytes(StandardCharsets.UTF_8));
    }
}
