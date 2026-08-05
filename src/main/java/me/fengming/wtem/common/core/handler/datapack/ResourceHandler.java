package me.fengming.wtem.common.core.handler.datapack;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Function;
import me.fengming.wtem.common.core.extraction.TranslationContext;
import me.fengming.wtem.common.Wtem;
import me.fengming.wtem.common.core.extraction.service.ExtractionDiagnostics;
import me.fengming.wtem.common.core.extraction.service.ExtractionSession;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.IoSupplier;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import org.jetbrains.annotations.Nullable;

/**
 * @author FengMing
 */
public abstract class ResourceHandler {
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

    public boolean accepts(Identifier rl) {
        return rl.getPath().endsWith(fileExtension());
    }

    protected String fileExtension() {
        return ".json";
    }

    public boolean handle(Identifier rl, IoSupplier<InputStream> supplier) {
        try (var transaction = TranslationContext.beginTransaction();
                var ignored = TranslationContext.pushLocation(rl.toString())) {
            int recordsBefore = TranslationContext.recordCount();
            String p = rl.getPath();
            int extension = p.lastIndexOf('.');
            if (extension >= 0) p = p.substring(0, extension);
            String s = rl.getNamespace() + "." + p;
            s = s.replace("/", ".");
            TranslationContext.setKey("datapack." + s);

            boolean changed = innerHandle(rl, supplier);
            if (!changed) {
                if (TranslationContext.hasOnlyCatalogEntriesSince(recordsBefore)) {
                    transaction.commit();
                }
                return false;
            }
            transaction.commit();
            return true;
        } catch (RuntimeException exception) {
            Wtem.LOGGER.error("Failed to process data-pack resource {}", rl, exception);
            if (this.context != null && this.context.diagnostics() != null) {
                this.context.diagnostics().record("datapack", rl.toString(), exception);
            }
            return false;
        }
    }

    protected abstract boolean innerHandle(Identifier rl, IoSupplier<InputStream> supplier);

    public record Context(
            @Nullable List<String> targetPaths,
            @Nullable StructureTemplateManager structureManager,
            @Nullable RegistryAccess registries,
            @Nullable ExtractionSession session) {
        public static Context of(
                List<String> targetPaths,
                StructureTemplateManager structureManager,
                RegistryAccess registries,
                ExtractionSession session) {
            return new Context(targetPaths, structureManager, registries, session);
        }

        public Context withTargets(List<String> targets) {
            return new Context(
                    List.copyOf(targets),
                    this.structureManager,
                    this.registries,
                    this.session);
        }

        public @Nullable ExtractionDiagnostics diagnostics() {
            return this.session == null ? null : this.session.diagnostics();
        }
    }
}
