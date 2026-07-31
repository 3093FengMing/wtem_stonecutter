package me.fengming.wtem.common.core.handler;

import me.fengming.wtem.common.core.TranslationContext;
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
        TranslationContext.setKey(getKey(tag));
        return innerHandle(tag);
    }
}
