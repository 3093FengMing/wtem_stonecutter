package me.fengming.wtem.common.core.extraction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Test
    void exposesAiTranslationAsASeparateNonTerminalPhase() {
        ExtractionSession session = new ExtractionSession();
        session.start();

        assertTrue(session.beginAiTranslation(5, 2));
        assertEquals(ExtractionStatus.AI_TRANSLATING, session.status());

        session.recordAiBatch(3, 1);
        assertEquals(3, session.aiTranslationProgress().completedEntries());
        assertEquals(1, session.aiTranslationProgress().completedBatches());
        assertEquals(0.6F, session.aiTranslationProgress().progress());

        session.finishAiTranslation();
        assertEquals(ExtractionStatus.RUNNING, session.status());
        session.complete();
        assertEquals(ExtractionStatus.SUCCEEDED, session.status());
    }

    @Test
    void cancellationDuringAiTranslationCannotBecomeSuccess() {
        ExtractionSession session = new ExtractionSession();
        session.start();
        assertTrue(session.beginAiTranslation(10, 1));

        assertEquals(
                ExtractionSession.CancellationResult.REQUESTED,
                session.requestCancellation());
        session.complete();

        assertEquals(ExtractionStatus.CANCELLED, session.status());
    }
}
