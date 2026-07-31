package me.fengming.wtem.common.core.visitor;

import me.fengming.wtem.common.core.TranslationContext;
import me.fengming.wtem.common.core.handler.BlockEntityWHandler;
import me.fengming.wtem.common.util.NbtUtils;
import me.fengming.wtem.common.util.ResourceIds;
import me.fengming.wtem.common.util.TranslationUtils;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

/**
 * Extracts components from item stacks, including item stacks nested in data components.
 *
 * @author FengMing
 */
public class ItemTagVisitor implements SimpleTagVisitor {
    private boolean changed;

    public boolean isChanged() {
        return this.changed;
    }

    @Override
    public void visitCompound(CompoundTag tag) {
        handleItem(tag);
    }

    @Override
    public void visitList(ListTag tag) {
        if (tag.isEmpty() || tag.get(0).getId() != Tag.TAG_COMPOUND) return;

        for (int i = 0; i < tag.size(); i++) {
            handleItem(NbtUtils.getCompound(tag, i));
        }
    }

    /**
     * Visits a component patch that is not wrapped in a complete item stack, as used by predicates
     * and the {@code set_components} loot function.
     */
    public void visitComponents(CompoundTag components) {
        if (components.isEmpty()) return;

        try (var traversal = NbtTraversalGuard.enter()) {
            if (!traversal.entered()) return;
            this.changed |= translateItemText(components);
            this.changed |= handleAttributeModifiers(components);
            this.changed |= handleBook(components);
            handleNestedItems(components);
            this.changed |= handleNestedData(components);
        }
    }

    private void handleItem(CompoundTag item) {
        if (item.isEmpty()) return;

        try (var traversal = NbtTraversalGuard.enter()) {
            if (!traversal.entered()) return;

            String itemId = ResourceIds.path(NbtUtils.getString(item, "id"));
            if (itemId.isBlank()) return;

            CompoundTag components = NbtUtils.getCompound(item, "components");
            String countType = "item." + itemId;
            int itemIndex = TranslationContext.getTypeCounts(countType);
            String itemKey = countType + "." + itemIndex;

            try (var ignored = TranslationContext.pushKey(itemKey)) {
                boolean translated = translateItemText(components);
                translated |= handleAttributeModifiers(components);
                this.changed |= translated;
                if (translated) TranslationContext.increaseTypeCounts(countType);

                this.changed |= handleBook(components);
                handleNestedItems(components);
                this.changed |= handleNestedData(components);
            }
        }
    }

    private static boolean translateItemText(CompoundTag components) {
        boolean translated =
                TranslationUtils.translateNbtComponent(
                        components, "minecraft:custom_name", "name");
        translated |=
                TranslationUtils.translateNbtComponent(
                        components, "minecraft:item_name", "item_name");

        ListTag lore = NbtUtils.getList(components, "minecraft:lore");
        for (int i = 0; i < lore.size(); i++) {
            translated |=
                    TranslationUtils.translateNbtComponent(lore, i, "lore.line" + i);
        }
        return translated;
    }

    private static boolean handleBook(CompoundTag components) {
        String componentName = "minecraft:written_book_content";
        if (!components.contains(componentName)) return false;

        CompoundTag book = NbtUtils.getCompound(components, componentName);
        int bookIndex = TranslationContext.getTypeCounts("book");
        boolean translated = false;

        try (var ignored = TranslationContext.pushKey("book." + bookIndex)) {
            ListTag pages = NbtUtils.getList(book, "pages", Tag.TAG_COMPOUND);
            for (int i = 0; i < pages.size(); i++) {
                CompoundTag page = NbtUtils.getCompound(pages, i);
                translated |=
                        TranslationUtils.translateNbtComponent(
                                page, "raw", "content.page" + i);
                translated |=
                        TranslationUtils.translateNbtComponent(
                                page, "filtered", "content.page" + i + ".filtered");
            }

            if (!components.contains("minecraft:custom_name")) {
                String title = NbtUtils.getString(NbtUtils.getCompound(book, "title"), "raw");
                translated |=
                        TranslationUtils.putTranslatedNbtComponent(
                                components,
                                "minecraft:custom_name",
                                title,
                                true,
                                "title");
            }
        }

        if (translated) TranslationContext.increaseTypeCounts("book");
        return translated;
    }

    private static boolean handleAttributeModifiers(CompoundTag components) {
        ListTag modifiers = NbtUtils.getList(components, "minecraft:attribute_modifiers");
        boolean translated = false;
        for (int i = 0; i < modifiers.size(); i++) {
            CompoundTag modifier = NbtUtils.getCompound(modifiers, i);
            translated |=
                    TranslationUtils.translateNbtComponent(
                            modifier,
                            "display.value",
                            "attribute_modifier." + i + ".display");
        }
        return translated;
    }

    private void handleNestedItems(CompoundTag components) {
        ListTag container = NbtUtils.getList(components, "minecraft:container", Tag.TAG_COMPOUND);
        for (int i = 0; i < container.size(); i++) {
            handleItem(NbtUtils.getCompound(NbtUtils.getCompound(container, i), "item"));
        }

        for (String componentName :
                new String[] {"minecraft:bundle_contents", "minecraft:charged_projectiles"}) {
            ListTag items = NbtUtils.getList(components, componentName, Tag.TAG_COMPOUND);
            for (int i = 0; i < items.size(); i++) {
                handleItem(NbtUtils.getCompound(items, i));
            }
        }

        handleItem(NbtUtils.getCompound(components, "minecraft:use_remainder"));
    }

    private static boolean handleNestedData(CompoundTag components) {
        EntityTagVisitor entityVisitor = new EntityTagVisitor();
        for (String componentName :
                new String[] {"minecraft:entity_data", "minecraft:bucket_entity_data"}) {
            CompoundTag entity = NbtUtils.getCompound(components, componentName);
            if (!entity.isEmpty()) entity.accept(entityVisitor);
        }

        CompoundTag blockEntity = NbtUtils.getCompound(components, "minecraft:block_entity_data");
        boolean changed =
                !blockEntity.isEmpty() && new BlockEntityWHandler().handle(blockEntity);

        ListTag bees = NbtUtils.getList(components, "minecraft:bees", Tag.TAG_COMPOUND);
        for (int i = 0; i < bees.size(); i++) {
            CompoundTag entityData = NbtUtils.getCompound(NbtUtils.getCompound(bees, i), "entity_data");
            if (!entityData.isEmpty()) entityData.accept(entityVisitor);
        }
        return changed || entityVisitor.isChanged();
    }
}
