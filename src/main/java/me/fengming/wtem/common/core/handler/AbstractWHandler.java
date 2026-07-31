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
        try (var transaction = TranslationContext.beginTransaction();
                var ignored = TranslationContext.pushKey(getKey(tag))) {
            boolean changed = innerHandle(tag);
            if (changed) transaction.commit();
            return changed;
        }
    }
}
