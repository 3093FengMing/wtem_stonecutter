package me.fengming.wtem.common.core.extraction.service;

/**
 * Lifecycle state of one world extraction task.
 *
 * @author FengMing
 */
public enum ExtractionStatus {
    READY,
    RUNNING,
    CANCELLING,
    SUCCEEDED,
    SUCCEEDED_WITH_WARNINGS,
    FAILED,
    CANCELLED;

    public boolean isTerminal() {
        return this == SUCCEEDED
                || this == SUCCEEDED_WITH_WARNINGS
                || this == FAILED
                || this == CANCELLED;
    }

    public boolean isSuccessful() {
        return this == SUCCEEDED || this == SUCCEEDED_WITH_WARNINGS;
    }
}
