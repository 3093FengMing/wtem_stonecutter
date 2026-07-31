package me.fengming.wtem.common.core.visitor;

import java.util.List;
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
            this.tracker.add(translateEntityText(tag, entityId));
            visitPassengers(tag);
            this.tracker.add(visitItems(tag));
            this.tracker.add(visitTrades(tag));
        }
    }

    private static boolean translateEntityText(CompoundTag tag, String entityId) {
        String type = entityId.isBlank() ? "unknown" : entityId;
        ChangeTracker tracker = new ChangeTracker();
        tracker.add(translateIndexedComponent(tag, "CustomName", "entity." + type, "name"));

        if ("text_display".equals(entityId)) {
            tracker.add(translateIndexedComponent(tag, "text", "text_display", "text"));
        }
        if ("command_block_minecart".equals(entityId)) {
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

        CompoundTag equipment = NbtUtils.getCompound(tag, "equipment");
        for (String key : NbtUtils.getKeys(equipment)) {
            NbtUtils.getCompound(equipment, key).accept(itemVisitor);
        }

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
