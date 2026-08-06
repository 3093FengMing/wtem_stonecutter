package me.fengming.wtem.common.core.extraction.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ExtractionChoicesTest {
    @Test
    void savedDataSelectionOmitsVanillaFilesInEverySupportedLayout(@TempDir Path world)
            throws IOException {
        Path data = world.resolve("data");
        for (String file :
                List.of(
                        "scoreboard.dat",
                        "custom_boss_events.dat",
                        "chunks.dat",
                        "command_storage_minecraft.dat",
                        "maps/12.dat",
                        "minecraft/scoreboard.dat",
                        "minecraft/custom_boss_events.dat",
                        "minecraft/command_storage.dat",
                        "minecraft/maps/last_id.dat")) {
            Path path = data.resolve(file);
            Files.createDirectories(path.getParent());
            Files.writeString(path, "placeholder");
        }
        for (String file : List.of("custom.dat", "minecraft/custom.dat", "custom/own.dat")) {
            Path path = data.resolve(file);
            Files.createDirectories(path.getParent());
            Files.writeString(path, "placeholder");
        }

        assertEquals(
                List.of("custom.dat", "custom/own.dat", "minecraft/custom.dat"),
                ExtractionChoices.savedDataFiles(data));
    }
}
