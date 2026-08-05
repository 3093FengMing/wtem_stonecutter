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

    private static void pollOnce() {
        for (int tick = 0; tick < 20; tick++) WtemConfigManager.tick();
    }
}
