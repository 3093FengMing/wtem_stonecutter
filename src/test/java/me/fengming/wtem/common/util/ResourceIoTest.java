package me.fengming.wtem.common.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.packs.resources.IoSupplier;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ResourceIoTest {
    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void readsTheWholeDocumentForAnEmptyPath() {
        assertEquals(
                JsonParser.parseString("{\"a\":{\"b\":1}}"),
                ResourceIo.readJson(source("{\"a\":{\"b\":1}}"), ""));
        assertEquals(
                JsonParser.parseString("{\"a\":{\"b\":1}}"),
                ResourceIo.readJson(source("{\"a\":{\"b\":1}}"), null));
    }

    @Test
    void walksDottedPathSegments() {
        JsonElement value = ResourceIo.readJson(source("{\"a\":{\"b\":{\"c\":\"x\"}}}"), "a.b.c");

        assertEquals("x", value.getAsString());
    }

    @Test
    void rejectsAMissingPath() {
        ResourceIo.ResourceIoException exception =
                assertThrows(
                        ResourceIo.ResourceIoException.class,
                        () -> ResourceIo.readJson(source("{\"a\":{}}"), "a.b"));

        assertEquals("Missing JSON path: a.b", exception.getMessage());
    }

    @Test
    void rejectsAPathThatRunsIntoANonObject() {
        assertThrows(
                ResourceIo.ResourceIoException.class,
                () -> ResourceIo.readJson(source("{\"a\":1}"), "a.b"));
    }

    @Test
    void wrapsMalformedJsonAndFailedStreams() {
        assertThrows(
                ResourceIo.ResourceIoException.class,
                () -> ResourceIo.readJson(source("{ not json"), ""));
        assertThrows(
                ResourceIo.ResourceIoException.class,
                () ->
                        ResourceIo.readJson(
                                () -> {
                                    throw new IOException("unreadable");
                                },
                                ""));
    }

    @Test
    void writesAndReplacesText(@TempDir Path directory) throws IOException {
        Path file = directory.resolve("nested/catalog.json");

        ResourceIo.writeString(file, "first");
        assertEquals("first", Files.readString(file));

        // Extraction rewrites the catalog whenever a later stage adds entries, so replacing an
        // existing file has to succeed rather than fail on the move.
        ResourceIo.writeString(file, "second");
        assertEquals("second", Files.readString(file));
    }

    @Test
    void leavesNoTemporaryFilesBehind(@TempDir Path directory) throws IOException {
        Path file = directory.resolve("catalog.json");

        ResourceIo.writeJson(file, JsonParser.parseString("{\"a\":1}"));

        try (Stream<Path> entries = Files.list(directory)) {
            assertEquals(
                    1,
                    entries.count(),
                    "the temporary file must be moved onto the target, not left next to it");
        }
    }

    @Test
    void writesJsonThatCanBeReadBack(@TempDir Path directory) throws IOException {
        Path file = directory.resolve("catalog.json");
        JsonObject json = new JsonObject();
        json.addProperty("entity.zombie.1.name", "Walker <&>");

        ResourceIo.writeJson(file, json);

        // HTML escaping is disabled, so a translator sees the characters they typed rather than
        // escape sequences.
        String contents = Files.readString(file, StandardCharsets.UTF_8);
        assertTrue(contents.contains("Walker <&>"), contents);
        assertEquals(json, JsonParser.parseString(contents));
    }

    @Test
    void writesCompressedNbt(@TempDir Path directory) throws IOException {
        Path file = directory.resolve("structure.nbt");
        CompoundTag tag = new CompoundTag();
        tag.putString("author", "wtem");

        ResourceIo.writeNbt(file, tag);

        assertEquals(tag, NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap()));
    }

    @Test
    void reportsAWriteThatCannotSucceed(@TempDir Path directory) throws IOException {
        // A path whose parent is an existing regular file cannot be created, which is the closest
        // portable stand-in for a read-only destination.
        Path blocker = directory.resolve("blocker");
        Files.writeString(blocker, "");
        Path file = blocker.resolve("catalog.json");

        assertThrows(
                ResourceIo.ResourceIoException.class, () -> ResourceIo.writeString(file, "text"));
        assertFalse(Files.exists(file));
    }

    private static IoSupplier<InputStream> source(String contents) {
        return () -> new ByteArrayInputStream(contents.getBytes(StandardCharsets.UTF_8));
    }
}
