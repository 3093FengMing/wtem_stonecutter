package me.fengming.wtem.common.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WtemConfigTest {
    @Test
    void keepsBuiltInBehaviourForAbsentSettings() {
        WtemConfig config = parse("{}");

        for (WtemConfig.Stage stage : WtemConfig.Stage.values()) {
            assertTrue(config.isEnabled(stage), stage::name);
        }
        assertTrue(config.isResourceEnabled("advancement"));
        assertTrue(config.keyReuse().allows("item.stick.1.name"));
        assertEquals(WtemConfig.DEFAULT_LANGUAGE_FILE, config.languageFile());
    }

    @Test
    void readsStageAndResourceToggles() {
        WtemConfig config =
                parse(
                        """
                        {
                          "stages": {"region": false, "datapacks": true},
                          "resources": {"function": false}
                        }
                        """);

        assertFalse(config.isEnabled(WtemConfig.Stage.REGION));
        assertTrue(config.isEnabled(WtemConfig.Stage.DATAPACKS));
        assertTrue(config.isEnabled(WtemConfig.Stage.ENTITIES));
        assertFalse(config.isResourceEnabled("function"));
        assertTrue(config.isResourceEnabled("loot_table"));
    }

    @Test
    void ignoresSettingsOfTheWrongType() {
        WtemConfig config =
                parse(
                        """
                        {
                          "stages": {"region": "false"},
                          "resources": {"function": 0},
                          "key_reuse": {"default": []}
                        }
                        """);

        assertTrue(config.isEnabled(WtemConfig.Stage.REGION));
        assertTrue(config.isResourceEnabled("function"));
        assertTrue(config.keyReuse().allows("item.stick.1.name"));
    }

    @Test
    void rejectsNonObjectRoot() {
        assertThrows(IllegalArgumentException.class, () -> parse("[]"));
    }

    @Test
    void appliesTheLongestMatchingKeyReusePrefix() {
        WtemConfig config =
                parse(
                        """
                        {
                          "key_reuse": {
                            "default": true,
                            "overrides": {
                              "datapack.": false,
                              "datapack.example.dialog.": true
                            }
                          }
                        }
                        """);

        WtemConfig.KeyReuse keyReuse = config.keyReuse();
        assertTrue(keyReuse.allows("item.stick.1.name"));
        assertFalse(keyReuse.allows("datapack.example.function"));
        assertTrue(keyReuse.allows("datapack.example.dialog.title"));
    }

    @Test
    void rejectsLanguageFileNamesThatEscapeTheWorldDirectory() {
        assertEquals(
                WtemConfig.DEFAULT_LANGUAGE_FILE,
                parse("{\"language_file\": \"../evil.json\"}").languageFile());
        assertEquals(
                WtemConfig.DEFAULT_LANGUAGE_FILE,
                parse("{\"language_file\": \"nested/zh_cn.json\"}").languageFile());
        assertEquals(
                WtemConfig.DEFAULT_LANGUAGE_FILE,
                parse("{\"language_file\": \"zh_cn.txt\"}").languageFile());
        assertEquals("zh_cn.json", parse("{\"language_file\": \"zh_cn.json\"}").languageFile());
    }

    @Test
    void writesADefaultFileWhenNoneExists(@TempDir Path directory) throws IOException {
        WtemConfig config = WtemConfig.loadOrCreate(directory);

        Path file = directory.resolve(WtemConfig.FILE_NAME);
        assertTrue(Files.exists(file));
        assertEquals(WtemConfig.DEFAULT, config);

        // The generated file is the only documentation of the available settings, so it spells every
        // stage out rather than relying on absent settings. Reading it back has to describe the same
        // behaviour, even though spelled-out settings are not the same record value as absent ones.
        String contents = Files.readString(file);
        assertTrue(contents.contains("generated_structures"), contents);
        WtemConfig reloaded = WtemConfig.fromJson(JsonParser.parseString(contents));
        for (WtemConfig.Stage stage : WtemConfig.Stage.values()) {
            assertTrue(reloaded.isEnabled(stage), stage::name);
        }
        assertEquals(WtemConfig.KeyReuse.DEFAULT, reloaded.keyReuse());
        assertEquals(WtemConfig.DEFAULT_LANGUAGE_FILE, reloaded.languageFile());
    }

    @Test
    void fallsBackToDefaultsWhenTheFileIsMalformed(@TempDir Path directory) throws IOException {
        Files.writeString(directory.resolve(WtemConfig.FILE_NAME), "{ not json");

        assertEquals(WtemConfig.DEFAULT, WtemConfig.loadOrCreate(directory));
    }

    @Test
    void readsAFileFromDisk(@TempDir Path directory) throws IOException {
        Files.writeString(
                directory.resolve(WtemConfig.FILE_NAME), "{\"stages\": {\"region\": false}}");

        assertFalse(WtemConfig.loadOrCreate(directory).isEnabled(WtemConfig.Stage.REGION));
    }

    private static WtemConfig parse(String json) {
        return WtemConfig.fromJson(JsonParser.parseString(json));
    }
}
