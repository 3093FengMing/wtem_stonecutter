package me.fengming.wtem.common.core.extraction;

import static org.junit.jupiter.api.Assertions.assertEquals;

import me.fengming.wtem.common.core.extraction.service.ExtractionSession;
import me.fengming.wtem.common.core.extraction.service.ExtractionStatus;
import org.junit.jupiter.api.Test;

class ExtractionSessionTest {
    @Test
    void completionHonorsConcurrentCancellation() {
        ExtractionSession session = new ExtractionSession();
        session.start();
        session.requestCancellation();

        session.complete();

        assertEquals(ExtractionStatus.CANCELLED, session.status());
    }

    @Test
    void nonFatalFailuresProduceSuccessfulWarningStatus() {
        ExtractionSession session = new ExtractionSession();
        session.start();
        session.diagnostics().record("test", "resource", new IllegalStateException("warning"));

        session.complete();

        assertEquals(ExtractionStatus.SUCCEEDED_WITH_WARNINGS, session.status());
    }
}
