package me.fengming.wtem.common.util;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.packs.resources.IoSupplier;

/**
 * Reads resource data and writes output through an atomic temporary-file replacement.
 *
 * @author FengMing
 */
public final class ResourceIo {
    private static final Gson GSON =
            new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private ResourceIo() {}

    public static JsonElement readJson(IoSupplier<InputStream> supplier, String path) {
        try (var reader = new InputStreamReader(supplier.get(), StandardCharsets.UTF_8)) {
            JsonElement root = JsonParser.parseReader(reader);
            if (path == null || path.isEmpty()) return root;

            JsonElement current = root;
            for (String name : path.split("\\.")) {
                if (!current.isJsonObject() || !current.getAsJsonObject().has(name)) {
                    throw new ResourceIoException("Missing JSON path: " + path);
                }
                current = current.getAsJsonObject().get(name);
            }
            return current;
        } catch (IOException | RuntimeException exception) {
            if (exception instanceof ResourceIoException resourceIoException) {
                throw resourceIoException;
            }
            throw new ResourceIoException("Failed to read JSON resource", exception);
        }
    }

    public static void writeString(Path path, String contents) {
        writeAtomically(
                path,
                temporary -> Files.writeString(temporary, contents, StandardCharsets.UTF_8));
    }

    public static void writeJson(Path path, JsonElement json) {
        writeString(path, GSON.toJson(json));
    }

    public static void writeNbt(Path path, CompoundTag tag) {
        writeAtomically(path, temporary -> NbtIo.writeCompressed(tag, temporary));
    }

    private static void writeAtomically(Path path, IoWriter writer) {
        Path temporary = null;
        try {
            Path parent = path.getParent();
            if (parent != null) Files.createDirectories(parent);
            Path temporaryDirectory = parent == null ? Path.of(".") : parent;
            temporary =
                    Files.createTempFile(
                            temporaryDirectory, path.getFileName().toString() + ".", ".tmp");
            writer.write(temporary);
            moveReplacing(temporary, path);
            temporary = null;
        } catch (IOException exception) {
            throw new ResourceIoException("Failed to write resource: " + path, exception);
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) {
                    // The original failure remains the useful diagnostic.
                }
            }
        }
    }

    private static void moveReplacing(Path source, Path target) throws IOException {
        try {
            Files.move(
                    source,
                    target,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    @FunctionalInterface
    private interface IoWriter {
        void write(Path path) throws IOException;
    }

    public static final class ResourceIoException extends RuntimeException {
        public ResourceIoException(String message) {
            super(message);
        }

        public ResourceIoException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
