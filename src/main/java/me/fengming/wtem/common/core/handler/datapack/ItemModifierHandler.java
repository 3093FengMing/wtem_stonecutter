package me.fengming.wtem.common.core.handler.datapack;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.function.Function;
import me.fengming.wtem.common.core.extraction.TranslationContext;
import me.fengming.wtem.common.core.visitor.ItemTagVisitor;
import me.fengming.wtem.common.util.NbtUtils;
import me.fengming.wtem.common.util.ResourceIds;
import me.fengming.wtem.common.util.ResourceIo;
import me.fengming.wtem.common.util.TranslationUtils;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.IoSupplier;

/**
 * @author FengMing
 */
public class ItemModifierHandler extends NonExtraResourceHandler {
    public static final HandlerFactory FACTORY = ItemModifierHandler::new;

    public ItemModifierHandler(Function<Identifier, Path> filePath, Context context) {
        super("item_modifier", filePath, context);
    }

    @Override
    protected boolean innerHandle(Identifier rl, IoSupplier<InputStream> supplier) {
        var json = ResourceIo.readJson(supplier, "");
        JsonElement original = json.deepCopy();
        if (json.isJsonObject()) {
            json = processItemModifier(json.getAsJsonObject());
        } else if (json.isJsonArray()) {
            json = processItemModifiers(json.getAsJsonArray());
        }
        boolean changed = !json.equals(original);
        if (json.isJsonObject() || json.isJsonArray()) {
            changed |= JsonPatternSupport.apply(json, getPath(), rl);
        }
        if (changed) ResourceIo.writeJson(getFilePath(rl), json);
        return changed;
    }

    public static JsonArray processItemModifiers(JsonArray modifiers) {
        JsonArray array = new JsonArray();
        for (JsonElement element : modifiers) {
            array.add(
                    element.isJsonObject()
                            ? processItemModifier(element.getAsJsonObject())
                            : element);
        }
        return array;
    }

    public static JsonObject processItemModifier(JsonObject modifier) {
        if (!modifier.has("function") || !modifier.get("function").isJsonPrimitive()) return modifier;
        String function = ResourceIds.vanillaPath(modifier.get("function").getAsString());
        try (var ignored = TranslationContext.push(function)) {
            switch (function) {
                case "set_lore" -> translateLore(modifier);
                case "set_name" -> translateName(modifier);
                case "set_components" -> translateComponents(modifier);
                case "set_contents" -> processContents(modifier);
                default -> {
                    // Other functions carry no literal text that can be identified by schema.
                }
            }
        }
        return modifier;
    }

    private static void translateLore(JsonObject modifier) {
        if (!modifier.has("lore") || !modifier.get("lore").isJsonArray()) return;

        JsonArray array = new JsonArray();
        int index = 0;
        for (JsonElement element : modifier.getAsJsonArray("lore")) {
            try (var ignored = TranslationContext.push("line" + index++)) {
                array.add(TranslationUtils.translateLiteral(element));
            }
        }
        modifier.add("lore", array);
    }

    private static void translateName(JsonObject modifier) {
        if (!modifier.has("name")) return;
        modifier.add("name", TranslationUtils.translateLiteral(modifier.get("name")));
    }

    private static void translateComponents(JsonObject modifier) {
        if (!modifier.has("components") || !modifier.get("components").isJsonObject()) return;

        var compound = NbtUtils.fromJson(modifier.getAsJsonObject("components"));
        var visitor = new ItemTagVisitor();
        visitor.visitComponents(compound);
        if (!visitor.isChanged()) return;
        modifier.add("components", NbtUtils.toJson(compound));
    }

    private static void processContents(JsonObject modifier) {
        if (!modifier.has("entries") || !modifier.get("entries").isJsonArray()) return;

        var array = new JsonArray();
        for (JsonElement entry : modifier.getAsJsonArray("entries")) {
            array.add(
                    entry.isJsonObject()
                            ? LootTableHandler.processLootEntry(entry.getAsJsonObject())
                            : entry);
        }
        modifier.add("entries", array);
    }
}
