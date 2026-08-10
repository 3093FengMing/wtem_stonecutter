package me.fengming.wtem.common.core.handler.datapack;

import com.google.gson.JsonElement;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Function;
import me.fengming.wtem.common.util.ResourceIo;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.IoSupplier;

/**
 * @author FengMing
 */
public class SimpleJsonHandler extends ResourceHandler {
    public static HandlerFactory factory(String path, String... targetPaths) {
        List<String> targets = List.of(targetPaths);
        return (filePath, context) ->
                new SimpleJsonHandler(path, filePath, context.withTargets(targets));
    }

    public SimpleJsonHandler(String path, Function<Identifier, Path> filePath, Context context) {
        super(path, filePath, context);
    }

    @Override
    protected boolean innerHandle(Identifier rl, IoSupplier<InputStream> supplier) {
        List<String> list = this.context.targetPaths();
        if (list == null) throw new IllegalStateException("Missing component target paths");
        JsonElement root = ResourceIo.readJson(supplier, "");
        if (!root.isJsonObject() && !(list.isEmpty() && root.isJsonArray())) {
            throw new IllegalStateException("Resource root is not a JSON object: " + rl);
        }

        boolean changed = JsonPatternSupport.apply(root, getPath(), rl, list);
        if (changed) ResourceIo.writeJson(getFilePath(rl), root);
        return changed;
    }

}
