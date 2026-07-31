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
import me.fengming.wtem.common.util.ResourceIds;
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
    protected boolean innerHandle(Identifier rl, IoSupplier<InputStream> supplier) {
        var json = ResourceIo.readJson(supplier, "");
        JsonElement original = json.deepCopy();
        if (json.isJsonObject()) {
            json = processPredicate(json.getAsJsonObject());
        } else if (json.isJsonArray()) {
            json = processPredicates(json.getAsJsonArray());
        }
        boolean changed = !json.equals(original);
        if (changed) ResourceIo.writeJson(getFilePath(rl), json);
        return changed;
    }

    public static JsonObject processPredicate(JsonObject predicate) {
        if (!predicate.has("condition") || !predicate.get("condition").isJsonPrimitive()) {
            return predicate;
        }

        String condition = ResourceIds.vanillaPath(predicate.get("condition").getAsString());
        switch (condition) {
            case "all_of", "any_of" -> processTerms(predicate);
            case "inverted" -> {
                if (!predicate.has("term") || !predicate.get("term").isJsonObject()) break;
                predicate.add("term", processPredicate(predicate.getAsJsonObject("term")));
            }
            case "match_tool" -> processMatchTool(predicate);
            default -> {
                // Other conditions carry no literal text that can be identified by schema.
            }
        }
        return predicate;
    }

    public static JsonArray processPredicates(JsonArray predicates) {
        var array = new JsonArray();
        for (JsonElement predicate : predicates) {
            array.add(
                    predicate.isJsonObject()
                            ? processPredicate(predicate.getAsJsonObject())
                            : predicate);
        }
        return array;
    }

    private static void processTerms(JsonObject predicate) {
        if (!predicate.has("terms") || !predicate.get("terms").isJsonArray()) return;
        predicate.add("terms", processPredicates(predicate.getAsJsonArray("terms")));
    }

    private static void processMatchTool(JsonObject predicate) {
        if (!predicate.has("predicate") || !predicate.get("predicate").isJsonObject()) return;

        JsonObject itemPredicate = predicate.getAsJsonObject("predicate");
        if (!itemPredicate.has("components") || !itemPredicate.get("components").isJsonObject()) {
            return;
        }

        var compound = NbtUtils.fromJson(itemPredicate.getAsJsonObject("components"));
        var visitor = new ItemTagVisitor();
        visitor.visitComponents(compound);
        if (!visitor.isChanged()) return;
        itemPredicate.add("components", NbtUtils.toJson(compound));
    }
}
