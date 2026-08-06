package me.fengming.wtem.common.core.extraction.service;

/**
 * Immutable progress snapshot for the optional AI translation stage.
 *
 * <p>The entry count is the useful user-facing measure. The request count is retained in this
 * progress shape for compatibility and is one for every non-empty translation run.
 *
 * @author FengMing
 */
public record AiTranslationProgress(
        int completedEntries,
        int totalEntries,
        int completedBatches,
        int totalBatches,
        float progress) {
    public AiTranslationProgress {
        completedEntries = Math.max(0, completedEntries);
        totalEntries = Math.max(0, totalEntries);
        completedBatches = Math.max(0, completedBatches);
        totalBatches = Math.max(0, totalBatches);
        completedEntries = Math.min(completedEntries, totalEntries);
        completedBatches = Math.min(completedBatches, totalBatches);
        progress = totalEntries == 0
                ? 0.0F
                : Math.clamp(completedEntries / (float) totalEntries, 0.0F, 1.0F);
    }

    public static AiTranslationProgress empty() {
        return new AiTranslationProgress(0, 0, 0, 0, 0.0F);
    }
}
