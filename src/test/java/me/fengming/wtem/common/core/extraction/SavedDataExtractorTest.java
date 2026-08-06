package me.fengming.wtem.common.core.extraction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import me.fengming.wtem.common.config.WtemConfig;
import me.fengming.wtem.common.core.extraction.service.ExtractionSession;
import me.fengming.wtem.common.core.extraction.source.SavedDataExtractor;
import me.fengming.wtem.common.util.ResourceIo;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Regression coverage for custom and vanilla SavedData files below the world's data directory. */
class SavedDataExtractorTest {
    @Test
    void extractsTextComponentsFromTheSuppliedStorageFixture(@TempDir Path world) throws Exception {
        Path fixture =
                Path.of("..", "..", "tools/testdatpack/data/cstore/command_storage.dat");
        if (!Files.isRegularFile(fixture)) return;
        Path data = world.resolve("data");
        Files.createDirectories(data.resolve("cstore"));
        Files.copy(fixture, data.resolve("cstore/command_storage.dat"));
        WtemConfig config = configured(WtemConfig.Filters.DEFAULT);
        TranslationContext.setConfig(config);
        ExtractionSession session = new ExtractionSession();

        new SavedDataExtractor(data, config, session).extract();

        Map<String, String> catalog = TranslationContext.snapshot();
        assertTrue(catalog.values().contains("생수"), catalog::toString);
        assertTrue(catalog.values().stream().anyMatch(value -> value.contains("판매가: 250원")), catalog::toString);
        assertFalse(catalog.values().contains("minecraft:potion"), catalog::toString);
        assertEquals(1, session.report().modifiedSavedData());
    }
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
    void extractsEveryDatFileIncludingCustomNestedFiles(@TempDir Path world) {
        Path data = world.resolve("data");
        write(data.resolve("one.dat"), "entry", "First");
        write(data.resolve("two.dat"), "entry", "Second");
        write(data.resolve("custom/nested.dat"), "entry", "Nested");
        try {
            Files.writeString(data.resolve("ignored.txt"), "not NBT");
        } catch (java.io.IOException exception) {
            throw new AssertionError(exception);
        }

        WtemConfig config = configured(WtemConfig.Filters.DEFAULT);
        TranslationContext.setConfig(config);
        ExtractionSession session = new ExtractionSession();

        new SavedDataExtractor(data, config, session).extract();

        assertEquals(
                Map.of(
                        "custom_nested.entry.name", "Nested",
                        "one.entry.name", "First",
                        "two.entry.name", "Second"),
                TranslationContext.snapshot());
        assertEquals(3, session.report().modifiedSavedData());
        assertTrue(
                session.diagnostics().failures().stream()
                        .allMatch(failure -> "saved_data_string".equals(failure.scope())),
                session.diagnostics().failures()::toString);
    }

    @Test
    void skipsVanillaSavedDataWithoutUsingTheNamespace(@TempDir Path world) {
        Path data = world.resolve("data");
        write(data.resolve("scoreboard.dat"), "entry", "Scoreboard text");
        write(data.resolve("custom_boss_events.dat"), "entry", "Bossbar text");
        write(data.resolve("chunks.dat"), "entry", "Forced chunks");
        write(data.resolve("command_storage_minecraft.dat"), "entry", "Command storage");
        write(data.resolve("maps/12.dat"), "entry", "Map text");
        write(data.resolve("minecraft/scoreboard.dat"), "entry", "Namespaced scoreboard");
        write(
                data.resolve("minecraft/custom_boss_events.dat"),
                "entry",
                "Namespaced bossbar");
        write(data.resolve("minecraft/command_storage.dat"), "entry", "Namespaced storage");
        write(data.resolve("minecraft/custom.dat"), "entry", "Custom text");
        write(data.resolve("custom/minecraft_like.dat"), "entry", "Also custom");
        write(data.resolve("custom/scoreboard_like.dat"), "entry", "Nested custom scoreboard");

        WtemConfig config = configured(WtemConfig.Filters.DEFAULT);
        TranslationContext.setConfig(config);

        new SavedDataExtractor(data, config, new ExtractionSession()).extract();

        assertEquals(
                Map.of(
                        "custom_minecraft_like.entry.name", "Also custom",
                        "custom_scoreboard_like.entry.name", "Nested custom scoreboard",
                        "minecraft_custom.entry.name", "Custom text"),
                TranslationContext.snapshot());
    }

    @Test
    void filtersIndividualSavedDataPaths(@TempDir Path world) {
        Path data = world.resolve("data");
        writeTwoEntries(data.resolve("custom.dat"), "Public", "Private");
        WtemConfig.Filters filters =
                new WtemConfig.Filters(
                        List.of(),
                        List.of(),
                        List.of("custom.dat/*", "!custom.dat/private/*"),
                        List.of(),
                        List.of(),
                        WtemConfig.Filters.Selection.DEFAULT);
        WtemConfig config = configured(filters);
        TranslationContext.setConfig(config);

        new SavedDataExtractor(data, config, new ExtractionSession()).extract();

        assertEquals(
                Map.of("custom.public.name", "Public"),
                TranslationContext.snapshot());
    }

