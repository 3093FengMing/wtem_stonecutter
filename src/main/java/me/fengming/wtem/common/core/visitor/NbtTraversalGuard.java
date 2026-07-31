package me.fengming.wtem.common.core.visitor;

/** Limits recursive traversal across nested item, entity, and block-entity data. */
final class NbtTraversalGuard {
    private static final int MAX_DEPTH = 32;
    private static final ThreadLocal<Integer> DEPTH = ThreadLocal.withInitial(() -> 0);

    private NbtTraversalGuard() {}

    static Scope enter() {
        int currentDepth = DEPTH.get();
        if (currentDepth >= MAX_DEPTH) return new Scope(currentDepth, false);

        DEPTH.set(currentDepth + 1);
        return new Scope(currentDepth, true);
    }

    static final class Scope implements AutoCloseable {
        private final int previousDepth;
        private final boolean entered;
        private boolean closed;

        private Scope(int previousDepth, boolean entered) {
            this.previousDepth = previousDepth;
            this.entered = entered;
        }

        boolean entered() {
            return this.entered;
        }

        @Override
        public void close() {
            if (this.closed || !this.entered) return;
            this.closed = true;
            if (this.previousDepth == 0) {
                DEPTH.remove();
            } else {
                DEPTH.set(this.previousDepth);
            }
        }
    }
}
