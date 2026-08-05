package me.fengming.wtem.common.core.extraction.service;

import java.util.List;
import org.jetbrains.annotations.Nullable;

/**
 * Immutable summary exposed to the UI after, and while, an extraction is running.
 *
 * @author FengMing
 */
public record ExtractionReport(
        ExtractionStatus status,
        int translatedEntries,
        int modifiedChunks,
        int modifiedResources,
        int modifiedSavedData,
        boolean partialWorldWrite,
        List<ExtractionDiagnostics.Failure> failures,
        @Nullable Throwable fatalFailure) {
    public ExtractionReport {
        failures = List.copyOf(failures);
    }
}
