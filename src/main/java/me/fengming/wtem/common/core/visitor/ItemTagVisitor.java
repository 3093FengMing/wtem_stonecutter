package me.fengming.wtem.common.core.visitor;

import java.util.List;
import java.util.function.Consumer;
import me.fengming.wtem.common.core.extraction.TranslationContext;
import me.fengming.wtem.common.core.handler.BlockEntityWHandler;
import me.fengming.wtem.common.util.ChangeTracker;
import me.fengming.wtem.common.util.NbtUtils;
import me.fengming.wtem.common.util.ResourceIds;
import me.fengming.wtem.common.util.TranslationUtils;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.component.CustomData;

/**
 * Extracts components from item stacks, including item stacks nested in data components.
 *
 * @author FengMing
 */
public class ItemTagVisitor implements SimpleTagVisitor {
    private final ChangeTracker tracker = new ChangeTracker();
    private final Consumer<CompoundTag> componentValidator;
    private final boolean preserveCommandMacros;

    public ItemTagVisitor() {
        this(ItemTagVisitor::validateCustomData, false);
    }

    public ItemTagVisitor(Consumer<CompoundTag> componentValidator) {
        this(componentValidator, false);
    }

    /** Creates an item visitor for command payloads that must retain direct custom-name macros. */
    public ItemTagVisitor(
            Consumer<CompoundTag> componentValidator, boolean preserveCommandMacros) {
        this.componentValidator =
                componentValidator == null
                        ? ItemTagVisitor::validateCustomData
                        : componentValidator;
        this.preserveCommandMacros = preserveCommandMacros;
    }

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

            // The item is appended to whatever holds it, so a sword in a shulker box in a chest reads
            // as the whole chain. That path is what a translator needs to find the stack again.
            try (var ignored =
                    TranslationContext.pushSubject(NbtUtils.getString(item, "id"))) {
                visitKeyedPatch("item." + itemId, NbtUtils.getCompound(item, "components"));
            }
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
        this.componentValidator.accept(components);
        ChangeTracker item = new ChangeTracker();
        item.add(translateItemText(components));
        item.add(handleAttributeModifiers(components));
        if (this.tracker.add(item.isChanged()) && countType != null) {
            TranslationContext.increaseTypeCounts(countType);
        }

