package me.fengming.wtem.common.core.handler.datapack;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Function;
import me.fengming.wtem.common.core.TranslationContext;
import me.fengming.wtem.common.Wtem;
import me.fengming.wtem.common.core.ExtractionDiagnostics;
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
        try {
            innerHandle(rl, supplier);
        } catch (RuntimeException exception) {
            Wtem.LOGGER.error("Failed to process data-pack resource {}", rl, exception);
            if (this.context != null && this.context.diagnostics() != null) {
                this.context.diagnostics().record("datapack", rl.toString(), exception);
            }
        }
    }

    protected abstract void innerHandle(Identifier rl, IoSupplier<InputStream> supplier);

    public record Context(
            @Nullable List<String> list,
            @Nullable StructureTemplateManager structureManager,
            @Nullable ExtractionDiagnostics diagnostics) {
        public static Context of(
                List<String> targetPaths,
                StructureTemplateManager structureManager,
                ExtractionDiagnostics diagnostics) {
            return new Context(targetPaths, structureManager, diagnostics);
        }

        public Context withTargets(List<String> targets) {
            return new Context(List.copyOf(targets), this.structureManager, this.diagnostics);
        }
    }
}
