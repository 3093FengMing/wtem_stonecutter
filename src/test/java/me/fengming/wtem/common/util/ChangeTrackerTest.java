package me.fengming.wtem.common.util;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ChangeTrackerTest {
    @Test
    void startsUnchanged() {
        assertFalse(new ChangeTracker().isChanged());
    }

    @Test
    void latchesOnTheFirstChange() {
        ChangeTracker tracker = new ChangeTracker();

        tracker.add(true);
        tracker.add(false);

        // A later unchanged step must not clear an earlier change, which is what makes the tracker a
        // safe replacement for a chain of 'changed |=' assignments.
        assertTrue(tracker.isChanged());
    }

    @Test
    void reportsTheValueItWasGiven() {
        ChangeTracker tracker = new ChangeTracker();

        // The return value lets a caller branch on its own step while the tracker keeps the total.
        assertTrue(tracker.add(true));
        assertFalse(tracker.add(false));
    }
}
