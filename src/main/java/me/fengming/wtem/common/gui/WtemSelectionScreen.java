package me.fengming.wtem.common.gui;

import dev.isxander.yacl3.api.ConfigCategory;
import dev.isxander.yacl3.api.Option;
import dev.isxander.yacl3.api.OptionDescription;
import dev.isxander.yacl3.api.YetAnotherConfigLib;
import dev.isxander.yacl3.api.controller.TickBoxControllerBuilder;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import me.fengming.wtem.common.config.WtemConfig;
import me.fengming.wtem.common.config.WtemConfigManager;
import me.fengming.wtem.common.core.extraction.service.ExtractionChoices;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** YACL screen for the source selections used by the extraction run.
 *
 * @author FengMing
 */
@Environment(EnvType.CLIENT)
public final class WtemSelectionScreen {
    private WtemSelectionScreen() {}

    public static Screen create(Screen parent, ExtractionChoices choices) {
        ExtractionChoices available = choices == null ? ExtractionChoices.EMPTY : choices;
        Draft draft = Draft.from(WtemConfig.active(), available);
        YetAnotherConfigLib.Builder builder =
                YetAnotherConfigLib.createBuilder()
                        .title(text("gui.wtem.select.title"))
                        .save(() -> WtemConfigManager.saveAndActivate(draft.toConfig()));
        addSelectionCategory(
                builder,
                "gui.wtem.select.datapacks",
                "gui.wtem.select.datapacks.description",
                available.datapacks(),
                draft.datapacks);
        addSelectionCategory(
                builder,
                "gui.wtem.select.entities",
                "gui.wtem.select.entities.description",
                available.entities(),
                draft.entities);
        addSelectionCategory(
                builder,
                "gui.wtem.select.block_entities",
                "gui.wtem.select.block_entities.description",
                available.blockEntities(),
                draft.blockEntities);
        addSelectionCategory(
                builder,
                "gui.wtem.select.storage_files",
                "gui.wtem.select.storage_files.description",
                available.storageFiles(),
                draft.storageFiles);

        // A normal world always has at least the registry-backed entity categories.  Keep the
        // fallback useful for tests and unusual startup failures instead of asking YACL to build a
        // library with no categories at all.
        if (available.datapacks().isEmpty()
                && available.entities().isEmpty()
                && available.blockEntities().isEmpty()
                && available.storageFiles().isEmpty()) {
            ConfigCategory.Builder category =
                    ConfigCategory.createBuilder().name(text("gui.wtem.select.empty"));
            category.option(
                    Option.<Boolean>createBuilder()
                            .name(text("gui.wtem.select.empty"))
                            .description(OptionDescription.of(text("gui.wtem.select.empty.description")))
                            .binding(false, () -> false, ignored -> {})
                            .available(false)
                            .controller(TickBoxControllerBuilder::create)
                            .build());
            builder.category(category.build());
        }
        return builder.build().generateScreen(parent);
    }

    private static void addSelectionCategory(
            YetAnotherConfigLib.Builder builder,
            String categoryKey,
            String descriptionKey,
            List<String> values,
            ChoiceSelection selection) {
        if (values.isEmpty()) return;
        ConfigCategory.Builder category = ConfigCategory.createBuilder().name(text(categoryKey));
        for (String value : values) {
            category.option(
                    Option.<Boolean>createBuilder()
                            .name(Component.literal(value))
                            .description(OptionDescription.of(text(descriptionKey)))
                            .binding(
                                    true,
                                    () -> selection.isSelected(value),
                                    selected -> selection.setSelected(value, selected))
                            .controller(TickBoxControllerBuilder::create)
                            .build());
        }
        builder.category(category.build());
    }

    private static Component text(String key) {
        return Component.translatable(key);
    }

    private static final class Draft {
        private final ChoiceSelection datapacks;
        private final ChoiceSelection entities;
        private final ChoiceSelection blockEntities;
        private final ChoiceSelection storageFiles;

        private Draft(
                ChoiceSelection datapacks,
                ChoiceSelection entities,
                ChoiceSelection blockEntities,
                ChoiceSelection storageFiles) {
            this.datapacks = datapacks;
            this.entities = entities;
            this.blockEntities = blockEntities;
            this.storageFiles = storageFiles;
        }

        static Draft from(WtemConfig config, ExtractionChoices choices) {
            WtemConfig.Filters.Selection selection = config.filters().selection();
            return new Draft(
                    ChoiceSelection.of(selection.datapacks(), choices.datapacks()),
                    ChoiceSelection.of(selection.entities(), choices.entities()),
                    ChoiceSelection.of(selection.blockEntities(), choices.blockEntities()),
                    ChoiceSelection.of(selection.storageFiles(), choices.storageFiles()));
        }

        WtemConfig toConfig() {
            return WtemConfig.active()
                    .withSelection(
                            new WtemConfig.Filters.Selection(
                                    this.datapacks.values(),
                                    this.entities.values(),
                                    this.blockEntities.values(),
                                    this.storageFiles.values()));
        }
    }

    /**
     * An empty persisted list means "all". Once one discovered value is disabled the selection is
     * expanded to an explicit allowlist; enabling every discovered value again collapses it back to
     * the forward-compatible empty representation.
     */
    private static final class ChoiceSelection {
        private final List<String> available;
        private final Set<String> selected = new LinkedHashSet<>();
        private boolean explicit;

        static ChoiceSelection of(List<String> configured, List<String> available) {
            ChoiceSelection selection = new ChoiceSelection(available);
            if (configured != null && !configured.isEmpty()) {
                selection.explicit = true;
                if (!configured.contains(WtemConfig.Filters.Selection.NONE)) {
                    selection.selected.addAll(configured);
                }
            }
            return selection;
        }

        private ChoiceSelection(List<String> available) {
            this.available = available == null ? List.of() : List.copyOf(available);
        }

        boolean isSelected(String value) {
            return !this.explicit || this.selected.contains(value);
        }

        void setSelected(String value, boolean enabled) {
            if (!this.explicit) {
                this.selected.addAll(this.available);
                this.explicit = true;
            }
            if (enabled) this.selected.add(value);
            else this.selected.remove(value);

            if (this.selected.equals(new LinkedHashSet<>(this.available))) {
                this.selected.clear();
                this.explicit = false;
            }
        }

        List<String> values() {
            if (!this.explicit) return List.of();
            return this.selected.isEmpty()
                    ? List.of(WtemConfig.Filters.Selection.NONE)
                    : List.copyOf(this.selected);
        }
    }
}
