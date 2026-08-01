package me.fengming.wtem.common.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import me.fengming.wtem.common.config.WtemConfig;
import me.fengming.wtem.common.core.extraction.table.ExtractionOrigin;
import me.fengming.wtem.common.core.extraction.table.ExtractionRecord;
import me.fengming.wtem.common.core.extraction.TranslationContext;
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
    void appliesConfiguredKeyReusePolicyPerKey() {
        TranslationContext.setKeyReuse(
                new WtemConfig.KeyReuse(true, Map.of("datapack.", false)));

        TranslationContext.setKey("entity.zombie.1.name");
        assertEquals("entity.zombie.1.name", TranslationContext.addEntry("Guard"));
        TranslationContext.setKey("entity.skeleton.1.name");
        assertEquals("entity.zombie.1.name", TranslationContext.addEntry("Guard"));

        // Reuse is disabled for this prefix, so the text is extracted again under its own key even
        // though an entry with the same text already exists.
        TranslationContext.setKey("datapack.example.function");
        assertEquals("datapack.example.function", TranslationContext.addEntry("Guard"));
    }

    @Test
    void drawsRandomKeysThatCarryNoContext() {
        TranslationContext.setKeepDuplicates(true);
        TranslationContext.setKeyNaming(
                new WtemConfig.KeyNaming(WtemConfig.KeyNaming.Scheme.RANDOM, 6));
        TranslationContext.setKey("entity.zombie.1.name");

        String first = TranslationContext.addEntry("Guard");
        String second = TranslationContext.addEntry("Sentry");

        assertNotEquals(first, second);
        for (String key : new String[] {first, second}) {
            assertTrue(key.startsWith(WtemConfig.KeyNaming.GENERATED_PREFIX), key);
            assertEquals(WtemConfig.KeyNaming.GENERATED_PREFIX.length() + 6, key.length(), key);
        }
        assertEquals(Map.of(first, "Guard", second, "Sentry"), TranslationContext.snapshot());
    }

    @Test
    void hashesKeysReproduciblyAcrossRuns() {
        WtemConfig.KeyNaming hashed =
                new WtemConfig.KeyNaming(WtemConfig.KeyNaming.Scheme.HASHED, 8);
        TranslationContext.setKeyNaming(hashed);
        TranslationContext.setKey("entity.zombie.1.name");
        String first = TranslationContext.addEntry("Guard");

        TranslationContext.clear();
        TranslationContext.setKeyNaming(hashed);
        TranslationContext.setKey("entity.zombie.1.name");

        assertEquals(first, TranslationContext.addEntry("Guard"));
    }

    @Test
    void keepsWholeKeysNoMatterHowLongTheyGrow() {
        // Vanilla places no limit on the length of a translation key, so a key is never shortened:
        // the extraction path is the only thing that tells a translator where the text came from.
        String path = "datapack.example." + "nested.".repeat(40) + "name";
        TranslationContext.setKey(path);

        assertEquals(path, TranslationContext.addEntry("Guard"));
    }

    @Test
    void reusesSeededEntriesInsteadOfAllocatingAKey() {
        TranslationContext.setBuiltinEntries(Map.of("wtem.blank", ""));

        TranslationContext.setKey("sign.1.front_text.0");
        assertEquals("wtem.blank", TranslationContext.addEntry(""));
        // The seeded entry is not counted as extracted, and reusing it adds nothing either.
        assertEquals(0, TranslationContext.extractedEntryCount());
        assertEquals(Map.of("wtem.blank", ""), TranslationContext.snapshot());
    }

    @Test
    void keepsAllocationClearOfSeededKeys() {
        TranslationContext.setBuiltinEntries(Map.of("sign.1.front_text.0", "Reserved"));

        TranslationContext.setKey("sign.1.front_text.0");
        String key = TranslationContext.addEntry("Shop");

        assertNotEquals("sign.1.front_text.0", key);
        assertEquals(1, TranslationContext.extractedEntryCount());
        assertEquals("Reserved", TranslationContext.snapshot().get("sign.1.front_text.0"));
        assertEquals("Shop", TranslationContext.snapshot().get(key));
    }

    @Test
    void advancesTypeCountsFromTheFirstIncrement() {
        assertEquals(1, TranslationContext.getTypeCounts("container.chest"));

        TranslationContext.increaseTypeCounts("container.chest");
        assertEquals(2, TranslationContext.getTypeCounts("container.chest"));

        TranslationContext.increaseTypeCounts("container.chest");
        assertEquals(3, TranslationContext.getTypeCounts("container.chest"));
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
    void restartsTheWholeKeyByDefault() {
        TranslationContext.setKey("item.shulker_box.1");
        try (var ignored = TranslationContext.pushKey("container.shulker_box.1")) {
            assertEquals("container.shulker_box.1", TranslationContext.getKey());
        }

        assertEquals("item.shulker_box.1", TranslationContext.getKey());
    }

    @Test
    void keepsAPinnedPrefixAcrossARestart() {
        TranslationContext.setKey("item.shulker_box.1");
        try (var base = TranslationContext.pinKey()) {
            try (var ignored = TranslationContext.pushKey("container.shulker_box.1")) {
                assertEquals(
                        "item.shulker_box.1.container.shulker_box.1", TranslationContext.getKey());

                // A restart nested inside the pinned scope still stops at the pinned prefix.
                try (var inner = TranslationContext.pushKey("sign.1")) {
                    assertEquals("item.shulker_box.1.sign.1", TranslationContext.getKey());
                }
            }
        }

        // Closing the pin restores the unpinned behaviour for the caller that follows.
        try (var ignored = TranslationContext.pushKey("container.shulker_box.1")) {
            assertEquals("container.shulker_box.1", TranslationContext.getKey());
        }
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

    @Test
    void rollsBackUncommittedTransaction() {
        TranslationContext.setKey("entity.zombie.1.name");
        try (var ignored = TranslationContext.beginTransaction()) {
            TranslationContext.addEntry("Temporary");
            TranslationContext.increaseTypeCounts("entity.zombie");
        }

        assertEquals(Map.of(), TranslationContext.snapshot());
        assertEquals(1, TranslationContext.getTypeCounts("entity.zombie"));
        assertEquals("entity.zombie.1.name", TranslationContext.getKey());
    }

    @Test
    void keepsCommittedTransaction() {
        TranslationContext.setKey("entity.zombie.1.name");
        try (var transaction = TranslationContext.beginTransaction()) {
            TranslationContext.addEntry("Persistent");
            transaction.commit();
        }

        assertEquals(
                Map.of("entity.zombie.1.name", "Persistent"),
                TranslationContext.snapshot());
    }

    @Test
    void recordsWhereEachEntryWasFound() {
        try (var source = TranslationContext.pushSource("region")) {
            try (var location = TranslationContext.pushLocation("minecraft:overworld chunk [1, 2]");
                    var subject = TranslationContext.pushSubject("minecraft:chest")) {
                TranslationContext.setKey("container.chest.1.name");
                TranslationContext.addEntry("Loot");
            }
            // Leaving the scope drops the chest without dropping the stage it was found in.
            assertEquals(ExtractionOrigin.of("region"), TranslationContext.getOrigin());
        }

        assertEquals(ExtractionOrigin.UNKNOWN, TranslationContext.getOrigin());
        assertEquals(
                List.of(
                        new ExtractionRecord(
                                "container.chest.1.name",
                                "Loot",
                                new ExtractionOrigin(
                                        "region",
                                        "minecraft:overworld chunk [1, 2]",
                                        "minecraft:chest"),
                                false)),
                TranslationContext.records());
    }

    @Test
    void recordsEveryPlaceASharedKeyWasFound() {
        // Two chests holding the same text share one catalog entry, but a translator rewording it
        // has to know about both, so the second sighting is recorded and marked as a reuse.
        try (var ignored = TranslationContext.pushSource("region")) {
            try (var first = TranslationContext.pushSubject("chest one")) {
                TranslationContext.setKey("container.chest.1.name");
                TranslationContext.addEntry("Loot");
            }
            try (var second = TranslationContext.pushSubject("chest two")) {
                TranslationContext.setKey("container.chest.2.name");
                assertEquals("container.chest.1.name", TranslationContext.addEntry("Loot"));
            }
        }

        assertEquals(1, TranslationContext.snapshot().size());
        List<ExtractionRecord> records = TranslationContext.records();
        assertEquals(2, records.size(), records::toString);
        assertEquals("container.chest.1.name", records.get(1).key());
        assertEquals("chest two", records.get(1).origin().subject());
        assertFalse(records.get(0).reused());
        assertTrue(records.get(1).reused());
    }

    @Test
    void discardsRecordsOfARolledBackTransaction() {
        // A failed codec conversion leaves nothing in the world, so the report must not claim the
        // text was extracted either.
        try (var ignored = TranslationContext.pushSource("structures")) {
            TranslationContext.setKey("structure.example.name");
            try (var transaction = TranslationContext.beginTransaction()) {
                TranslationContext.addEntry("Temporary");
            }
        }

        assertEquals(List.of(), TranslationContext.records());
    }

    @Test
    void restoresTheEnclosingOriginWhenAStageIsSkipped() {
        try (var outer = TranslationContext.pushSource("region")) {
            try (var inner = TranslationContext.pushSource("datapacks")) {
                assertEquals("datapacks", TranslationContext.getOrigin().source());
            }

            assertEquals("region", TranslationContext.getOrigin().source());
        }
    }
}
