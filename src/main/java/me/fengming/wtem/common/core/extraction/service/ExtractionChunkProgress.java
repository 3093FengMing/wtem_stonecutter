package me.fengming.wtem.common.core.extraction.service;

/**
 * Accumulates the chunk counters exposed by the extraction screen across data-fix stages.
 *
 * <p>Minecraft's {@code UpgradeProgress.reset()} intentionally starts a new region pass at zero.
 * WTEM runs more than one pass (block entities and entities), so the screen needs a small
 * run-scoped accumulator in front of that API. The worker calls {@link #beginStage(int, int, int)}
 * immediately before resetting the game progress object; the screen calls {@link #snapshot(int,
 * int, int)} with the current stage values on every frame.
 *
 * @author FengMing
 */
public final class ExtractionChunkProgress {
    private int completedTotal;
    private int completedConverted;
    private int completedSkipped;
    private boolean stageStarted;

    /** Adds the values of the stage that is about to be replaced and opens the next stage. */
    public synchronized void beginStage(int currentTotal, int currentConverted, int currentSkipped) {
        if (this.stageStarted) {
            this.completedTotal += Math.max(0, currentTotal);
            this.completedConverted += Math.max(0, currentConverted);
            this.completedSkipped += Math.max(0, currentSkipped);
        }
        this.stageStarted = true;
    }

    /** Returns a consistent cumulative view using the live values of the current stage. */
    public synchronized Snapshot snapshot(
            int currentTotal, int currentConverted, int currentSkipped) {
        int total = this.completedTotal + Math.max(0, currentTotal);
        int converted = this.completedConverted + Math.max(0, currentConverted);
        int skipped = this.completedSkipped + Math.max(0, currentSkipped);
        float progress =
                total <= 0
                        ? 0.0F
                        : Math.clamp((converted + skipped) / (float) total, 0.0F, 1.0F);
        return new Snapshot(total, converted, skipped, progress);
    }

    public record Snapshot(int totalChunks, int converted, int skipped, float totalProgress) {}
}