    @Test
    void skipsUnselectedSavedDataFilesBeforeReadingThem(@TempDir Path world) {
        Path data = world.resolve("data");
        write(data.resolve("selected.dat"), "entry", "Selected");
        try {
            Files.createDirectories(data);
            // If this file were opened, it would add a diagnostic because it is not compressed NBT.
            Files.writeString(data.resolve("broken.dat"), "not compressed NBT");
        } catch (java.io.IOException exception) {
            throw new AssertionError(exception);
        }
        WtemConfig.Filters filters =
                new WtemConfig.Filters(
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        new WtemConfig.Filters.Selection(
                                List.of(),
                                List.of(),
                                List.of(),
                                List.of("selected.dat")));
        WtemConfig config = configured(filters);
        TranslationContext.setConfig(config);
        ExtractionSession session = new ExtractionSession();

        new SavedDataExtractor(data, config, session).extract();

        assertEquals(Map.of("selected.entry.name", "Selected"), TranslationContext.snapshot());
        assertTrue(
                session.diagnostics().failures().stream()
                        .allMatch(failure -> "saved_data_string".equals(failure.scope())),
                session.diagnostics().failures()::toString);
    }

    @Test
    void catalogsPlainCustomStringsWithoutChangingTheirNbtType(@TempDir Path world) {
        Path data = world.resolve("data");
        CompoundTag root = new CompoundTag();
        CompoundTag wrapped = new CompoundTag();
        CompoundTag entry = new CompoundTag();
        entry.putString("name", "Plain custom storage text");
        wrapped.put("entry", entry);
        root.put("data", wrapped);
        Path file = data.resolve("plain.dat");
        ResourceIo.writeNbt(file, root);
        WtemConfig config = configured(WtemConfig.Filters.DEFAULT);
        TranslationContext.setConfig(config);
        ExtractionSession session = new ExtractionSession();

        new SavedDataExtractor(data, config, session).extract();

        assertEquals(
                Map.of("plain.entry.name", "Plain custom storage text"),
                TranslationContext.snapshot());
        assertFalse(TranslationContext.records().getFirst().replaced());
        assertEquals(0, session.report().modifiedSavedData());
        var warning =
                session.diagnostics().failures().stream()
                        .filter(failure -> "saved_data_string".equals(failure.scope()))
                        .findFirst()
                        .orElseThrow();
        assertTrue(warning.resource().contains("plain.dat/entry/name"), warning.resource());
    }

    @Test
    void doesNotTreatAnArbitraryPlainStringAsTextByDefault(@TempDir Path world) {
        Path data = world.resolve("data");
        writeField(data.resolve("custom.dat"), "custom_message", "{\"text\":\"Not configured\"}");
        WtemConfig config = configured(WtemConfig.Filters.DEFAULT);
        TranslationContext.setConfig(config);
        ExtractionSession session = new ExtractionSession();

        new SavedDataExtractor(data, config, session).extract();

        assertEquals(Map.of(), TranslationContext.snapshot());
        var warning =
                session.diagnostics().failures().stream()
                        .filter(failure -> "saved_data_string".equals(failure.scope()))
                        .findFirst()
                        .orElseThrow();
        assertTrue(warning.resource().contains("custom.dat/entry/custom_message"), warning.resource());
    }

    @Test
    void recognizesBackTextUsingTheDefaultFieldList(@TempDir Path world) {
        Path data = world.resolve("data");
        writeField(data.resolve("custom.dat"), "back_text", "{\"text\":\"Back side\"}");
        WtemConfig config = configured(WtemConfig.Filters.DEFAULT);
        TranslationContext.setConfig(config);

        new SavedDataExtractor(data, config, new ExtractionSession()).extract();

        assertEquals(Map.of("custom.entry.back_text", "Back side"), TranslationContext.snapshot());
    }

    @Test
    void usesConfiguredFieldNamesForPlainJsonStrings(@TempDir Path world) {
        Path data = world.resolve("data");
        writeField(data.resolve("custom.dat"), "custom_message", "{\"text\":\"Configured\"}");
        WtemConfig config =
                configured(WtemConfig.Filters.DEFAULT, List.of(" Custom_Message "));
        TranslationContext.setConfig(config);

        new SavedDataExtractor(data, config, new ExtractionSession()).extract();

        assertEquals(
                Map.of("custom.entry.custom_message", "Configured"),
                TranslationContext.snapshot());
    }

