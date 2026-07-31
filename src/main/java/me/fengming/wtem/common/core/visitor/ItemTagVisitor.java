package me.fengming.wtem.common.core.visitor;

import me.fengming.wtem.common.Wtem;
import me.fengming.wtem.common.config.WtemConfig;
import me.fengming.wtem.common.core.extraction.TranslationContext;
import me.fengming.wtem.common.core.handler.BlockEntityWHandler;
import me.fengming.wtem.common.util.ChangeTracker;
import me.fengming.wtem.common.util.NbtUtils;
import me.fengming.wtem.common.util.ResourceIds;
import me.fengming.wtem.common.util.TranslationUtils;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.util.List;

/**
 * Extracts components from item stacks, including item stacks nested in data components.
 *
 * @author FengMing
 */
public class ItemTagVisitor implements SimpleTagVisitor {
    private final ChangeTracker tracker = new ChangeTracker();

    public boolean isChanged() {
        return this.tracker.isChanged();
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
     *
     * <p>Without an owning item there is no type to count, so the caller's key is used as-is.
     */
    public void visitComponents(CompoundTag components) {
        if (components.isEmpty()) return;

        try (var traversal = NbtTraversalGuard.enter()) {
            if (!traversal.entered()) return;
            visitPatch(components, null);
        }
    }

    /** Visits a component patch while retaining the item-based upstream key strategy. */
    public void visitComponents(String itemId, CompoundTag components) {
        if (components.isEmpty()) return;

        String type = itemId == null || itemId.isBlank() ? "unknown" : ResourceIds.path(itemId);
        try (var traversal = NbtTraversalGuard.enter()) {
            if (!traversal.entered()) return;
            visitKeyedPatch("item." + type, components);
        }
    }

    private void handleItem(CompoundTag item) {
        if (item.isEmpty()) return;

        try (var traversal = NbtTraversalGuard.enter()) {
            if (!traversal.entered()) return;

            String itemId = ResourceIds.path(NbtUtils.getString(item, "id"));
            if (itemId.isBlank()) return;

            visitKeyedPatch("item." + itemId, NbtUtils.getCompound(item, "components"));
        }
    }

    private void visitKeyedPatch(String countType, CompoundTag components) {
        int itemIndex = TranslationContext.getTypeCounts(countType);
        try (var ignored = TranslationContext.pushKey(countType + "." + itemIndex)) {
            visitPatch(components, countType);
        }
    }

    /**
     * Translates every text-carrying component in a patch.
     *
     * <p>{@code countType} advances the upstream per-item counter, and is null when the patch has no
     * owning item to count.
     */
    private void visitPatch(CompoundTag components, String countType) {
        ChangeTracker item = new ChangeTracker();
        item.add(translateItemText(components));
        item.add(handleAttributeModifiers(components));
        if (this.tracker.add(item.isChanged()) && countType != null) {
            TranslationContext.increaseTypeCounts(countType);
        }

        this.tracker.add(handleBook(components));
        warnAboutWritableBook(components);
        handleNestedItems(components);
        this.tracker.add(handleNestedData(components));
    }

    private static boolean translateItemText(CompoundTag components) {
        ChangeTracker tracker = new ChangeTracker();
        tracker.add(
                TranslationUtils.translateNbtComponent(
                        components, "minecraft:custom_name", "name"));
        tracker.add(
                TranslationUtils.translateNbtComponent(
                        components, "minecraft:item_name", "item_name"));

        ListTag lore = NbtUtils.getList(components, "minecraft:lore");
        for (int i = 0; i < lore.size(); i++) {
            tracker.add(TranslationUtils.translateNbtComponent(lore, i, "lore.line" + i));
        }
        return tracker.isChanged();
    }

    /**
     * Reports a book-and-quill whose pages are left untranslated.
     *
     * <p>{@code writable_book_content} pages are plain strings rather than components, so they cannot
     * hold a translatable node. Replacing them would have to bake in one language, which is worse than
     * leaving them alone, so the content is only reported.
     */
    private static void warnAboutWritableBook(CompoundTag components) {
        String componentName = "minecraft:writable_book_content";
        if (!components.contains(componentName)) return;

        ListTag pages =
                NbtUtils.getList(
                        NbtUtils.getCompound(components, componentName),
                        "pages",
                        Tag.TAG_COMPOUND);
        if (pages.isEmpty()) return;

        Wtem.LOGGER.warn(
                "Skipping {} pages of a book and quill at {}: its pages are plain text and cannot"
                        + " hold a translatable component",
                pages.size(),
                TranslationContext.getKey());
    }

    private static boolean handleBook(CompoundTag components) {
        String componentName = "minecraft:written_book_content";
        if (!components.contains(componentName)) return false;

        CompoundTag book = NbtUtils.getCompound(components, componentName);
        int bookIndex = TranslationContext.getTypeCounts("book");
        ChangeTracker tracker = new ChangeTracker();

        try (var ignored = TranslationContext.pushKey("book." + bookIndex)) {
            ListTag pages = NbtUtils.getList(book, "pages", Tag.TAG_COMPOUND);
            for (int i = 0; i < pages.size(); i++) {
                CompoundTag page = NbtUtils.getCompound(pages, i);
                tracker.add(
                        TranslationUtils.translateNbtComponent(page, "raw", "content.page" + i));
                // almost never used
                // tracker.add(
                //        TranslationUtils.translateNbtComponent(page, "filtered", "content.page" + i + ".filtered"));
            }

            if (!components.contains("minecraft:custom_name")) {
                String title = NbtUtils.getString(NbtUtils.getCompound(book, "title"), "raw");
                tracker.add(
                        TranslationUtils.putTranslatedNbtComponent(
                                components,
                                "minecraft:custom_name",
                                title,
                                true,
                                "title"));
            }
        }

        if (tracker.isChanged()) TranslationContext.increaseTypeCounts("book");
        return tracker.isChanged();
    }

    private static boolean handleAttributeModifiers(CompoundTag components) {
        ListTag modifiers = NbtUtils.getList(components, "minecraft:attribute_modifiers");
        ChangeTracker tracker = new ChangeTracker();
        for (int i = 0; i < modifiers.size(); i++) {
            CompoundTag modifier = NbtUtils.getCompound(modifiers, i);
            tracker.add(
                    TranslationUtils.translateNbtComponent(
                            modifier,
                            "display.value",
                            "attribute_modifier." + i + ".display"));
        }
        return tracker.isChanged();
    }

    private void handleNestedItems(CompoundTag components) {
        ListTag container = NbtUtils.getList(components, "minecraft:container", Tag.TAG_COMPOUND);
        for (int i = 0; i < container.size(); i++) {
            handleItem(NbtUtils.getCompound(NbtUtils.getCompound(container, i), "item"));
        }

        for (String componentName :
                List.of("minecraft:bundle_contents", "minecraft:charged_projectiles")) {
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
                List.of("minecraft:entity_data", "minecraft:bucket_entity_data")) {
            CompoundTag entity = NbtUtils.getCompound(components, componentName);
            if (!entity.isEmpty()) entity.accept(entityVisitor);
        }

        CompoundTag blockEntity = NbtUtils.getCompound(components, "minecraft:block_entity_data");
        boolean changed =
                !blockEntity.isEmpty()
                        && new BlockEntityWHandler()
                                .handle(blockEntity, WtemConfig.active().rebuildNestedKeys());

        ListTag bees = NbtUtils.getList(components, "minecraft:bees", Tag.TAG_COMPOUND);
        for (int i = 0; i < bees.size(); i++) {
            // Occupant#entity_data stores the entity compound directly, so there is no nested
            // 'entity' wrapper here, unlike SpawnData.
            CompoundTag entityData = NbtUtils.getCompound(NbtUtils.getCompound(bees, i), "entity_data");
            if (!entityData.isEmpty()) entityData.accept(entityVisitor);
        }
        return changed || entityVisitor.isChanged();
    }
}
