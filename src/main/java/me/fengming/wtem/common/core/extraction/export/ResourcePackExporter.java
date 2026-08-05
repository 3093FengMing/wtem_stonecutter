package me.fengming.wtem.common.core.extraction.export;

import com.google.gson.JsonObject;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import me.fengming.wtem.common.Wtem;
import me.fengming.wtem.common.config.WtemConfig;
import me.fengming.wtem.common.core.extraction.service.ExtractionSession;
import me.fengming.wtem.common.util.DirectoryPublisher;
import me.fengming.wtem.common.util.ResourceIo;
import net.minecraft.SharedConstants;
import net.minecraft.server.packs.PackType;

/** Builds a client resource pack containing the extracted language catalog.
 *
 * @author FengMing
 */
public final class ResourcePackExporter {
    private ResourcePackExporter() {}

    public static boolean export(
            WtemConfig.ResourcePack settings,
            Path levelRoot,
            Path languageFile,
            Path aiFile,
            ExtractionSession session) {
        if (settings == null || !settings.enabled()) return false;
        Path outputDirectory = levelRoot.resolve(settings.outputDirectory()).normalize();
        if (!outputDirectory.startsWith(levelRoot.normalize())) {
            throw new IllegalArgumentException("Resource-pack output escapes the world directory");
        }
        Path folderTarget = outputDirectory.resolve(settings.name()).normalize();
        Path zipTarget = outputDirectory.resolve(settings.name() + ".zip").normalize();
        if (!folderTarget.startsWith(outputDirectory) || !zipTarget.startsWith(outputDirectory)) {
            throw new IllegalArgumentException("Resource-pack name escapes the output directory");
        }

        Path staging = null;
        Path zipTemporary = null;
        try {
            Files.createDirectories(outputDirectory);
            staging = DirectoryPublisher.createStagingDirectory(folderTarget);
            writePackContents(staging, settings, languageFile, aiFile);

            if (settings.format() == WtemConfig.ResourcePack.Format.FOLDER
                    || settings.format() == WtemConfig.ResourcePack.Format.BOTH) {
                DirectoryPublisher.publish(staging, folderTarget);
                staging = null;
                session.recordModifiedResource();
            }

            if (settings.format() == WtemConfig.ResourcePack.Format.ZIP
                    || settings.format() == WtemConfig.ResourcePack.Format.BOTH) {
                Path zipParent = zipTarget.getParent();
                zipTemporary =
                        Files.createTempFile(
                                zipParent, "." + zipTarget.getFileName() + ".", ".tmp");
                Path source =
                        staging != null
                                ? staging
                                : folderTarget;
                writeZip(source, zipTemporary);
                moveReplacing(zipTemporary, zipTarget);
                zipTemporary = null;
                session.recordModifiedResource();
            }
            return true;
        } catch (IOException | RuntimeException exception) {
            session.diagnostics().record("resource_pack", outputDirectory.toString(), exception);
            Wtem.LOGGER.warn("Failed to export WTEM resource pack", exception);
            return false;
        } finally {
            if (staging != null) {
                try {
                    DirectoryPublisher.discard(staging);
                } catch (RuntimeException ignored) {
                    // Keep the original failure as the useful diagnostic.
                }
            }
            if (zipTemporary != null) {
                try {
                    Files.deleteIfExists(zipTemporary);
                } catch (IOException ignored) {
                    // Best effort cleanup.
                }
            }
        }
    }

    private static void writePackContents(
            Path root,
            WtemConfig.ResourcePack settings,
            Path languageFile,
            Path aiFile)
            throws IOException {
        JsonObject pack = new JsonObject();
        JsonObject metadata = new JsonObject();
        metadata.addProperty("pack_format", effectivePackFormat(settings.packFormat()));
        metadata.addProperty("description", settings.description());
        pack.add("pack", metadata);
        ResourceIo.writeJson(root.resolve("pack.mcmeta"), pack);

        Path languageTarget = root.resolve("assets/wtem/lang");
        Files.createDirectories(languageTarget);
        if (Files.isRegularFile(languageFile)) {
            Files.copy(languageFile, languageTarget.resolve(languageFile.getFileName()), StandardCopyOption.REPLACE_EXISTING);
        }
        if (aiFile != null && Files.isRegularFile(aiFile)) {
            Files.copy(aiFile, languageTarget.resolve(aiFile.getFileName()), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void writeZip(Path source, Path target) throws IOException {
        try (OutputStream output = Files.newOutputStream(target);
                ZipOutputStream zip = new ZipOutputStream(output)) {
            try (var paths = Files.walk(source)) {
                for (Path path : paths.filter(Files::isRegularFile).sorted().toList()) {
                    String entryName =
                            source.relativize(path).toString().replace('\\', '/');
                    zip.putNextEntry(new ZipEntry(entryName));
                    Files.copy(path, zip);
                    zip.closeEntry();
                }
            }
        }
    }

    private static void moveReplacing(Path source, Path target) throws IOException {
        try {
            Files.move(
                    source,
                    target,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static int effectivePackFormat(int configured) {
        if (configured > 0) return configured;
        try {
            SharedConstants.tryDetectVersion();
            Object version = SharedConstants.getCurrentVersion();
            try {
                Object format =
                        version
                                .getClass()
                                .getMethod("packVersion", PackType.class)
                                .invoke(version, PackType.CLIENT_RESOURCES);
                Object major = format.getClass().getMethod("major").invoke(format);
                if (major instanceof Number number && number.intValue() > 0) return number.intValue();
            } catch (ReflectiveOperationException ignored) {
                // Older mappings expose a direct integer accessor; try those below.
            }
            for (String methodName : new String[] {"getPackVersion", "packVersion", "getPackVersionId"}) {
                try {
                    Object value = version.getClass().getMethod(methodName).invoke(version);
                    if (value instanceof Number number && number.intValue() > 0) return number.intValue();
                } catch (ReflectiveOperationException ignored) {
                    // Try the next version-specific accessor.
                }
            }
        } catch (RuntimeException ignored) {
            // Use the conservative legacy fallback below.
        }
        return 34;
    }
}
