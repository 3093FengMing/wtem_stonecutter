package me.fengming.wtem.common.util;

/**
 * Accumulates whether any step of a multi-step extraction changed its input.
 *
 * <p>Extraction is built from many independent steps that each report a boolean. Collecting those
 * results through a tracker keeps the accumulation out of the calling code, and lets steps that run
 * inside lambdas report back without needing an effectively-final local.
 *
 * @author FengMing
 */
public final class ChangeTracker {
    private boolean changed;

    /** Records the outcome of one step and returns that same outcome. */
    public boolean add(boolean changed) {
        this.changed |= changed;
        return changed;
    }

    public boolean isChanged() {
        return this.changed;
    }
}
