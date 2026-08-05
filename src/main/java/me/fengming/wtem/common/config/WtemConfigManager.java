package me.fengming.wtem.common.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.List;
import me.fengming.wtem.common.Wtem;
import me.fengming.wtem.common.util.ResourceIo;

/**
 * Owns the on-disk WTEM configuration and its live, client-side reload.
 *
 * <p>The extractor takes a snapshot when it starts.  Reloading this manager therefore affects the
 * next extraction rather than changing a run halfway through (which would make paths and output
 * destinations inconsistent).
 *
 * @author FengMing
 */
public final class WtemConfigManager {
    private static final Object LOCK = new Object();
    private static Path directory;
    private static List<String> resourceDirectories = List.of();
    private static FileStamp stamp = FileStamp.missing();
    private static FileStamp reportedBadStamp = FileStamp.missing();
    private static int tickCounter;

    private WtemConfigManager() {}

    public static void initialize(Path configDirectory, Collection<String> directories) {
        synchronized (LOCK) {
            directory = configDirectory;
            resourceDirectories = List.copyOf(directories);
            WtemConfig config = WtemConfig.loadOrCreate(directory, resourceDirectories);
            WtemConfig.initialize(config);
            stamp = readStamp(file());
            reportedBadStamp = FileStamp.missing();
            tickCounter = 0;
        }
    }

    public static Path file() {
        Path current = directory;
        return current == null ? null : current.resolve(WtemConfig.FILE_NAME);
    }

    public static List<String> resourceDirectories() {
        return resourceDirectories;
    }

    /** Saves first, then activates the supplied configuration. */
    public static boolean saveAndActivate(WtemConfig config) {
        if (config == null) return false;
        synchronized (LOCK) {
            Path target = file();
            if (target == null) return false;
            try {
                ResourceIo.writeJson(target, config.toJson(resourceDirectories));
                WtemConfig.initialize(config);
                stamp = readStamp(target);
                reportedBadStamp = FileStamp.missing();
                return true;
            } catch (RuntimeException exception) {
                Wtem.LOGGER.error("Failed to save WTEM configuration {}", target, exception);
                return false;
            }
        }
    }

    /**
     * Polls the small config file.  A polling hook is deliberately used instead of a watcher thread:
     * it runs on the client tick, has no lifecycle leak, and works on every supported platform.
     */
    public static void tick() {
        synchronized (LOCK) {
            if (directory == null) return;
            if (++tickCounter < 20) return;
            tickCounter = 0;
            Path target = file();
            FileStamp current = readStamp(target);
            if (current.equals(stamp)) return;
            try {
                WtemConfig reloaded =
                        WtemConfig.fromJson(ResourceIo.readJson(() -> Files.newInputStream(target), ""));
                WtemConfig.initialize(reloaded);
                stamp = current;
                reportedBadStamp = FileStamp.missing();
                Wtem.LOGGER.info("Reloaded WTEM configuration from {}", target);
            } catch (RuntimeException exception) {
                // Keep the last known-good configuration.  Do not log the same half-written file on
                // every client tick; a later modification will be tried again.
                if (!current.equals(reportedBadStamp)) {
                    Wtem.LOGGER.warn("Failed to hot-reload WTEM configuration {}", target, exception);
                    reportedBadStamp = current;
                }
            }
        }
    }

    private static FileStamp readStamp(Path path) {
        if (path == null) return FileStamp.missing();
        try {
            if (!Files.exists(path)) return FileStamp.missing();
            byte[] bytes = Files.readAllBytes(path);
            return new FileStamp(fingerprint(bytes));
        } catch (IOException exception) {
            return FileStamp.unreadable();
        }
    }

    private static String fingerprint(byte[] bytes) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new AssertionError("SHA-256 is required by the Java runtime", exception);
        }
    }

    private record FileStamp(String digest) {
        private static FileStamp missing() {
            return new FileStamp("<missing>");
        }

        private static FileStamp unreadable() {
            return new FileStamp("<unreadable>");
        }
    }
}
