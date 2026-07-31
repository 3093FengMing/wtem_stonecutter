package me.fengming.wtem.common.core.handler.datapack;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Function;
import me.fengming.wtem.common.core.TranslationContext;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.IoSupplier;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import org.jetbrains.annotations.Nullable;

/**
 * @author FengMing
 */
public abstract class ResourceHandler {
    protected static final Gson GSON =
            new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private final String path;
    private final Function<Identifier, Path> filePath;
    protected final Context context;

    public ResourceHandler(String path, Function<Identifier, Path> filePath, Context context) {
        this.path = path;
        this.filePath = filePath;
        this.context = context;
    }

    public String getPath() {
        return this.path;
    }

    public Path getFilePath(Identifier rl) {
        return this.filePath.apply(rl);
    }

    public void handle(Identifier rl, IoSupplier<InputStream> supplier) {
        String p = rl.getPath();
        int extension = p.lastIndexOf('.');
        if (extension >= 0) p = p.substring(0, extension);
        String s = rl.getNamespace() + "." + p;
        s = s.replace("/", ".");
        TranslationContext.setKey("datapack." + s);
        innerHandle(rl, supplier);
    }

    protected abstract void innerHandle(Identifier rl, IoSupplier<InputStream> supplier);

    public record Context(
            @Nullable List<String> list, @Nullable StructureTemplateManager structureManager) {
        public static Context of(List<String> targetPaths, StructureTemplateManager structureManager) {
            return new Context(targetPaths, structureManager);
        }

        public Context set(List<String> list, StructureTemplateManager structureManager) {
            if (this.list == null && this.structureManager == null)
                return new Context(list, structureManager);
            if (this.list != null && this.structureManager == null)
                return new Context(this.list, structureManager);
            if (this.list == null) return new Context(list, this.structureManager);
            return new Context(this.list, this.structureManager);
        }
    }
}