        this.tracker.add(handleBook(components));
        extractWritableBook(components);
        handleNestedItems(components);
        this.tracker.add(handleNestedData(components));
    }

    private boolean translateItemText(CompoundTag components) {
        ChangeTracker tracker = new ChangeTracker();
        String customName = componentKey(components, "minecraft:custom_name");
        String itemName = componentKey(components, "minecraft:item_name");
        tracker.add(
                this.preserveCommandMacros
                        ? TranslationUtils.translateNbtComponentPreservingMacros(
                                components, customName, "name")
                        : TranslationUtils.translateNbtComponent(components, customName, "name"));
        tracker.add(
                TranslationUtils.translateNbtComponent(
                        components, itemName, "item_name"));

        ListTag lore =
                NbtUtils.getList(components, componentKey(components, "minecraft:lore"));
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
    private static void extractWritableBook(CompoundTag components) {
        String componentName = componentKey(components, "minecraft:writable_book_content");
        if (!components.contains(componentName)) return;

        ListTag pages =
                NbtUtils.getList(
                        NbtUtils.getCompound(components, componentName),
                        "pages",
                        Tag.TAG_COMPOUND);
        if (pages.isEmpty()) return;

        int bookIndex = TranslationContext.getTypeCounts("writable_book");
        int recordsBefore = TranslationContext.recordCount();
        boolean hasPlainText = false;
        try (var ignored =
                TranslationContext.pushKey("writable_book." + bookIndex)) {
            boolean skipFiltered = TranslationContext.config().skipped().filteredText();
            for (int i = 0; i < pages.size(); i++) {
                CompoundTag page = NbtUtils.getCompound(pages, i);
                hasPlainText |= !NbtUtils.getString(page, "raw").isBlank();
                addCatalogPage(page, "raw", "content.page" + i);
                if (!skipFiltered) {
                    hasPlainText |= !NbtUtils.getString(page, "filtered").isBlank();
                    addCatalogPage(page, "filtered", "content.page" + i + ".filtered");
                }
            }
            if (hasPlainText) {
                try (var warningPath = TranslationContext.push("content.pages")) {
                    TranslationContext.recordWarning(
                            "writable_book",
                            "Cataloged "
                                    + pages.size()
                                    + " book-and-quill page(s) without rewriting them: writable"
                                    + " pages are plain strings and cannot hold a translatable"
                                    + " component");
                }
            }
        }
        if (TranslationContext.recordCount() == recordsBefore) return;

        TranslationContext.increaseTypeCounts("writable_book");
    }

    private static void addCatalogPage(CompoundTag page, String field, String keyPath) {
        String text = NbtUtils.getString(page, field);
        if (text.isBlank()) return;
        try (var ignored = TranslationContext.push(keyPath)) {
            TranslationContext.addCatalogEntry(text);
        }
    }

    private static boolean handleBook(CompoundTag components) {
        String componentName = componentKey(components, "minecraft:written_book_content");
        if (!components.contains(componentName)) return false;

        CompoundTag book = NbtUtils.getCompound(components, componentName);
        int bookIndex = TranslationContext.getTypeCounts("book");
        ChangeTracker tracker = new ChangeTracker();

        try (var ignored = TranslationContext.pushKey("book." + bookIndex)) {
            boolean skipFiltered = TranslationContext.config().skipped().filteredText();
            ListTag pages = NbtUtils.getList(book, "pages", Tag.TAG_COMPOUND);
            for (int i = 0; i < pages.size(); i++) {
                CompoundTag page = NbtUtils.getCompound(pages, i);
                tracker.add(
                        TranslationUtils.translateNbtComponent(page, "raw", "content.page" + i));
                // The filtered page is the same page with the words a server's chat filter objects
                // to blanked out. Only a filtered server writes one, so the field is usually absent
                // and this usually does nothing, but where it is present leaving it in the original
                // language would show untranslated text to exactly the players who cannot see the
                // page beside it. Signs make the same choice for the same reason.
                if (!skipFiltered) {
                    tracker.add(
                            TranslationUtils.translateNbtComponent(
                                    page, "filtered", "content.page" + i + ".filtered"));
                }
            }

            String customName = componentKey(components, "minecraft:custom_name");
            if (!components.contains(customName)) {
                String title = NbtUtils.getString(NbtUtils.getCompound(book, "title"), "raw");
                tracker.add(
                        TranslationUtils.putTranslatedNbtComponent(
                                components,
                                componentKey(components, "minecraft:custom_name"),
                                title,
                                true,
                                "title"));
            }
            recordPlainWrittenBookString(book, "author");
        }

        if (tracker.isChanged()) TranslationContext.increaseTypeCounts("book");
        return tracker.isChanged();
    }

    private static void recordPlainWrittenBookString(CompoundTag book, String field) {
        String value = NbtUtils.getString(book, field);
        if (value.isBlank()) return;
        try (var ignored = TranslationContext.push(field)) {
            TranslationContext.recordWarning(
                    "written_book_string",
                    "Written-book "
                            + field
                            + " is a plain string and remains unchanged; it cannot carry a"
                            + " translatable component");
        }
    }

    private static boolean handleAttributeModifiers(CompoundTag components) {
        ListTag modifiers =
                NbtUtils.getList(
                        components, componentKey(components, "minecraft:attribute_modifiers"));
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
        ListTag container =
                NbtUtils.getList(
                        components,
                        componentKey(components, "minecraft:container"),
                        Tag.TAG_COMPOUND);
        for (int i = 0; i < container.size(); i++) {
            handleItem(NbtUtils.getCompound(NbtUtils.getCompound(container, i), "item"));
        }

        for (String componentName :
                List.of("minecraft:bundle_contents", "minecraft:charged_projectiles")) {
            ListTag items =
                    NbtUtils.getList(
                            components, componentKey(components, componentName), Tag.TAG_COMPOUND);
            for (int i = 0; i < items.size(); i++) {
                handleItem(NbtUtils.getCompound(items, i));
            }
        }

        handleItem(
                NbtUtils.getCompound(
                        components, componentKey(components, "minecraft:use_remainder")));
    }

    private boolean handleNestedData(CompoundTag components) {
        EntityTagVisitor entityVisitor =
                new EntityTagVisitor(this.componentValidator, this.preserveCommandMacros);
        for (String componentName :
                List.of("minecraft:entity_data", "minecraft:bucket_entity_data")) {
            CompoundTag entity =
                    NbtUtils.getCompound(components, componentKey(components, componentName));
            if (!entity.isEmpty()) entity.accept(entityVisitor);
        }

        CompoundTag blockEntity =
                NbtUtils.getCompound(
                        components, componentKey(components, "minecraft:block_entity_data"));
        boolean changed =
                !blockEntity.isEmpty()
                        && new BlockEntityWHandler(
                                        this.componentValidator, this.preserveCommandMacros)
                                .handle(blockEntity, TranslationContext.config().rebuildNestedKeys());

        ListTag bees =
                NbtUtils.getList(
                        components, componentKey(components, "minecraft:bees"), Tag.TAG_COMPOUND);
        for (int i = 0; i < bees.size(); i++) {
            // Occupant#entity_data stores the entity compound directly, so there is no nested
            // 'entity' wrapper here, unlike SpawnData.
            CompoundTag entityData = NbtUtils.getCompound(NbtUtils.getCompound(bees, i), "entity_data");
            if (!entityData.isEmpty()) entityData.accept(entityVisitor);
        }
        return changed || entityVisitor.isChanged();
    }

    /**
     * Command SNBT commonly omits {@code minecraft:} for built-in item components.  The generic
     * summon NBT parser preserves that spelling when another component is malformed, so visitors
     * must recognize it before the item codec gets a chance to reject the whole stack.
     */
    private static String componentKey(CompoundTag components, String canonical) {
        if (components.contains(canonical)) return canonical;
        String shortName = canonical;
        int separator = canonical.indexOf(':');
        if (separator >= 0 && separator + 1 < canonical.length()) {
            shortName = canonical.substring(separator + 1);
        }
        return components.contains(shortName) ? shortName : canonical;
    }

    private static void validateCustomData(CompoundTag components) {
        String customData = componentKey(components, "minecraft:custom_data");
        Tag value = components.get(customData);
        if (value == null) return;

        try (var ignored = TranslationContext.push(customData)) {
            try {
                // Vanilla also accepts a string here when the string itself contains compound
                // SNBT. Use the component codec so that form is not mistaken for an invalid value.
                CustomData.CODEC
                        .parse(NbtOps.INSTANCE, value)
                        .error()
                        .ifPresent(
                                error ->
                                        TranslationContext.recordWarning(
                                                "item_component",
                                                "Item component custom_data could not be decoded by"
                                                        + " Minecraft's codec; WTEM preserved the"
                                                        + " original value and continued extraction: "
                                                        + error));
            } catch (RuntimeException exception) {
                TranslationContext.recordFailure("item_component", exception);
            }
        }
    }
}
