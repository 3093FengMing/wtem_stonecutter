package me.fengming.wtem.common.util;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.UUID;

/**
 * Publishes a generated directory only after all of its contents have been staged successfully.
 *
 * @author FengMing
 */
public final class DirectoryPublisher {
    private DirectoryPublisher() {}

    public static Path createStagingDirectory(Path target) {
        Path absoluteTarget = target.toAbsolutePath().normalize();
        Path parent = absoluteTarget.getParent();
        if (parent == null) throw new IllegalArgumentException("Output directory has no parent: " + target);

        try {
            Files.createDirectories(parent);
            return Files.createTempDirectory(parent, "." + absoluteTarget.getFileName() + ".staging-");
        } catch (IOException exception) {
            throw new ResourceIo.ResourceIoException(
                    "Failed to create staging directory for: " + target, exception);
        }
    }

    public static void publish(Path staging, Path target) {
        Path absoluteStaging = staging.toAbsolutePath().normalize();
        Path absoluteTarget = target.toAbsolutePath().normalize();
        Path parent = absoluteTarget.getParent();
        if (parent == null || !parent.equals(absoluteStaging.getParent())) {
            throw new IllegalArgumentException("Staging and output directories must be siblings");
        }
        if (!Files.isDirectory(absoluteStaging) || absoluteStaging.equals(absoluteTarget)) {
            throw new IllegalArgumentException("Invalid staging directory: " + staging);
        }

        Path backup =
                parent.resolve(
                        "." + absoluteTarget.getFileName() + ".backup-" + UUID.randomUUID());
        boolean hasBackup = false;
        try {
            if (Files.exists(absoluteTarget)) {
                move(absoluteTarget, backup);
                hasBackup = true;
            }
            move(absoluteStaging, absoluteTarget);
        } catch (IOException publishFailure) {
            if (hasBackup && !Files.exists(absoluteTarget)) {
                try {
                    move(backup, absoluteTarget);
                    hasBackup = false;
                } catch (IOException restoreFailure) {
                    publishFailure.addSuppressed(restoreFailure);
                }
            }
            throw new ResourceIo.ResourceIoException(
                    "Failed to publish directory: " + target, publishFailure);
        } finally {
            if (hasBackup) discardQuietly(backup);
        }
    }

    public static void discard(Path directory) {
        if (directory == null || !Files.exists(directory)) return;
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        } catch (IOException exception) {
            throw new ResourceIo.ResourceIoException(
                    "Failed to discard staged directory: " + directory, exception);
        }
    }

    private static void discardQuietly(Path directory) {
        try {
            discard(directory);
        } catch (RuntimeException ignored) {
            // Publication has already succeeded; a stale backup is safer than rolling it back.
        }
    }

    private static void move(Path source, Path target) throws IOException {
        try {
            Files.move(
                    source,
                    target,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
