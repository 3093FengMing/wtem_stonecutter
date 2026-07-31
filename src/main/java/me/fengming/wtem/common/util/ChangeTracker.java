package me.fengming.wtem.common.util;

/**
 * @author FengMing
 */
public final class ChangeTracker {
    private boolean changed;

    public boolean add(boolean changed) {
        this.changed |= changed;
        return changed;
    }

    public boolean isChanged() {
        return this.changed;
    }
}
