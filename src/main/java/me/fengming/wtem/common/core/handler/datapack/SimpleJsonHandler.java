package me.fengming.wtem.common.core.handler.datapack;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Function;
import me.fengming.wtem.common.util.ResourceIo;
import me.fengming.wtem.common.util.TranslationUtils;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.IoSupplier;

/**
 * @author FengMing
 */
public class SimpleJsonHandler extends ResourceHandler {
    public static HandlerFactory factory(String path, String... targetPaths) {
        List<String> targets = List.of(targetPaths);
        return (filePath, context) ->
                new SimpleJsonHandler(
                        path, filePath, context.set(targets, null));
    }

    public SimpleJsonHandler(String path, Function<Identifier, Path> filePath, Context context) {
        super(path, filePath, context);
    }

    @Override
    protected void innerHandle(Identifier rl, IoSupplier<InputStream> supplier) {
        ResourceIo.writeString(getFilePath(rl), processJsonFile(supplier, this.context.list()));
    }

    protected String processJsonFile(IoSupplier<InputStream> supplier, List<String> list) {
        if (list == null) return "";
        var jsonObj = ResourceIo.readJson(supplier, "").getAsJsonObject();
        for (String s : list) {
            TranslationUtils.translateJsonElement(jsonObj, s);
        }
        return GSON.toJson(jsonObj);
    }

    public static class AdvancementHandlerSimple extends SimpleJsonHandler {
        public static final HandlerFactory FACTORY = AdvancementHandlerSimple::new;

        public AdvancementHandlerSimple(Function<Identifier, Path> filePath, Context context) {
            super(
                    "advancement",
                    filePath,
                    context.set(List.of("display.title", "display.description"), null));
        }
    }

    public static class EnchantmentHandlerSimple extends SimpleJsonHandler {
        public static final HandlerFactory FACTORY = EnchantmentHandlerSimple::new;

        public EnchantmentHandlerSimple(Function<Identifier, Path> filePath, Context context) {
            super("enchantment", filePath, context.set(List.of("description"), null));
        }
    }
}
