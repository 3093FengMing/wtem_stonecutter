package me.fengming.wtem.common.core.extraction.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import me.fengming.wtem.common.Wtem;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.WorldStem;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.repository.PackSource;

/** Values the extraction configuration screen can present as explicit selection toggles.
 *
 * @author FengMing
 */
public record ExtractionChoices(
        List<String> datapacks,
        List<String> entities,
        List<String> blockEntities,
        List<String> storageFiles) {
    public static final ExtractionChoices EMPTY =
            new ExtractionChoices(List.of(), List.of(), List.of(), List.of());

    public ExtractionChoices {
        datapacks = immutableSorted(datapacks);
        entities = immutableSorted(entities);
        blockEntities = immutableSorted(blockEntities);
        storageFiles = immutableSorted(storageFiles);
    }

    public static ExtractionChoices discover(WorldStem worldStem, Path worldRoot) {
        if (worldStem == null || worldRoot == null) return EMPTY;
        List<String> packs =
                worldStem.resourceManager().listPacks()
                        .filter(pack -> pack.location().source() == PackSource.WORLD)
                        .map(PackResources::packId)
                        .filter(packId -> !isGeneratedCompanionPack(packId))
                        .toList();
        List<String> entityTypes =
                BuiltInRegistries.ENTITY_TYPE.keySet().stream()
                        .map(Object::toString)
                        .toList();
        List<String> blockEntityTypes =
                BuiltInRegistries.BLOCK_ENTITY_TYPE.keySet().stream()
                        .map(Object::toString)
                        .toList();
        return new ExtractionChoices(
                packs, entityTypes, blockEntityTypes, savedDataFiles(worldRoot.resolve("data")));
    }

    static List<String> savedDataFiles(Path dataDirectory) {
        if (!Files.isDirectory(dataDirectory)) return List.of();
        try (var paths = Files.walk(dataDirectory)) {
            return paths.filter(Files::isRegularFile)
                    .filter(
                            path ->
                                    path.getFileName()
                                            .toString()
                                            .toLowerCase(java.util.Locale.ROOT)
                                            .endsWith(".dat"))
                    .map(
                            path ->
                                    dataDirectory
                                            .relativize(path)
                                            .toString()
                                            .replace('\\', '/'))
                    .filter(path -> !VanillaSavedDataFiles.isVanilla(path))
                    .sorted()
                    .toList();
        } catch (IOException exception) {
            Wtem.LOGGER.warn("Failed to discover saved-data files in {}", dataDirectory, exception);
            return List.of();
        }
    }

    private static List<String> immutableSorted(List<String> values) {
        if (values == null) return List.of();
        return values.stream()
                .filter(value -> value != null && !value.isBlank())
                .distinct()
                .sorted(Comparator.naturalOrder())
                .toList();
    }

    private static boolean isGeneratedCompanionPack(String packId) {
        if (packId == null || !packId.endsWith("_wtem")) return false;
        int marker = packId.length() - 14;
        if (marker < 0 || packId.charAt(marker) != '_') return false;
        for (int index = marker + 1; index < marker + 9; index++) {
            char character = packId.charAt(index);
            if (!((character >= '0' && character <= '9')
                    || (character >= 'a' && character <= 'f'))) return false;
        }
        return packId.charAt(marker + 9) == '_';
    }
}
