package me.fengming.wtem.common.util;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import me.fengming.wtem.common.Wtem;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.packs.resources.IoSupplier;

/** Reads and writes extracted resource data.
 * @author FengMing*/
public final class ResourceIo {
    private static final Gson GSON =
            new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private ResourceIo() {}

    public static JsonElement readJson(IoSupplier<InputStream> supplier, String path) {
        try (var reader = new InputStreamReader(supplier.get(), StandardCharsets.UTF_8)) {
            JsonElement json = JsonParser.parseReader(reader);
            if (!json.isJsonObject() || path == null || path.isEmpty()) return json;

            JsonElement current = json;
            for (String name : path.split("\\.")) {
                if (!current.isJsonObject() || !current.getAsJsonObject().has(name))
                    return new JsonObject();
                current = current.getAsJsonObject().get(name);
            }
            return current;
        } catch (Exception e) {
            Wtem.LOGGER.error("Failed to parse JSON", e);
            return new JsonObject();
        }
    }

    public static void writeString(Path path, String contents) {
        try {
            createParentDirectories(path);
            Files.writeString(path, contents, StandardCharsets.UTF_8);
        } catch (IOException e) {
            Wtem.LOGGER.error("Failed to write text to {}", path, e);
        }
    }

    public static void writeJson(Path path, JsonElement json) {
        writeString(path, GSON.toJson(json));
    }

    public static void writeNbt(Path path, CompoundTag tag) {
        try {
            createParentDirectories(path);
            NbtIo.writeCompressed(tag, path);
        } catch (IOException e) {
            Wtem.LOGGER.error("Failed to write NBT to {}", path, e);
        }
    }

    private static void createParentDirectories(Path path) throws IOException {
        Path parent = path.getParent();
        if (parent != null) Files.createDirectories(parent);
    }
}
