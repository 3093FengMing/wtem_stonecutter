package me.fengming.wtem.common.core.extraction.service;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.jetbrains.annotations.Nullable;

/**
 * Owns task state, cancellation, diagnostics, and counters for one extraction run.
 *
 * @author FengMing
 */
public final class ExtractionSession {
    public enum CancellationResult {
        REQUESTED,
        CANCELLED_BEFORE_START,
        IGNORED
    }

    private final AtomicReference<ExtractionStatus> status =
            new AtomicReference<>(ExtractionStatus.READY);
    private final ExtractionDiagnostics diagnostics = new ExtractionDiagnostics();
    private final AtomicInteger translatedEntries = new AtomicInteger();
    private final AtomicInteger modifiedChunks = new AtomicInteger();
    private final AtomicInteger modifiedResources = new AtomicInteger();
    private final AtomicInteger modifiedSavedData = new AtomicInteger();
    private final AtomicBoolean partialWorldWrite = new AtomicBoolean();
    private volatile Throwable fatalFailure;

    public boolean start() {
        return this.status.compareAndSet(ExtractionStatus.READY, ExtractionStatus.RUNNING);
    }

    public CancellationResult requestCancellation() {
        while (true) {
            ExtractionStatus current = this.status.get();
            if (current.isTerminal() || current == ExtractionStatus.CANCELLING) {
                return CancellationResult.IGNORED;
            }
            ExtractionStatus target =
                    current == ExtractionStatus.READY
                            ? ExtractionStatus.CANCELLED
                            : ExtractionStatus.CANCELLING;
            if (!this.status.compareAndSet(current, target)) continue;
            return current == ExtractionStatus.READY
                    ? CancellationResult.CANCELLED_BEFORE_START
                    : CancellationResult.REQUESTED;
        }
    }

    public boolean isCancellationRequested() {
        ExtractionStatus current = this.status.get();
        return current == ExtractionStatus.CANCELLING || current == ExtractionStatus.CANCELLED;
    }

    public void complete() {
        ExtractionStatus target =
                this.diagnostics.hasFailures()
                        ? ExtractionStatus.SUCCEEDED_WITH_WARNINGS
                        : ExtractionStatus.SUCCEEDED;
        while (true) {
            ExtractionStatus current = this.status.get();
            if (current == ExtractionStatus.RUNNING) {
                if (this.status.compareAndSet(current, target)) return;
                continue;
            }
            if (current == ExtractionStatus.CANCELLING) {
                if (this.status.compareAndSet(current, ExtractionStatus.CANCELLED)) return;
                continue;
            }
            return;
        }
    }

    public void completeCancellation() {
        this.status.compareAndSet(ExtractionStatus.CANCELLING, ExtractionStatus.CANCELLED);
    }

    public void fail(Throwable throwable) {
        this.fatalFailure = throwable;
        this.status.set(ExtractionStatus.FAILED);
    }

    public ExtractionStatus status() {
        return this.status.get();
    }

    public ExtractionDiagnostics diagnostics() {
        return this.diagnostics;
    }

    public @Nullable Throwable fatalFailure() {
        return this.fatalFailure;
    }

    public void setTranslatedEntries(int amount) {
        this.translatedEntries.set(Math.max(amount, 0));
    }

    public void recordModifiedChunk() {
        this.modifiedChunks.incrementAndGet();
        this.partialWorldWrite.set(true);
    }

    public void recordModifiedResource() {
        this.modifiedResources.incrementAndGet();
        this.partialWorldWrite.set(true);
    }

    public void recordModifiedResources(int amount) {
        if (amount <= 0) return;
        this.modifiedResources.addAndGet(amount);
        this.partialWorldWrite.set(true);
    }

    public void recordModifiedSavedData() {
        this.modifiedSavedData.incrementAndGet();
        this.partialWorldWrite.set(true);
    }

    public ExtractionReport report() {
        return new ExtractionReport(
                this.status.get(),
                this.translatedEntries.get(),
                this.modifiedChunks.get(),
                this.modifiedResources.get(),
                this.modifiedSavedData.get(),
                this.partialWorldWrite.get(),
                this.diagnostics.failures(),
                this.fatalFailure);
    }
}
