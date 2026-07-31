package me.fengming.wtem.common.core.handler.datapack;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.function.Function;
import me.fengming.wtem.common.core.TranslationContext;
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
    protected void innerHandle(Identifier rl, IoSupplier<InputStream> supplier) {
        var json = ResourceIo.readJson(supplier, "");
        if (json.isJsonObject()) {
            json = processItemModifier(json.getAsJsonObject());
        } else if (json.isJsonArray()) {
            json = processItemModifiers(json.getAsJsonArray());
        }
        ResourceIo.writeString(getFilePath(rl), GSON.toJson(json));
    }

    public static JsonArray processItemModifiers(JsonArray modifiers) {
        JsonArray array = new JsonArray();
        for (JsonElement element : modifiers) {
            array.add(processItemModifier(element.getAsJsonObject()));
        }
        return array;
    }

    public static JsonObject processItemModifier(JsonObject modifier) {
        if (!modifier.has("function")) return modifier;
        String function = modifier.get("function").getAsString();
        try (var ignored = TranslationContext.push(ResourceIds.path(function))) {
            switch (function) {
                case "minecraft:set_lore" -> {
                    var lore = modifier.get("lore").getAsJsonArray();
                    var array = new JsonArray();
                    for (JsonElement element : lore) {
                        array.add(TranslationUtils.translateLiteral(element));
                    }
                    modifier.add("lore", array);
                }
                case "minecraft:set_name" ->
                        modifier.add("name", TranslationUtils.translateLiteral(modifier.get("name")));
                case "minecraft:set_components" -> {
                    var compound = NbtUtils.fromJson(modifier.getAsJsonObject("components"));
                    new ItemTagVisitor().visitComponents(compound);
                    modifier.add("components", NbtUtils.toJson(compound));
                }
                case "minecraft:set_contents" -> {
                    var array = new JsonArray();
                    var entries = modifier.getAsJsonArray("entries");
                    for (JsonElement entry : entries) {
                        array.add(LootTableHandler.processLootEntry(entry.getAsJsonObject()));
                    }
                    modifier.add("entries", array);
                }
            }
        }
        return modifier;
    }
}
