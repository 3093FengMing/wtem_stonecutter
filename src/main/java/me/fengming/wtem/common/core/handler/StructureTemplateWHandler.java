package me.fengming.wtem.common.core.handler;

import java.util.OptionalDouble;
import me.fengming.wtem.common.core.extraction.TranslationContext;
import me.fengming.wtem.common.core.visitor.EntityTagVisitor;
import me.fengming.wtem.common.util.ChangeTracker;
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
        ChangeTracker tracker = new ChangeTracker();
        ListTag blocks = NbtUtils.getList(tag, "blocks", CompoundTag.TAG_COMPOUND);
        for (int i = 0; i < blocks.size(); i++) {
            CompoundTag block = NbtUtils.getCompound(blocks, i);
            if (!block.contains("nbt")) continue;
            try (var ignored = TranslationContext.pushSubject(offset(block, "pos"))) {
                tracker.add(beHandler.handle(NbtUtils.getCompound(block, "nbt")));
            }
        }

        EntityTagVisitor entityVisitor = new EntityTagVisitor();
        ListTag entities = NbtUtils.getList(tag, "entities", CompoundTag.TAG_COMPOUND);
        for (int i = 0; i < entities.size(); i++) {
            CompoundTag entity = NbtUtils.getCompound(entities, i);
            if (!entity.contains("nbt")) continue;
            try (var ignored = TranslationContext.pushSubject(offset(entity, "blockPos"))) {
                NbtUtils.getCompound(entity, "nbt").accept(entityVisitor);
            }
        }
        tracker.add(entityVisitor.isChanged());
        return tracker.isChanged();
    }

    /**
     * Describes where inside the template a palette entry sits.
     *
     * <p>The nbt of a block or entity in a template has no world position of its own, only an offset
     * from the template corner, so that offset is the only thing that can tell two otherwise identical
     * chests in the same structure apart.
     */
    private static String offset(CompoundTag entry, String field) {
        ListTag pos = NbtUtils.getList(entry, field);
        if (pos.size() < 3) return "";

        StringBuilder description = new StringBuilder("offset (");
        for (int i = 0; i < 3; i++) {
            OptionalDouble coordinate = NbtUtils.getDouble(pos, i);
            if (coordinate.isEmpty()) return "";
            if (i > 0) description.append(", ");
            description.append(Math.round(coordinate.getAsDouble()));
        }
        return description.append(')').toString();
    }

    public record Result(CompoundTag tag, boolean changed) {}
}
