package me.fengming.wtem.common.core.handler;

import java.util.List;
import java.util.OptionalInt;
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

    private static final String FILTERED_MESSAGES = "filtered_messages";
    private static final List<String> SIGN_MESSAGES = List.of("messages", FILTERED_MESSAGES);

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
        String fullId = NbtUtils.getString(compound, "id");
        if (!TranslationContext.config().filters().matchesBlockEntity(fullId)) return false;
        String id = ResourceIds.path(fullId);
        ChangeTracker tracker = new ChangeTracker();
        try (var ignored = TranslationContext.pushSubject(describe(compound))) {
            tracker.add(translateCustomName(compound, id));
            compound.accept(getVisitor(id, tracker));
        }
        return tracker.isChanged();
    }

    /**
     * Names the block for the extraction report, with its coordinates when it stores them.
     *
     * <p>A block entity read out of a region file carries its own position, which is what makes a
     * report row actionable: the translator can go and look at the block. A block entity nested in an
     * item stack has no position of its own, so it is described by type alone.
     */
    private static String describe(CompoundTag compound) {
        String id = NbtUtils.getString(compound, "id");
        if (id.isBlank()) id = "block entity";

        OptionalInt x = NbtUtils.getInt(compound, "x");
        OptionalInt y = NbtUtils.getInt(compound, "y");
        OptionalInt z = NbtUtils.getInt(compound, "z");
        if (x.isEmpty() || y.isEmpty() || z.isEmpty()) return id;
        return id + " (" + x.getAsInt() + ", " + y.getAsInt() + ", " + z.getAsInt() + ")";
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
                                    // Occupant#entity_data stores the entity compound directly, with
                                    // the entity id merged in at the top level. Unlike SpawnData
                                    // there is no nested 'entity' wrapper to unwrap.
                                    NbtUtils.getCompound(bee, "entity_data").accept(entityVisitor);
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
                for (String messageType : SIGN_MESSAGES) {
                    boolean filtered = FILTERED_MESSAGES.equals(messageType);
                    if (filtered && TranslationContext.config().skipped().filteredText()) continue;

                    ListTag messages = NbtUtils.getList(text, messageType);
                    for (int i = 0; i < messages.size(); i++) {
                        String keyPath = side + "." + i + (filtered ? ".filtered" : "");
                        // Sign message lists are heterogeneous in modern Minecraft: a literal
                        // message can become a structured {translate:...} component directly.
                        // Do not serialize that component back into a StringTag containing JSON,
                        // which would make the game display the JSON text verbatim.
                        tracker.add(
                                TranslationUtils.translateNbtComponentAsStructured(
                                        messages, i, keyPath));
                    }
                }
            }
        }

        if (tracker.isChanged()) TranslationContext.increaseTypeCounts("sign");
        return tracker.isChanged();
    }

    private boolean translateCommandBlock(CompoundTag tag) {
        String countType = "command_block";
        ChangeTracker tracker = new ChangeTracker();
        if (!TranslationContext.config().skipped().commandBlockOutput()) {
            int index = TranslationContext.getTypeCounts(countType);
            try (var ignored = TranslationContext.pushKey(countType + "." + index)) {
                if (tracker.add(
                        TranslationUtils.translateNbtComponent(tag, "LastOutput", "last_output"))) {
                    TranslationContext.increaseTypeCounts(countType);
                }
            }
        }

        if (tag.contains("Command")) {
            String originalCommand = NbtUtils.getString(tag, "Command");
            if (originalCommand.startsWith("/")) originalCommand = originalCommand.substring(1);
            String translatedCommand = FunctionHandler.processFunction(originalCommand);
            if (tracker.add(!originalCommand.equals(translatedCommand))) {
                tag.putString("Command", translatedCommand);
            }
        }
        return tracker.isChanged();
    }
}
