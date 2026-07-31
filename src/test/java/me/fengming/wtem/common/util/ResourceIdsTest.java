package me.fengming.wtem.common.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ResourceIdsTest {
    @Test
    void extractsPathFromNamespacedIdentifier() {
        assertEquals("written_book", ResourceIds.path("minecraft:written_book"));
    }

    @Test
    void keepsUnnamespacedValue() {
        assertEquals("custom", ResourceIds.path("custom"));
    }

    @Test
    void handlesNullValue() {
        assertEquals("", ResourceIds.path(null));
    }
}
