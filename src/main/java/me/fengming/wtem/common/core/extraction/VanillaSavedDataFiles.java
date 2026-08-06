package me.fengming.wtem.common.core.extraction;

import java.util.Locale;
import java.util.Set;

/**
 * Identifies SavedData files owned by Minecraft itself.
 *
 * <p>The SavedData path layout changed between supported Minecraft versions.  For example,
 * {@code scoreboard.dat} used to be directly below {@code data}, while newer versions can store
 * the same type as {@code minecraft/scoreboard.dat}.  The selection screen must therefore use the
 * SavedData type's well-known file name rather than treating a namespace directory as proof that a
 * file is vanilla.  This intentionally also excludes a custom file that uses one of these reserved
 * names: the file name alone is the only stable information available while discovering files.
 *
 * @author FengMing
 */
public final class VanillaSavedDataFiles {
    private static final Set<String> VANILLA_FILE_NAMES =
            Set.of("chunks.dat", "scoreboard.dat", "custom_boss_events.dat",
                "raids.dat", "raids_end.dat", "idcounts.dat",
                "random_sequences.dat", "structure_feature_indices.dat",
                "structures.dat", "game_rules.dat", "scheduled_events.dat",
                "stopwatches.dat", "wandering_trader.dat", "weather.dat",
                "world_clocks.dat", "world_gen_settings.dat");

    private VanillaSavedDataFiles() {}

    /** Returns whether {@code relativePath} is a known vanilla SavedData path. */
    public static boolean isVanilla(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) return false;

        String path = relativePath.trim().replace('\\', '/').toLowerCase(Locale.ROOT);
        while (path.startsWith("./")) path = path.substring(2);
        if (!path.endsWith(".dat")) return false;

        String fileName = path.substring(path.lastIndexOf('/') + 1);
        if (VANILLA_FILE_NAMES.contains(fileName)) return true;

        // Older versions stored the vanilla command storage as
        // command_storage_<namespace>.dat; newer versions use the minecraft namespace
        // directory.  A data pack may legitimately own another namespace (for example
        // cstore/command_storage.dat), so do not classify every namespaced file with this
        // basename as vanilla.
        if (fileName.startsWith("command_storage_")) return true;
        if (("command_storage.dat".equals(fileName) && path.indexOf('/') < 0)
                || "minecraft/command_storage.dat".equals(path)) return true;

        // 1.21+ stores map data under maps/<numeric id>.dat (possibly below a namespace
        // directory), while older versions used map_<id>.dat beside the other SavedData files.
        return isMapDataPath(path) || isMapFileName(fileName);
    }

    private static boolean isMapDataPath(String path) {
        int separator = path.lastIndexOf("maps/");
        if (separator < 0 || separator > 0 && path.charAt(separator - 1) != '/') return false;
        String name = path.substring(separator + "maps/".length());
        return "last_id.dat".equals(name)
                || (name.endsWith(".dat")
                        && isUnsignedInteger(name.substring(0, name.length() - ".dat".length())));
    }

    private static boolean isMapFileName(String fileName) {
        if (!fileName.startsWith("map_") || !fileName.endsWith(".dat")) return false;
        String id = fileName.substring("map_".length(), fileName.length() - ".dat".length());
        if (id.startsWith("-")) id = id.substring(1);
        return isUnsignedInteger(id);
    }

    private static boolean isUnsignedInteger(String value) {
        if (value == null || value.isEmpty()) return false;
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            if (character < '0' || character > '9') return false;
        }
        return true;
    }
}
