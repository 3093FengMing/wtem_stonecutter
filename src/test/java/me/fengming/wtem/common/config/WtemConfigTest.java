package me.fengming.wtem.common.config;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

class WtemConfigTest {
    // Stands in for the registered handlers, which cannot be built without a loaded game.
    private static final List<String> RESOURCE_DIRECTORIES =
            List.of("advancement", "loot_table", "function");

    @Test
    void keepsBuiltInBehaviourForAbsentSettings() {
        WtemConfig config = parse("{}");

        for (WtemConfig.Stage stage : WtemConfig.Stage.values()) {
            assertTrue(config.isEnabled(stage), stage::name);
        }
        assertTrue(config.isResourceEnabled("advancement"));
        assertTrue(config.keyReuse().allows("item.stick.1.name"));
        assertEquals(WtemConfig.KeyNaming.DEFAULT, config.keyNaming());
        assertEquals(WtemConfig.DEFAULT_NBT_MAX_DEPTH, config.nbtMaxDepth());
        assertTrue(config.rebuildNestedKeys());
        assertEquals(WtemConfig.Skipped.DEFAULT, config.skipped());
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
    void readsTheTraversalDepthLimitAndRejectsUnusableValues() {
        assertEquals(4, parse("{\"nbt_max_depth\": 4}").nbtMaxDepth());
        // A limit below one stops traversal before the outermost tag is read, extracting nothing.
        assertEquals(WtemConfig.DEFAULT_NBT_MAX_DEPTH, parse("{\"nbt_max_depth\": 0}").nbtMaxDepth());
        assertEquals(
                WtemConfig.DEFAULT_NBT_MAX_DEPTH, parse("{\"nbt_max_depth\": -1}").nbtMaxDepth());
        assertEquals(
                WtemConfig.DEFAULT_NBT_MAX_DEPTH, parse("{\"nbt_max_depth\": \"8\"}").nbtMaxDepth());
    }

    @Test
    void readsWhetherNestedDataRestartsItsKey() {
        assertFalse(parse("{\"rebuild_nested_keys\": false}").rebuildNestedKeys());
        assertTrue(parse("{\"rebuild_nested_keys\": true}").rebuildNestedKeys());
        assertTrue(parse("{\"rebuild_nested_keys\": \"false\"}").rebuildNestedKeys());
    }

    @Test
    void readsWhichTranslatableTextToLeaveAlone() {
        // Both default to off, so an untouched file keeps extracting text that was extracted before
        // the settings existed.
        assertEquals(new WtemConfig.Skipped(true, true), parse("{}").skipped());
        assertEquals(
                new WtemConfig.Skipped(false, true),
                parse("{\"skipped\": {\"command_block_output\": false}}").skipped());
        assertEquals(
                new WtemConfig.Skipped(true, false),
                parse("{\"skipped\": {\"filtered_text\": false}}").skipped());
        assertEquals(
                WtemConfig.Skipped.DEFAULT,
                parse("{\"skipped\": {\"filtered_text\": \"true\"}}").skipped());
    }

    @Test
    void readsTheKeyNamingSettings() {
        WtemConfig.KeyNaming keyNaming =
                parse(
                                """
                                {
                                  "key_naming": {
                                    "scheme": "hashed",
                                    "random_length": 4
                                  }
                                }
                                """)
                        .keyNaming();

        assertEquals(WtemConfig.KeyNaming.Scheme.HASHED, keyNaming.scheme());
        assertEquals(4, keyNaming.randomLength());
    }

    @Test
    void ignoresUnknownKeyNamingChoices() {
        assertEquals(
                WtemConfig.KeyNaming.DEFAULT,
                parse("{\"key_naming\": {\"scheme\": \"nope\"}}").keyNaming());
    }

    @Test
    void fallsBackToADefaultRandomKeyLength() {
        assertEquals(
                WtemConfig.KeyNaming.DEFAULT_RANDOM_LENGTH,
                parse("{\"key_naming\": {\"random_length\": 0}}").keyNaming().randomLength());
    }

    @Test
    void hashedKeysAreNamespacedAndStable() {
        WtemConfig.KeyNaming keyNaming =
                new WtemConfig.KeyNaming(WtemConfig.KeyNaming.Scheme.HASHED, 8);

        String key = keyNaming.baseKey("entity.zombie.1.name");

        // A hashed key no longer contains the generated index that keeps a structured key from
        // colliding with a vanilla one, so it is namespaced instead.
        assertTrue(key.startsWith(WtemConfig.KeyNaming.GENERATED_PREFIX), key);
        assertEquals(key, keyNaming.baseKey("entity.zombie.1.name"));
        assertNotEquals(key, keyNaming.baseKey("entity.zombie.2.name"), key);
    }

    @Test
    void structuredKeysKeepTheExtractionPath() {
        assertEquals(
                "entity.zombie.1.name",
                WtemConfig.KeyNaming.DEFAULT.baseKey("entity.zombie.1.name"));
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
        WtemConfig config = WtemConfig.loadOrCreate(directory, RESOURCE_DIRECTORIES);

        Path file = directory.resolve(WtemConfig.FILE_NAME);
        assertTrue(Files.exists(file));
        assertEquals(WtemConfig.DEFAULT, config);

        // The generated file is the only documentation of the available settings, so it spells every
        // setting out at its default rather than relying on absent settings. Reading it back has to
        // describe the same behaviour, even though a spelled-out setting is not the same record value
        // as an absent one.
        String contents = Files.readString(file);
        assertTrue(contents.contains("generated_structures"), contents);
        WtemConfig reloaded = WtemConfig.fromJson(JsonParser.parseString(contents));
        for (WtemConfig.Stage stage : WtemConfig.Stage.values()) {
            assertTrue(reloaded.isEnabled(stage), stage::name);
        }
        assertEquals(WtemConfig.KeyReuse.DEFAULT, reloaded.keyReuse());
        assertEquals(WtemConfig.KeyNaming.DEFAULT, reloaded.keyNaming());
        assertEquals(WtemConfig.DEFAULT_NBT_MAX_DEPTH, reloaded.nbtMaxDepth());
        assertTrue(reloaded.rebuildNestedKeys());
        assertEquals(WtemConfig.Skipped.DEFAULT, reloaded.skipped());
        assertEquals(WtemConfig.DEFAULT_BUILTIN_ENTRIES, reloaded.builtinEntries());
        assertEquals(WtemConfig.DEFAULT_LANGUAGE_FILE, reloaded.languageFile());
    }

    @Test
    void theDefaultFileListsEveryResourceKindItControls() {
        JsonObject resources =
                WtemConfig.DEFAULT
                        .toJson(RESOURCE_DIRECTORIES)
                        .getAsJsonObject("resources");

        // A resource kind absent from the file is enabled, so listing only the configured ones would
        // produce a file that hides most of what it controls.
        assertEquals(RESOURCE_DIRECTORIES, new ArrayList<>(resources.keySet()));
        for (String directory : RESOURCE_DIRECTORIES) {
            assertTrue(resources.get(directory).getAsBoolean(), directory);
        }
    }

    @Test
    void keepsAConfiguredResourceToggleOverTheListedDefault() {
        JsonObject resources =
                parse("{\"resources\": {\"function\": false}}")
                        .toJson(RESOURCE_DIRECTORIES)
                        .getAsJsonObject("resources");

        assertFalse(resources.get("function").getAsBoolean());
        assertTrue(resources.get("loot_table").getAsBoolean());
    }

    @Test
    void seedsTheCatalogWithBuiltInEntriesUnlessTheyAreReplaced() {
        assertEquals(WtemConfig.DEFAULT_BUILTIN_ENTRIES, parse("{}").builtinEntries());
        assertEquals(
                Map.of("wtem.dash", "-"),
                parse("{\"builtin_entries\": {\"wtem.dash\": \"-\"}}").builtinEntries());
        // An empty section is the only way to ask for nothing at all, so it is not treated as absent.
        assertEquals(Map.of(), parse("{\"builtin_entries\": {}}").builtinEntries());
        assertEquals(
                Map.of(),
                parse("{\"builtin_entries\": {\"wtem.bad\": []}}").builtinEntries());
    }

    @Test
    void theBuiltInEntriesCoverBlankTextAndSingleDigits() {
        assertEquals("", WtemConfig.DEFAULT_BUILTIN_ENTRIES.get("wtem.blank"));
        assertEquals(" ", WtemConfig.DEFAULT_BUILTIN_ENTRIES.get("wtem.space"));
        for (int digit = 0; digit <= 9; digit++) {
            assertEquals(
                    String.valueOf(digit),
                    WtemConfig.DEFAULT_BUILTIN_ENTRIES.get("wtem." + digit));
        }
    }

    @Test
    void fallsBackToDefaultsWhenTheFileIsMalformed(@TempDir Path directory) throws IOException {
        Files.writeString(directory.resolve(WtemConfig.FILE_NAME), "{ not json");

        assertEquals(
                WtemConfig.DEFAULT, WtemConfig.loadOrCreate(directory, RESOURCE_DIRECTORIES));
    }

    @Test
    void readsAFileFromDisk(@TempDir Path directory) throws IOException {
        Files.writeString(
                directory.resolve(WtemConfig.FILE_NAME), "{\"stages\": {\"region\": false}}");

        assertFalse(
                WtemConfig.loadOrCreate(directory, RESOURCE_DIRECTORIES)
                        .isEnabled(WtemConfig.Stage.REGION));
    }

    private static WtemConfig parse(String json) {
        return WtemConfig.fromJson(JsonParser.parseString(json));
    }
}
