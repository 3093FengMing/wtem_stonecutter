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
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Regression coverage for custom and vanilla SavedData files below the world's data directory. */
class SavedDataExtractorTest {
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
        assertFalse(session.diagnostics().hasFailures());
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
        assertTrue(session.diagnostics().failures().isEmpty());
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
    }

    private static WtemConfig configured(WtemConfig.Filters filters) {
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
                WtemConfig.DEFAULT.resourcePack());
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
