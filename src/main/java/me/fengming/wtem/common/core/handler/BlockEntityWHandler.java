package me.fengming.wtem.common.core.handler;

import java.util.List;
import java.util.Set;
import me.fengming.wtem.common.core.extraction.TranslationContext;
import me.fengming.wtem.common.core.handler.datapack.command.FunctionHandler;
import me.fengming.wtem.common.core.visitor.EntityTagVisitor;
import me.fengming.wtem.common.core.visitor.ItemTagVisitor;
import me.fengming.wtem.common.core.visitor.SimpleTagVisitor;
import me.fengming.wtem.common.util.ChangeTracker;
import me.fengming.wtem.common.util.NbtUtils;
import me.fengming.wtem.common.util.ResourceIds;
import me.fengming.wtem.common.util.TranslationUtils;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.TagVisitor;

/**
 * Extracts translatable data from block entities and recursively visits their stored items and
 * entities.
 * @author FengMing
 */
public class BlockEntityWHandler extends AbstractWHandler<CompoundTag> {
    private static final Set<String> CONTAINER_TYPES =
            Set.of("barrel", "blast_furnace", "brewing_stand",
                "campfire", "chest", "chiseled_bookshelf",
                "crafter", "dispenser", "dropper",
                "furnace", "hopper", "shulker_box",
                "smoker", "trapped_chest"
            );

    @Override
    public String getName() {
        return "block_entities";
    }

    @Override
    protected String getKey(CompoundTag tag) {
        return "block." + ResourceIds.path(NbtUtils.getString(tag, "id"));
    }

    @Override
    protected boolean innerHandle(CompoundTag compound) {
        String id = ResourceIds.path(NbtUtils.getString(compound, "id"));
        ChangeTracker tracker = new ChangeTracker();
        tracker.add(translateCustomName(compound, id));
        compound.accept(getVisitor(id, tracker));
        return tracker.isChanged();
    }

    private static boolean translateCustomName(CompoundTag tag, String id) {
        String type = id.isBlank() ? "unknown" : id;
        String countType =
                CONTAINER_TYPES.contains(type) ? "container." + type : "block_entity." + type;
        int index = TranslationContext.getTypeCounts(countType);

        try (var ignored = TranslationContext.pushKey(countType + "." + index)) {
            ChangeTracker tracker = new ChangeTracker();
            tracker.add(TranslationUtils.translateNbtComponent(tag, "CustomName", "name"));
            tracker.add(TranslationUtils.translateNbtComponent(tag, "custom_name", "name"));
            if (tracker.isChanged()) TranslationContext.increaseTypeCounts(countType);
            return tracker.isChanged();
        }
    }

