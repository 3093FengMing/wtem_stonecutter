package me.fengming.wtem.common.core.handler.datapack;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.function.Function;
import me.fengming.wtem.common.core.visitor.ItemTagVisitor;
import me.fengming.wtem.common.util.NbtUtils;
import me.fengming.wtem.common.util.ResourceIo;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.IoSupplier;

/**
 * @author FengMing
 */
public class PredicateHandler extends NonExtraResourceHandler {
    public static final HandlerFactory FACTORY = PredicateHandler::new;

    public PredicateHandler(Function<Identifier, Path> filePath, Context context) {
        super("predicate", filePath, context);
    }

    @Override
    protected void innerHandle(Identifier rl, IoSupplier<InputStream> supplier) {
        var json = ResourceIo.readJson(supplier, "");
        if (json.isJsonObject()) {
            json = processPredicate(json.getAsJsonObject());
        } else if (json.isJsonArray()) {
            json = processPredicates(json.getAsJsonArray());
        }
        ResourceIo.writeString(getFilePath(rl), GSON.toJson(json));
    }

    public static JsonObject processPredicate(JsonObject predicate) {
        if (!predicate.has("condition")) return predicate;
        String condition = predicate.get("condition").getAsString();
        switch (condition) {
            case "all_of", "any_of" -> {
                var array = processPredicates(predicate.getAsJsonArray("terms"));
                predicate.remove("terms");
                predicate.add("terms", array);
            }
            case "inverted" -> {
                var object = processPredicate(predicate.getAsJsonObject("term"));
                predicate.add("term", object);
            }
            case "match_tool" -> {
                var components = predicate.getAsJsonObject("predicate").getAsJsonObject("components");
                var compound = NbtUtils.fromJson(components);
                new ItemTagVisitor().visitComponents(compound);
                predicate.getAsJsonObject("predicate").add("components", NbtUtils.toJson(compound));
            }
        }
        return predicate;
    }

    public static JsonArray processPredicates(JsonArray predicates) {
        var array = new JsonArray();
        for (JsonElement predicate : predicates) {
            array.add(processPredicate(predicate.getAsJsonObject()));
        }
        return array;
    }
}
