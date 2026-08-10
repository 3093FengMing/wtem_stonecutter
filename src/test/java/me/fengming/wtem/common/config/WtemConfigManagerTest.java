package me.fengming.wtem.common.config;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WtemConfigManagerTest {
    @TempDir Path temporaryDirectory;

    @AfterEach
    void restoreDefaults() {
        WtemConfig.initialize(WtemConfig.DEFAULT);
    }

    @Test
    void hotReloadsACompleteExternalEditAndKeepsTheLastGoodMalformedFile() throws Exception {
        WtemConfigManager.initialize(this.temporaryDirectory, List.of("function"));
        Path file = this.temporaryDirectory.resolve(WtemConfig.FILE_NAME);
        Files.writeString(file, "{\"stages\":{\"region\":false}}");

        pollOnce();
        assertFalse(WtemConfig.active().isEnabled(WtemConfig.Stage.REGION));

        Files.writeString(file, "{not valid json");
        pollOnce();
        assertFalse(WtemConfig.active().isEnabled(WtemConfig.Stage.REGION));

        Files.writeString(file, "{\"stages\":{\"region\":true}}");
        pollOnce();
        assertTrue(WtemConfig.active().isEnabled(WtemConfig.Stage.REGION));
    }

    @Test
    void hotReloadsAReferencedPatternFile() throws Exception {
        WtemConfigManager.initialize(this.temporaryDirectory, List.of("function"));
        Path config = this.temporaryDirectory.resolve(WtemConfig.FILE_NAME);
        Path patterns = this.temporaryDirectory.resolve("patterns.json");
        Files.writeString(
                patterns,
                "{\"json\":[{\"resource\":\"dialog\",\"path\":\"custom.title\"}]}");
        Files.writeString(config, "{\"patterns\":{\"files\":[\"patterns.json\"]}}");
        pollOnce();
        assertTrue(
                WtemConfig.active().patterns().json().stream()
                        .anyMatch(rule -> rule.path().source().equals("custom.title")));

        Files.writeString(
                patterns,
                "{\"json\":[{\"resource\":\"dialog\",\"path\":\"custom.description\"}]}");
        pollOnce();
        assertTrue(
                WtemConfig.active().patterns().json().stream()
                        .anyMatch(rule -> rule.path().source().equals("custom.description")));
    }

    private static void pollOnce() {
        for (int tick = 0; tick < 20; tick++) WtemConfigManager.tick();
    }
}
