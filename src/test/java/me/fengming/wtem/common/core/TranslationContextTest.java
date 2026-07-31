package me.fengming.wtem.common.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TranslationContextTest {
    @BeforeEach
    void setUp() {
        TranslationContext.clear();
    }

    @AfterEach
    void tearDown() {
        TranslationContext.release();
    }

    @Test
    void reusesKeyForDuplicateTextByDefault() {
        TranslationContext.setKey("entity.zombie.1.name");
        String first = TranslationContext.addEntry("Guard");

        TranslationContext.setKey("entity.skeleton.1.name");
        String second = TranslationContext.addEntry("Guard");

        assertEquals(first, second);
        assertEquals(Map.of("entity.zombie.1.name", "Guard"), TranslationContext.snapshot());
    }

    @Test
    void allocatesSeparateKeysWhenDuplicatesAreEnabled() {
        TranslationContext.setKeepDuplicates(true);
        TranslationContext.setKey("item.stick.1.name");
        String first = TranslationContext.addEntry("Marker");
        String second = TranslationContext.addEntry("Marker");

        assertNotEquals(first, second);
        assertEquals(2, TranslationContext.snapshot().size());
    }

    @Test
    void restoresPathAfterNestedScope() {
        TranslationContext.setKey("entity.villager.1");
        try (var ignored = TranslationContext.push("offers.0")) {
            assertEquals("entity.villager.1.offers.0", TranslationContext.getKey());
        }

        assertEquals("entity.villager.1", TranslationContext.getKey());
    }

    @Test
    void isolatesConcurrentExtractionState() throws Exception {
        TranslationContext.setKey("main.name");
        TranslationContext.addEntry("Main");

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<Map<String, String>> worker =
                    executor.submit(
                            () -> {
                                TranslationContext.clear();
                                try {
                                    TranslationContext.setKey("worker.name");
                                    TranslationContext.addEntry("Worker");
                                    return TranslationContext.snapshot();
                                } finally {
                                    TranslationContext.release();
                                }
                            });

            assertEquals(Map.of("worker.name", "Worker"), worker.get());
            assertEquals(Map.of("main.name", "Main"), TranslationContext.snapshot());
            assertFalse(TranslationContext.snapshot().containsKey("worker.name"));
        } finally {
            executor.shutdownNow();
        }
    }
}
