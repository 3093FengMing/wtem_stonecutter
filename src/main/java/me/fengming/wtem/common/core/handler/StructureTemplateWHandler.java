package me.fengming.wtem.common.core.handler;

import me.fengming.wtem.common.core.visitor.EntityTagVisitor;
import me.fengming.wtem.common.util.NbtUtils;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

/**
 * @author FengMing
 */
public class StructureTemplateWHandler extends AbstractWHandler<CompoundTag> {

    @Override
    public String getName() {
        return "structure";
    }

    @Override
    protected String getKey(CompoundTag tag) {
        return "structure";
    }

    public Result process(StructureTemplate structure) {
        if (structure == null) return new Result(new CompoundTag(), false);
        return process(structure.save(new CompoundTag()));
    }

    public Result process(CompoundTag compound) {
        if (compound == null || compound.isEmpty()) return new Result(new CompoundTag(), false);
        return new Result(compound, handle(compound));
    }

    @Override
    protected boolean innerHandle(CompoundTag tag) {
        BlockEntityWHandler beHandler = new BlockEntityWHandler();
        boolean changed = false;
        ListTag blocks = NbtUtils.getList(tag, "blocks", CompoundTag.TAG_COMPOUND);
        for (int i = 0; i < blocks.size(); i++) {
            CompoundTag block = NbtUtils.getCompound(blocks, i);
            if (!block.contains("nbt")) continue;
            changed |= beHandler.handle(NbtUtils.getCompound(block, "nbt"));
        }

        EntityTagVisitor entityVisitor = new EntityTagVisitor();
        ListTag entities = NbtUtils.getList(tag, "entities", CompoundTag.TAG_COMPOUND);
        for (int i = 0; i < entities.size(); i++) {
            CompoundTag entity = NbtUtils.getCompound(entities, i);
            if (!entity.contains("nbt")) continue;
            NbtUtils.getCompound(entity, "nbt").accept(entityVisitor);
        }
        return changed || entityVisitor.isChanged();
    }

    public record Result(CompoundTag tag, boolean changed) {}
}
