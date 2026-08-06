package me.fengming.wtem.common.core.extraction.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ExtractionChunkProgressTest {
    @Test
    void keepsTheFirstStageWhenTheSecondStageStartsAtZero() {
        ExtractionChunkProgress progress = new ExtractionChunkProgress();

        progress.beginStage(100, 60, 40);
        assertEquals(
                new ExtractionChunkProgress.Snapshot(100, 60, 40, 1.0F),
                progress.snapshot(100, 60, 40));

        // This is the point immediately before UpgradeProgress.reset() for the next pass.
        progress.beginStage(100, 60, 40);
        assertEquals(
                new ExtractionChunkProgress.Snapshot(150, 60, 40, 0.6666667F),
                progress.snapshot(50, 0, 0));
        assertEquals(
                new ExtractionChunkProgress.Snapshot(150, 60, 40, 0.6666667F),
                progress.snapshot(50, 0, 0));
    }

    @Test
    void addsCurrentStageValuesToEarlierValuesMonotonically() {
        ExtractionChunkProgress progress = new ExtractionChunkProgress();

        progress.beginStage(4, 1, 1);
        progress.beginStage(4, 1, 1);

        assertEquals(
                new ExtractionChunkProgress.Snapshot(7, 3, 1, 4.0F / 7.0F),
                progress.snapshot(3, 2, 0));
    }
}
