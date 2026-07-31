package me.fengming.wtem.common.core.visitor;

import java.util.List;
import me.fengming.wtem.common.core.extraction.TranslationContext;
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
    private boolean changed;

    public boolean isChanged() {
        return this.changed;
    }

    @Override
    public void visitCompound(CompoundTag tag) {
        if (tag.isEmpty()) return;

        try (var traversal = NbtTraversalGuard.enter()) {
            if (!traversal.entered()) return;

            String entityId = ResourceIds.path(NbtUtils.getString(tag, "id"));
            this.changed |= translateEntityText(tag, entityId);
            visitPassengers(tag);
            this.changed |= visitItems(tag);
            this.changed |= visitTrades(tag);
        }
    }

    private static boolean translateEntityText(CompoundTag tag, String entityId) {
        String type = entityId.isBlank() ? "unknown" : entityId;
        boolean changed =
                translateIndexedComponent(tag, "CustomName", "entity." + type, "name");

        if ("text_display".equals(entityId)) {
            changed |= translateIndexedComponent(tag, "text", "text_display", "text");
        }
        if ("command_block_minecart".equals(entityId)) {
            changed |= translateIndexedComponent(
                    tag, "LastOutput", "command_block_minecart", "last_output");
        }
        if ("mannequin".equals(entityId)) {
            changed |=
                    translateIndexedComponent(
                            tag, "description", "mannequin", "description");
        }
        return changed;
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
