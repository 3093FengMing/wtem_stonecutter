package me.fengming.wtem.common.core.extraction.service;

import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.jetbrains.annotations.Nullable;

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

    /** Records an expected warning without manufacturing an exception and its stack trace. */
    public void recordWarning(String scope, String resource, String message) {
        this.failures.add(new Failure(scope, resource, null, message));
    }

    public boolean hasFailures() {
        return !this.failures.isEmpty();
    }

    public List<Failure> failures() {
        return List.copyOf(this.failures);
    }

    public record Failure(
            String scope, String resource, @Nullable Throwable cause, @Nullable String message) {
        public Failure(String scope, String resource, Throwable cause) {
            this(scope, resource, cause, null);
        }

        public Failure {
            scope = scope == null ? "" : scope;
            resource = resource == null ? "" : resource;
            message = message == null || message.isBlank() ? null : message;
        }

        /** Returns the short diagnostic text used when a warning has no throwable. */
        public String displayMessage() {
            if (this.message != null) return this.message;
            return this.cause == null ? "Extraction warning" : this.cause.getMessage();
        }
    }
}