    private TagVisitor getVisitor(String id, ChangeTracker tracker) {
        EntityTagVisitor entityVisitor = new EntityTagVisitor();
        if (CONTAINER_TYPES.contains(id)) return itemListVisitor("Items", tracker);
        return switch (id) {
            case "jukebox" -> itemVisitor("RecordItem", tracker);
            case "lectern" -> itemVisitor("Book", tracker);
            case "brushable_block", "decorated_pot" -> itemVisitor("item", tracker);
            case "sign", "hanging_sign" ->
                    (SimpleTagVisitor) tag -> tracker.add(translateSign(tag));
            case "beehive", "bee_nest" ->
                    (SimpleTagVisitor)
                            tag -> {
                                ListTag bees = NbtUtils.getList(tag, "bees", Tag.TAG_COMPOUND);
                                for (int i = 0; i < bees.size(); ++i) {
                                    CompoundTag bee = NbtUtils.getCompound(bees, i);
                                    CompoundTag entityData = NbtUtils.getCompound(bee, "entity_data");
                                    CompoundTag nestedEntity = NbtUtils.getCompound(entityData, "entity");
                                    if (!nestedEntity.isEmpty()) entityData = nestedEntity;
                                    entityData.accept(entityVisitor);
                                }
                                tracker.add(entityVisitor.isChanged());
                            };
            case "mob_spawner" ->
                    (SimpleTagVisitor)
                            tag -> {
                                NbtUtils.getCompound(NbtUtils.getCompound(tag, "SpawnData"), "entity")
                                        .accept(entityVisitor);
                                ListTag potentials =
                                        NbtUtils.getList(tag, "SpawnPotentials", Tag.TAG_COMPOUND);
                                for (int i = 0; i < potentials.size(); ++i) {
                                    NbtUtils.getCompoundPath(
                                                    NbtUtils.getCompound(potentials, i), "data.entity")
                                            .accept(entityVisitor);
                                }
                                tracker.add(entityVisitor.isChanged());
                            };
            case "trial_spawner" ->
                    (SimpleTagVisitor)
                            tag -> {
                                NbtUtils.getCompoundPath(tag, "spawn_data.entity")
                                        .accept(entityVisitor);
                                for (String field : List.of("normal_config", "ominous_config")) {
                                    ListTag potentials =
                                            NbtUtils.getList(
                                                    NbtUtils.getCompound(tag, field),
                                                    "spawn_potentials",
                                                    Tag.TAG_COMPOUND);
                                    for (int i = 0; i < potentials.size(); ++i) {
                                        NbtUtils.getCompoundPath(
                                                        NbtUtils.getCompound(potentials, i),
                                                        "data.entity")
                                                .accept(entityVisitor);
                                    }
                                }
                                tracker.add(entityVisitor.isChanged());
                            };
            case "command_block" ->
                    (SimpleTagVisitor) tag -> tracker.add(translateCommandBlock(tag));
            default -> SimpleTagVisitor.INSTANCE;
        };
    }

    private static SimpleTagVisitor itemListVisitor(String field, ChangeTracker tracker) {
        return tag -> {
            ItemTagVisitor visitor = new ItemTagVisitor();
            NbtUtils.getList(tag, field, Tag.TAG_COMPOUND).accept(visitor);
            tracker.add(visitor.isChanged());
        };
    }

    private static SimpleTagVisitor itemVisitor(String field, ChangeTracker tracker) {
        return tag -> {
            ItemTagVisitor visitor = new ItemTagVisitor();
            NbtUtils.getCompound(tag, field).accept(visitor);
            tracker.add(visitor.isChanged());
        };
    }

    private boolean translateSign(CompoundTag tag) {
        int index = TranslationContext.getTypeCounts("sign");
        ChangeTracker tracker = new ChangeTracker();

        try (var ignored = TranslationContext.pushKey("sign." + index)) {
            for (String side : List.of("front_text", "back_text")) {
                CompoundTag text = NbtUtils.getCompound(tag, side);
                for (String messageType : List.of("messages", "filtered_messages")) {
                    ListTag messages = NbtUtils.getList(text, messageType);
                    for (int i = 0; i < messages.size(); i++) {
                        String keyPath = side + "." + i;
                        if ("filtered_messages".equals(messageType)) {
                            keyPath += ".filtered";
                        }
                        tracker.add(TranslationUtils.translateNbtComponent(messages, i, keyPath));
                    }
                }
            }
        }

        if (tracker.isChanged()) TranslationContext.increaseTypeCounts("sign");
        return tracker.isChanged();
    }

    private boolean translateCommandBlock(CompoundTag tag) {
        String countType = "command_block";
        int index = TranslationContext.getTypeCounts(countType);
        ChangeTracker tracker = new ChangeTracker();
        try (var ignored = TranslationContext.pushKey(countType + "." + index)) {
            if (tracker.add(
                    TranslationUtils.translateNbtComponent(tag, "LastOutput", "last_output"))) {
                TranslationContext.increaseTypeCounts(countType);
            }
        }

        if (tag.contains("Command")) {
            String originalCommand = NbtUtils.getString(tag, "Command");
            String translatedCommand = FunctionHandler.processFunction(List.of(originalCommand));
            if (tracker.add(!originalCommand.equals(translatedCommand))) {
                tag.putString("Command", translatedCommand);
            }
        }
        return tracker.isChanged();
    }
}
