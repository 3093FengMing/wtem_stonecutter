package me.fengming.wtem.common.core.handler;

import me.fengming.wtem.common.core.visitor.EntityTagVisitor;
import me.fengming.wtem.common.util.NbtUtils;
import me.fengming.wtem.common.util.ResourceIds;
import net.minecraft.nbt.CompoundTag;

/**
 * @author FengMing
 */
public class EntityWHandler extends AbstractWHandler<CompoundTag> {

    @Override
    public String getName() {
        return "Entities";
    }

    @Override
    protected String getKey(CompoundTag tag) {
        return "entity." + ResourceIds.path(NbtUtils.getString(tag, "id"));
    }

    @Override
    protected boolean innerHandle(CompoundTag tag) {
        tag.accept(new EntityTagVisitor());
        return true;
    }
}
