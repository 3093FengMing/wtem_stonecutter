package me.fengming.wtem.common.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DirectoryPublisherTest {
    @TempDir Path temporaryDirectory;

    @Test
    void publishesCompleteStagingDirectoryOverPreviousOutput() throws Exception {
        Path target = this.temporaryDirectory.resolve("example_wtem");
        Files.createDirectories(target);
        Files.writeString(target.resolve("stale.json"), "stale");

        Path staging = DirectoryPublisher.createStagingDirectory(target);
        Files.writeString(staging.resolve("pack.mcmeta"), "current");

        DirectoryPublisher.publish(staging, target);

        assertFalse(Files.exists(staging));
        assertFalse(Files.exists(target.resolve("stale.json")));
        assertEquals("current", Files.readString(target.resolve("pack.mcmeta")));
    }
}