    @Test
    void keepsRecognizingStructuredComponentsWhenFieldHeuristicsAreDisabled(@TempDir Path world) {
        Path data = world.resolve("data");
        CompoundTag root = new CompoundTag();
        CompoundTag wrapped = new CompoundTag();
        CompoundTag entry = new CompoundTag();
        CompoundTag component = new CompoundTag();
        component.putString("text", "Structured");
        entry.put("custom_component", component);
        entry.putString("custom_message", "{\"text\":\"Not configured\"}");
        wrapped.put("entry", entry);
        root.put("data", wrapped);
        ResourceIo.writeNbt(data.resolve("structured.dat"), root);
        WtemConfig config = configured(WtemConfig.Filters.DEFAULT, List.of());
        TranslationContext.setConfig(config);

        new SavedDataExtractor(data, config, new ExtractionSession()).extract();

        assertEquals(
                Map.of("structured.entry.custom_component", "Structured"),
                TranslationContext.snapshot());
    }

    @Test
    void recognizesStructuredComponentsInsideAnUnconfiguredList(@TempDir Path world) {
        Path data = world.resolve("data");
        CompoundTag root = new CompoundTag();
        CompoundTag wrapped = new CompoundTag();
        CompoundTag entry = new CompoundTag();
        CompoundTag component = new CompoundTag();
        component.putString("text", "List structured");
        ListTag components = new ListTag();
        components.add(component);
        entry.put("custom_components", components);
        wrapped.put("entry", entry);
        root.put("data", wrapped);
        ResourceIo.writeNbt(data.resolve("list_structured.dat"), root);
        WtemConfig config = configured(WtemConfig.Filters.DEFAULT, List.of());
        TranslationContext.setConfig(config);

        new SavedDataExtractor(data, config, new ExtractionSession()).extract();

        assertEquals(
                Map.of("list_structured.entry.custom_components.0", "List structured"),
                TranslationContext.snapshot());
    }

    @Test
    void warnsForStringsInsideNestedSavedDataLists(@TempDir Path world) {
        CompoundTag root = new CompoundTag();
        CompoundTag data = new CompoundTag();
        CompoundTag entry = new CompoundTag();
        ListTag outer = new ListTag();
        ListTag inner = new ListTag();
        inner.add(StringTag.valueOf("Nested plain string"));
        outer.add(inner);
        entry.put("payload", outer);
        data.put("entry", entry);
        root.put("data", data);
        Path file = world.resolve("data/nested.dat");
        ResourceIo.writeNbt(file, root);
        WtemConfig config = configured(WtemConfig.Filters.DEFAULT, List.of());
        TranslationContext.setConfig(config);
        ExtractionSession session = new ExtractionSession();

        new SavedDataExtractor(world.resolve("data"), config, session).extract();

        assertEquals(Map.of(), TranslationContext.snapshot());
        assertTrue(
                session.diagnostics().failures().stream()
                        .anyMatch(
                                failure ->
                                        "saved_data_string".equals(failure.scope())
                                                && failure.resource()
                                                        .contains("nested.dat/entry/payload/0/0")),
                session.diagnostics().failures()::toString);
    }

    private static WtemConfig configured(WtemConfig.Filters filters) {
        return configured(filters, WtemConfig.DEFAULT.savedDataTextFields());
    }

    private static WtemConfig configured(
            WtemConfig.Filters filters, List<String> savedDataTextFields) {
        return new WtemConfig(
                WtemConfig.DEFAULT.stages(),
                WtemConfig.DEFAULT.resources(),
                WtemConfig.DEFAULT.keyReuse(),
                WtemConfig.DEFAULT.keyNaming(),
                WtemConfig.DEFAULT.nbtMaxDepth(),
                WtemConfig.DEFAULT.rebuildNestedKeys(),
                WtemConfig.DEFAULT.skipped(),
                WtemConfig.DEFAULT.skippedPaths(),
                Map.of(),
                WtemConfig.DEFAULT.languageFile(),
                filters,
                WtemConfig.DEFAULT.outputs(),
                WtemConfig.DEFAULT.aiTranslation(),
                WtemConfig.DEFAULT.resourcePack(),
                savedDataTextFields);
    }

    private static void writeField(Path file, String fieldName, String value) {
        CompoundTag root = new CompoundTag();
        CompoundTag data = new CompoundTag();
        CompoundTag entry = new CompoundTag();
        entry.putString(fieldName, value);
        data.put("entry", entry);
        root.put("data", data);
        ResourceIo.writeNbt(file, root);
    }

    private static void write(Path file, String entryName, String text) {
        CompoundTag root = new CompoundTag();
        CompoundTag data = new CompoundTag();
        CompoundTag entry = new CompoundTag();
        entry.putString("name", "{\"text\":\"" + text + "\"}");
        data.put(entryName, entry);
        root.put("data", data);
        ResourceIo.writeNbt(file, root);
    }

    private static void writeTwoEntries(Path file, String publicText, String privateText) {
        CompoundTag root = new CompoundTag();
        CompoundTag data = new CompoundTag();
        CompoundTag publicEntry = new CompoundTag();
        publicEntry.putString("name", "{\"text\":\"" + publicText + "\"}");
        CompoundTag privateEntry = new CompoundTag();
        privateEntry.putString("name", "{\"text\":\"" + privateText + "\"}");
        data.put("public", publicEntry);
        data.put("private", privateEntry);
        root.put("data", data);
        ResourceIo.writeNbt(file, root);
    }
}
