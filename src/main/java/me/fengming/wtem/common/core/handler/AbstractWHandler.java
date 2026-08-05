package me.fengming.wtem.common.core.handler;

import me.fengming.wtem.common.core.extraction.TranslationContext;
import net.minecraft.nbt.Tag;

/**
 * @author FengMing
 */
public abstract class AbstractWHandler<T extends Tag> {

    public abstract String getName();

    protected abstract String getKey(T tag);

    /**
     * @param tag a tag to handle.
     * @return true is the tag has been changed; otherwise, false.
     */
    protected abstract boolean innerHandle(T tag);

    public boolean handle(T tag) {
        return handle(tag, true);
    }

    /**
     * @param tag a tag to handle.
     * @param rebuildKey whether the handler restarts the key path instead of extending the caller's.
     *     Restarting is right for a top-level tag read out of a region file, but loses the context of
     *     data nested inside an item stack. Extending pins the caller's path, so the nested handler
     *     and everything it visits build on top of it rather than replacing it.
     * @return true if the tag has been changed; otherwise, false.
     */
    public boolean handle(T tag, boolean rebuildKey) {
        // The key has to be pinned before it is pushed, because pushing is what restarts it. Pinning
        // first makes the restart stop at the caller's path instead of at the root.
        if (!rebuildKey) {
            try (var ignored = TranslationContext.pinKey()) {
                return handle(tag, true);
            }
        }

        try (var transaction = TranslationContext.beginTransaction();
                var ignored = TranslationContext.pushKey(getKey(tag))) {
            int recordsBefore = TranslationContext.recordCount();
            boolean changed = innerHandle(tag);
            // Catalog-only fields, such as writable-book pages, deliberately leave NBT untouched.
            // Their report rows must nevertheless survive this handler's rollback boundary.
            if (changed || TranslationContext.hasOnlyCatalogEntriesSince(recordsBefore)) {
                transaction.commit();
            }
            return changed;
        }
    }
}
