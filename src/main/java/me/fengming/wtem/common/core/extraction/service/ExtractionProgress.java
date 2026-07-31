package me.fengming.wtem.common.core.extraction.service;

import java.util.List;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/**
 * A consistent, read-only progress snapshot for the client screen.
 *
 * @author FengMing
 */
public record ExtractionProgress(
        int totalChunks,
        int converted,
        int skipped,
        float totalProgress,
        List<DimensionProgress> dimensions) {
    public ExtractionProgress {
        dimensions = List.copyOf(dimensions);
    }

    public static ExtractionProgress empty() {
        return new ExtractionProgress(0, 0, 0, 0.0F, List.of());
    }

    public record DimensionProgress(ResourceKey<Level> level, float progress) {}
}
