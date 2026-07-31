package me.fengming.wtem.common.core.handler.datapack;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Set;
import java.util.function.Function;
import me.fengming.wtem.common.util.ResourceIds;
import me.fengming.wtem.common.util.ResourceIo;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.IoSupplier;

/**
 * Recursively processes loot-table entries and functions without changing the root schema.
 *
 * @author FengMing
 */
public class LootTableHandler extends NonExtraResourceHandler {
    private static final Set<String> COMPOSITE_ENTRY_TYPES =
            Set.of("group", "alternatives", "sequence");

    public static final HandlerFactory FACTORY = LootTableHandler::new;

    public LootTableHandler(Function<Identifier, Path> filePath, Context context) {
        super("loot_table", filePath, context);
    }

    @Override
    protected boolean innerHandle(Identifier rl, IoSupplier<InputStream> supplier) {
        JsonElement source = ResourceIo.readJson(supplier, "");
        JsonElement original = source.deepCopy();
        if (!source.isJsonObject()) {
            return false;
        }

        JsonObject table = source.getAsJsonObject();
        processFunctions(table);
        if (table.has("pools") && table.get("pools").isJsonArray()) {
            for (JsonElement poolElement : table.getAsJsonArray("pools")) {
                if (!poolElement.isJsonObject()) continue;
                JsonObject pool = poolElement.getAsJsonObject();
                processFunctions(pool);
                processEntries(pool, "entries");
            }
        }
        boolean changed = !table.equals(original);
        if (changed) ResourceIo.writeJson(getFilePath(rl), table);
        return changed;
    }

    public static JsonObject processLootEntry(JsonObject entry) {
        processFunctions(entry);
        if (!entry.has("type")) return entry;

        if (!entry.get("type").isJsonPrimitive()) return entry;
        String type = ResourceIds.vanillaPath(entry.get("type").getAsString());
        if (COMPOSITE_ENTRY_TYPES.contains(type)) processEntries(entry, "children");
        return entry;
    }

    private static void processEntries(JsonObject parent, String field) {
        if (!parent.has(field) || !parent.get(field).isJsonArray()) return;

        JsonArray processed = new JsonArray();
        for (JsonElement element : parent.getAsJsonArray(field)) {
            processed.add(
                    element.isJsonObject()
                            ? processLootEntry(element.getAsJsonObject())
                            : element);
        }
        parent.add(field, processed);
    }

    private static void processFunctions(JsonObject parent) {
        if (!parent.has("functions") || !parent.get("functions").isJsonArray()) return;
        parent.add(
                "functions",
                ItemModifierHandler.processItemModifiers(parent.getAsJsonArray("functions")));
    }
}
