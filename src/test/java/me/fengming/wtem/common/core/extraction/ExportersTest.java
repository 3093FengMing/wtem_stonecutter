package me.fengming.wtem.common.core.extraction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.zip.ZipFile;
import me.fengming.wtem.common.config.WtemConfig;
import me.fengming.wtem.common.core.extraction.export.AiTranslationExporter;
import me.fengming.wtem.common.core.extraction.export.ResourcePackExporter;
import me.fengming.wtem.common.core.extraction.service.ExtractionSession;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ExportersTest {
    @TempDir Path temporaryDirectory;

    @Test
    void aiTranslationWithoutACredentialPublishesANondestructiveSourceCopy() throws Exception {
        WtemConfig.AiTranslation defaults = WtemConfig.AiTranslation.DEFAULT;
        WtemConfig.AiTranslation settings =
                new WtemConfig.AiTranslation(
                        true,
                        defaults.endpoint(),
                        "",
                        defaults.model(),
                        "ko-KR",
                        "ko_kr.json",
                        defaults.batchSize(),
                        defaults.timeoutSeconds(),
                        defaults.translationPrompt(),
                        defaults.keyNamingPrompt());
        Path output = this.temporaryDirectory.resolve("ko_kr.json");
        ExtractionSession session = new ExtractionSession();

        assertTrue(
                AiTranslationExporter.export(
                        settings,
                        output,
                        Map.of("item.best.name", "The Best Sword"),
                        session));

        assertEquals(
                "The Best Sword",
                JsonParser.parseString(Files.readString(output))
                        .getAsJsonObject()
                        .get("item.best.name")
                        .getAsString());
        assertEquals(1, session.report().modifiedResources());
    }

    @Test
    void resourcePackExporterPublishesMatchingFolderAndZipOutputs() throws Exception {
        Path language = this.temporaryDirectory.resolve("en_us.json");
        Path translated = this.temporaryDirectory.resolve("zh_cn.json");
        Files.writeString(language, "{\"example.title\":\"Example\"}");
        Files.writeString(translated, "{\"example.title\":\"示例\"}");
        WtemConfig.ResourcePack settings =
                new WtemConfig.ResourcePack(
                        true,
                        WtemConfig.ResourcePack.Format.BOTH,
                        "translations",
                        "Translation test",
                        "resourcepacks",
                        42);
        ExtractionSession session = new ExtractionSession();

        assertTrue(
                ResourcePackExporter.export(
                        settings,
                        this.temporaryDirectory,
                        language,
                        translated,
                        session));

        Path folder = this.temporaryDirectory.resolve("resourcepacks/translations");
        assertTrue(Files.isRegularFile(folder.resolve("pack.mcmeta")));
        assertTrue(Files.isRegularFile(folder.resolve("assets/wtem/lang/en_us.json")));
        assertTrue(Files.isRegularFile(folder.resolve("assets/wtem/lang/zh_cn.json")));
        assertEquals(
                42,
                JsonParser.parseString(Files.readString(folder.resolve("pack.mcmeta")))
                        .getAsJsonObject()
                        .getAsJsonObject("pack")
                        .get("pack_format")
                        .getAsInt());

        try (ZipFile zip =
                new ZipFile(
                        this.temporaryDirectory
                                .resolve("resourcepacks/translations.zip")
                                .toFile())) {
            Set<String> entries = new TreeSet<>();
            zip.stream().forEach(entry -> entries.add(entry.getName()));
            assertEquals(
                    Set.of(
                            "pack.mcmeta",
                            "assets/wtem/lang/en_us.json",
                            "assets/wtem/lang/zh_cn.json"),
                    entries);
        }
        assertEquals(2, session.report().modifiedResources());
    }
}
