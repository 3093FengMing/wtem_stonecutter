package me.fengming.wtem.common.core.extraction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import me.fengming.wtem.common.core.extraction.table.ExtractionOrigin;
import org.junit.jupiter.api.Test;

/** Boundary coverage for the place description attached to each extracted entry. */
class ExtractionOriginTest {
    @Test
    void nestsSegmentsInnermostLast() {
        ExtractionOrigin origin =
                ExtractionOrigin.of("region")
                        .addLocation("minecraft:overworld")
                        .addLocation("chunk [1, 2]")
                        .addSubject("minecraft:chest at 12 64 -30")
                        .addSubject("minecraft:shulker_box")
                        .addSubject("minecraft:written_book");

        assertEquals("region", origin.source());
        assertEquals("minecraft:overworld > chunk [1, 2]", origin.location());
        assertEquals(
                "minecraft:chest at 12 64 -30 > minecraft:shulker_box > minecraft:written_book",
                origin.subject());
    }

    @Test
    void ignoresSegmentsThatSayNothing() {
        // A block entity with unreadable coordinates or an item without an id describes itself with
        // an empty string, and an empty segment must not add a separator to the chain.
        ExtractionOrigin origin = ExtractionOrigin.of("region").addSubject("minecraft:chest");

        assertSame(origin, origin.addLocation(""));
        assertSame(origin, origin.addLocation("  "));
        assertSame(origin, origin.addLocation(null));
        assertSame(origin, origin.addSubject(""));
        assertSame(origin, origin.addSubject(null));
    }

    @Test
    void startsFreshForEachStage() {
        ExtractionOrigin nested =
                ExtractionOrigin.of("region").addLocation("chunk [0, 0]").addSubject("chest");

        assertEquals(ExtractionOrigin.of("datapacks"), ExtractionOrigin.of("datapacks"));
        assertEquals("", ExtractionOrigin.of("datapacks").location());
        assertEquals("", ExtractionOrigin.of("datapacks").subject());
        // The nested origin is untouched, so a scope can restore it on close.
        assertEquals("chunk [0, 0]", nested.location());
    }

    @Test
    void treatsMissingFieldsAsEmptyRatherThanNull() {
        assertEquals(ExtractionOrigin.UNKNOWN, new ExtractionOrigin(null, null, null));
    }
}
