package me.fengming.wtem.common.core.extraction.pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ExtractionPatternsTest {
    @Test
    void readsTypedInlineRulesAndRoundTripsOnlyInlineRules() {
        ExtractionPatterns patterns =
                ExtractionPatterns.fromJson(
                        JsonParser.parseString(
                                """
                                {
                                  "files": ["extra.json"],
                                  "json": [{"resource":"dialog","path":"body[*].contents"}],
                                  "saved_data": [{"file":"custom.dat","path":"entry.name","kind":"plain_string"}],
                                  "commands": [{"command":"tellraw","argument":"message"}]
                                }
                                """));

        assertEquals(1, patterns.files().size());
        assertEquals(1, patterns.json().size());
        assertEquals(1, patterns.savedData().size());
        assertEquals(1, patterns.commands().size());
        assertEquals("body[*].contents", patterns.toJson().getAsJsonArray("json").get(0).getAsJsonObject().get("path").getAsString());
        assertTrue(patterns.toJson().getAsJsonArray("saved_data").get(0).getAsJsonObject().get("kind").getAsString().equals("plain_string"));
    }

    @Test
    void loadsExternalRulesRelativeToTheConfigDirectory(@TempDir Path directory) throws Exception {
        Files.writeString(
                directory.resolve("extra.json"),
                """
                {"json":[{"resource":"advancement","path":"custom.title"}]}
                """);
        ExtractionPatterns patterns =
                ExtractionPatterns.fromJson(
                        JsonParser.parseString("{\"files\":[\"extra.json\"]}"), directory);

        assertEquals(1, patterns.json().size());
        assertEquals("custom.title", patterns.json().getFirst().path().source());
        assertEquals(0, patterns.toJson().getAsJsonArray("json").size());
    }

    @Test
    void ignoresExternalPathsOutsideTheConfigDirectory(@TempDir Path directory) throws Exception {
        ExtractionPatterns patterns =
                ExtractionPatterns.fromJson(
                        JsonParser.parseString("{\"files\":[\"../outside.json\"]}"), directory);

        assertEquals(List.of(), patterns.resolvedFiles(directory));
    }
}
