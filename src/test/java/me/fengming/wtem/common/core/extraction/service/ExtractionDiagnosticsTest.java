package me.fengming.wtem.common.core.extraction.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class ExtractionDiagnosticsTest {
    @Test
    void startsWithoutFailures() {
        ExtractionDiagnostics diagnostics = new ExtractionDiagnostics();

        assertFalse(diagnostics.hasFailures());
        assertEquals(List.of(), diagnostics.failures());
    }

    @Test
    void keepsEveryRecordedFailureInOrder() {
        ExtractionDiagnostics diagnostics = new ExtractionDiagnostics();
        RuntimeException first = new RuntimeException("first");
        RuntimeException second = new RuntimeException("second");

        diagnostics.record("datapack", "example:a.json", first);
        diagnostics.record("region", "r.0.0.mca", second);

        assertTrue(diagnostics.hasFailures());
        assertEquals(
                List.of(
                        new ExtractionDiagnostics.Failure("datapack", "example:a.json", first),
                        new ExtractionDiagnostics.Failure("region", "r.0.0.mca", second)),
                diagnostics.failures());
    }

    @Test
    void keepsSeparateEntriesForRepeatedFailuresOfTheSameResource() {
        ExtractionDiagnostics diagnostics = new ExtractionDiagnostics();

        diagnostics.record("datapack", "example:a.json", new RuntimeException("first"));
        diagnostics.record("datapack", "example:a.json", new RuntimeException("second"));

        assertEquals(2, diagnostics.failures().size());
    }

    @Test
    void handsOutAnImmutableView() {
        ExtractionDiagnostics diagnostics = new ExtractionDiagnostics();
        diagnostics.record("datapack", "example:a.json", new RuntimeException());

        List<ExtractionDiagnostics.Failure> failures = diagnostics.failures();

        assertThrows(UnsupportedOperationException.class, failures::clear);
    }

    @Test
    void collectsFailuresReportedFromSeveralThreads() throws Exception {
        // Data-pack resources are processed concurrently, so a failure on a worker thread has to
        // survive without a lock on the caller's side.
        ExtractionDiagnostics diagnostics = new ExtractionDiagnostics();
        int workers = 8;
        int perWorker = 50;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(workers);
        try {
            for (int worker = 0; worker < workers; worker++) {
                String scope = "worker" + worker;
                executor.execute(
                        () -> {
                            try {
                                start.await();
                            } catch (InterruptedException exception) {
                                Thread.currentThread().interrupt();
                                return;
                            }
                            for (int i = 0; i < perWorker; i++) {
                                diagnostics.record(scope, "resource" + i, new RuntimeException());
                            }
                        });
            }
            start.countDown();
            executor.shutdown();
            assertTrue(executor.awaitTermination(30, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }

        assertEquals(workers * perWorker, diagnostics.failures().size());
    }
}
