package me.fengming.wtem.common.core.visitor;

import java.util.List;
import java.util.OptionalDouble;
import me.fengming.wtem.common.config.WtemConfig;
import me.fengming.wtem.common.core.extraction.TranslationContext;
import me.fengming.wtem.common.util.ChangeTracker;
import me.fengming.wtem.common.util.NbtUtils;
import me.fengming.wtem.common.util.ResourceIds;
import me.fengming.wtem.common.util.TranslationUtils;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

/**
 * Extracts entity text and recursively visits passengers, equipment, inventories, and trades.
 *
 * @author FengMing
 */
public class EntityTagVisitor implements SimpleTagVisitor {
    private final ChangeTracker tracker = new ChangeTracker();

    public boolean isChanged() {
        return this.tracker.isChanged();
    }

    @Override
    public void visitCompound(CompoundTag tag) {
        if (tag.isEmpty()) return;

        try (var traversal = NbtTraversalGuard.enter()) {
            if (!traversal.entered()) return;

            String entityId = ResourceIds.path(NbtUtils.getString(tag, "id"));
            try (var ignored = TranslationContext.pushSubject(describe(tag))) {
                this.tracker.add(translateEntityText(tag, entityId));
                visitPassengers(tag);
                this.tracker.add(visitItems(tag));
                this.tracker.add(visitTrades(tag));
            }
        }
    }

    /**
     * Names the entity for the extraction report, with its position when it carries one.
     *
     * <p>Entity coordinates are fractional and stored as a three-element list, and they are rounded
     * here because a report row only has to point a translator at the right place. Entities stored on
     * an item or in a spawner have no position yet, so they are described by type alone.
     */
    private static String describe(CompoundTag tag) {
        String id = NbtUtils.getString(tag, "id");
        if (id.isBlank()) id = "entity";

        ListTag pos = NbtUtils.getList(tag, "Pos");
        if (pos.size() < 3) return id;

        StringBuilder description = new StringBuilder(id).append(" (");
        for (int i = 0; i < 3; i++) {
            OptionalDouble coordinate = NbtUtils.getDouble(pos, i);
            if (coordinate.isEmpty()) return id;
            if (i > 0) description.append(", ");
            description.append(Math.round(coordinate.getAsDouble()));
        }
        return description.append(')').toString();
    }

    private static boolean translateEntityText(CompoundTag tag, String entityId) {
        String type = entityId.isBlank() ? "unknown" : entityId;
        ChangeTracker tracker = new ChangeTracker();
        tracker.add(translateIndexedComponent(tag, "CustomName", "entity." + type, "name"));

        if ("text_display".equals(entityId)) {
            tracker.add(translateIndexedComponent(tag, "text", "text_display", "text"));
        }
        // A command block minecart caches its output in the same field a command block does, so the
        // setting that drops one drops the other: they are the same text for the same reason.
        if ("command_block_minecart".equals(entityId)
                && !WtemConfig.active().skipped().commandBlockOutput()) {
            tracker.add(
                    translateIndexedComponent(
                            tag, "LastOutput", "command_block_minecart", "last_output"));
        }
        if ("mannequin".equals(entityId)) {
            tracker.add(translateIndexedComponent(tag, "description", "mannequin", "description"));
        }
        return tracker.isChanged();
    }

    private static boolean translateIndexedComponent(
            CompoundTag tag, String field, String countType, String keyPath) {
        int index = TranslationContext.getTypeCounts(countType);
        try (var ignored = TranslationContext.pushKey(countType + "." + index)) {
            if (TranslationUtils.translateNbtComponent(tag, field, keyPath)) {
                TranslationContext.increaseTypeCounts(countType);
                return true;
            }
        }
        return false;
    }

    private void visitPassengers(CompoundTag tag) {
        ListTag passengers = NbtUtils.getList(tag, "Passengers", Tag.TAG_COMPOUND);
        for (int i = 0; i < passengers.size(); i++) {
            visitCompound(NbtUtils.getCompound(passengers, i));
        }
    }

    private static boolean visitItems(CompoundTag tag) {
        ItemTagVisitor itemVisitor = new ItemTagVisitor();

        // for 1.21.5+
        CompoundTag equipment = NbtUtils.getCompound(tag, "equipment");
        for (String key : NbtUtils.getKeys(equipment)) {
            NbtUtils.getCompound(equipment, key).accept(itemVisitor);
        }

        // for pre-1.21.5
        for (String field : List.of("Items", "Inventory", "ArmorItems", "HandItems")) {
            NbtUtils.getList(tag, field, Tag.TAG_COMPOUND).accept(itemVisitor);
        }

        for (String field :
                List.of(
                        "Item",
                        "item",
                        "FireworksItem",
                        "SaddleItem",
                        "weapon",
                        "body_armor_item")) {
            NbtUtils.getCompound(tag, field).accept(itemVisitor);
        }
        return itemVisitor.isChanged();
    }

    private static boolean visitTrades(CompoundTag tag) {
        ItemTagVisitor itemVisitor = new ItemTagVisitor();
        ListTag recipes =
                NbtUtils.getList(NbtUtils.getCompound(tag, "Offers"), "Recipes", Tag.TAG_COMPOUND);
        for (int i = 0; i < recipes.size(); i++) {
            CompoundTag recipe = NbtUtils.getCompound(recipes, i);
            for (String field : List.of("buy", "buyB", "sell")) {
                NbtUtils.getCompound(recipe, field).accept(itemVisitor);
            }
        }
        return itemVisitor.isChanged();
    }
}
