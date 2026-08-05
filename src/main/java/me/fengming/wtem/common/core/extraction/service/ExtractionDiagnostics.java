package me.fengming.wtem.common.core.extraction.service;

import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Thread-safe collection of non-fatal extraction failures.
 *
 * @author FengMing
 */
public final class ExtractionDiagnostics {
    private final ConcurrentLinkedQueue<Failure> failures = new ConcurrentLinkedQueue<>();

    public void record(String scope, String resource, Throwable cause) {
        this.failures.add(new Failure(scope, resource, cause));
    }

    public boolean hasFailures() {
        return !this.failures.isEmpty();
    }

    public List<Failure> failures() {
        return List.copyOf(this.failures);
    }

    public record Failure(String scope, String resource, Throwable cause) {}
}
