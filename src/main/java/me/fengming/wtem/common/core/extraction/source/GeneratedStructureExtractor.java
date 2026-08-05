package me.fengming.wtem.common.core.extraction.source;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import me.fengming.wtem.common.Wtem;
import me.fengming.wtem.common.core.extraction.TranslationContext;
import me.fengming.wtem.common.core.extraction.service.ExtractionSession;
import me.fengming.wtem.common.core.handler.StructureTemplateWHandler;
import me.fengming.wtem.common.util.ResourceIo;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;

/**
 * Extracts only structures physically stored in the world's generated directory.
 *
 * @author FengMing
 */
public final class GeneratedStructureExtractor {
    private final Path generatedRoot;
    private final String structureDirectory;
    private final ExtractionSession session;

    public GeneratedStructureExtractor(
            Path generatedRoot,
            String structureDirectory,
            ExtractionSession session) {
        this.generatedRoot = generatedRoot.normalize();
        this.structureDirectory = structureDirectory;
        this.session = session;
    }

    public void extract() {
        if (!Files.isDirectory(this.generatedRoot)) return;

        for (Path namespaceDirectory : listDirectories(this.generatedRoot)) {
            if (this.session.isCancellationRequested()) return;
            Path structures = namespaceDirectory.resolve(this.structureDirectory);
            if (!Files.isDirectory(structures)) continue;

            for (Path structureFile : listStructureFiles(structures)) {
                if (this.session.isCancellationRequested()) return;
                extractOne(namespaceDirectory.getFileName().toString(), structures, structureFile);
            }
        }
    }

    private void extractOne(String namespace, Path structures, Path structureFile) {
        String relative = structures.relativize(structureFile).toString().replace('\\', '/');
        String path = relative.substring(0, relative.length() - ".nbt".length());
        String resource = namespace + ":" + path;
        try (var transaction = TranslationContext.beginTransaction();
                var ignored = TranslationContext.pushLocation(resource);
                var input = Files.newInputStream(structureFile)) {
            int recordsBefore = TranslationContext.recordCount();
            var source = NbtIo.readCompressed(input, NbtAccounter.unlimitedHeap());
            StructureTemplateWHandler.Result result = new StructureTemplateWHandler().process(source);
            if (!result.changed()) {
                if (TranslationContext.hasOnlyCatalogEntriesSince(recordsBefore)) {
                    transaction.commit();
                }
                return;
            }
            ResourceIo.writeNbt(structureFile, result.tag());
            transaction.commit();
            this.session.recordModifiedResource();
        } catch (IOException | RuntimeException exception) {
            this.session.diagnostics().record("generated_structure", resource, exception);
            Wtem.LOGGER.error("Failed to process generated structure {}", resource, exception);
        }
    }

    private static List<Path> listDirectories(Path root) {
        try (var paths = Files.list(root)) {
            return paths.filter(Files::isDirectory)
                    .sorted(Comparator.comparing(Path::toString))
                    .toList();
        } catch (IOException exception) {
            throw new ResourceIo.ResourceIoException(
                    "Failed to list generated structure namespaces: " + root, exception);
        }
    }

    private static List<Path> listStructureFiles(Path root) {
        try (var paths = Files.walk(root)) {
            return paths.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".nbt"))
                    .sorted(Comparator.comparing(Path::toString))
                    .toList();
        } catch (IOException exception) {
            throw new ResourceIo.ResourceIoException(
                    "Failed to list generated structures: " + root, exception);
        }
    }
}
