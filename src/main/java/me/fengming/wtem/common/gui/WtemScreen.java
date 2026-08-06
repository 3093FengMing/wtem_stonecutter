//~ screen
package me.fengming.wtem.common.gui;

import com.mojang.datafixers.DataFixer;
import com.mojang.serialization.Dynamic;
import it.unimi.dsi.fastutil.booleans.BooleanConsumer;
import me.fengming.wtem.common.Wtem;
import me.fengming.wtem.common.core.extraction.WorldExtractor;
import me.fengming.wtem.common.core.extraction.service.ExtractionChoices;
import me.fengming.wtem.common.core.extraction.service.AiTranslationProgress;
import me.fengming.wtem.common.core.extraction.service.ExtractionProgress;
import me.fengming.wtem.common.core.extraction.service.ExtractionReport;
import me.fengming.wtem.common.core.extraction.service.ExtractionStatus;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.worldselection.WorldOpenFlows;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.WorldStem;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.server.packs.repository.ServerPacksSource;
import net.minecraft.util.Mth;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.level.storage.WorldData;

/**
 * @author FengMing
 */
@Environment(EnvType.CLIENT)
public class WtemScreen extends Screen {
    public static final Component WTEM_SCREEN_TITLE = Component.translatable("gui.wtem.main.title");
    public static final Component WTEM_EXTRACT = Component.translatable("gui.wtem.extract");
    public static final Component WTEM_CONFIG = Component.translatable("gui.wtem.config.button");

    private Button extractButton;
    private Button selectButton;
    private Button configButton;
    private Button closeButton;
    private final BooleanConsumer callback;
    private final WorldExtractor worldExtractor;
    private final ExtractionChoices choices;
    private boolean completionHandled;
    private boolean navigatingToChild;

    public static WtemScreen create(
            Minecraft mc,
            BooleanConsumer callback,
            DataFixer dataFixer,
            LevelStorageSource.LevelStorageAccess levelStorage) {
        WorldStem worldStem = null;
        try {
            WorldOpenFlows worldOpenFlows = mc.createWorldOpenFlows();
            PackRepository packRepository = ServerPacksSource.createPackRepository(levelStorage);

            //? if >=26.1
            Dynamic<?> unfixedDataTag = levelStorage.getUnfixedDataTagWithFallback();
            Dynamic<?> dataTag =
                    //? if >=26.1 {
                    DataFixTypes.LEVEL.updateToCurrentVersion(
                            DataFixers.getDataFixer(), unfixedDataTag, NbtUtils.getDataVersion(unfixedDataTag));
            //?} else
            //levelStorage.getDataTag();

            worldStem =
                    worldOpenFlows.loadWorldStem(
                            /*?if >=26.1 >>*/ levelStorage, dataTag, false, packRepository);
            RegistryAccess.Frozen registry = worldStem.registries().compositeAccess();
            //? if >= 26.1 {
            WorldData worldData = worldStem.worldDataAndGenSettings().data();
            levelStorage.saveDataTag(worldData);
            //?} else {
            /*levelStorage.saveDataTag(registry, worldStem.worldData());

            *///?}
            ExtractionChoices choices =
                    ExtractionChoices.discover(
                            worldStem, levelStorage.getLevelPath(net.minecraft.world.level.storage.LevelResource.ROOT));
            return new WtemScreen(
                    callback, dataFixer, worldStem, levelStorage, registry, choices);
        } catch (Exception e) {
            if (worldStem != null) worldStem.close();
            Wtem.LOGGER.warn("Failed to load world, can't extract world", e);
        }
        return null;
    }

    private WtemScreen(
            BooleanConsumer callback,
            DataFixer dataFixer,
            WorldStem worldStem,
            LevelStorageSource.LevelStorageAccess levelStorage,
            RegistryAccess registryAccess,
            ExtractionChoices choices) {
        super(WTEM_SCREEN_TITLE);
        this.callback = callback;
        this.worldExtractor =
                new WorldExtractor(dataFixer, worldStem, levelStorage, registryAccess);
        this.choices = choices == null ? ExtractionChoices.EMPTY : choices;
    }

    @Override
    protected void init() {
        super.init();
        this.navigatingToChild = false;
        this.closeButton =
                this.addRenderableWidget(
                Button.builder(
                                CommonComponents.GUI_CANCEL,
                                button -> {
                                    ExtractionStatus status =
                                            this.worldExtractor.getExtractionStatus();
                                    if (!status.isTerminal()) this.worldExtractor.cancel();
                                    this.finish(status.isTerminal() && status.isSuccessful());
                                })
                        .bounds(this.width / 2 - 100, this.height / 4 + 150, 200, 20)
                        .build());

        extractButton =
                this.addRenderableWidget(
                        Button.builder(
                                        WTEM_EXTRACT,
                                        button -> {
                                            button.active = false;
                                            this.worldExtractor.startThread();
                                        })
                                .bounds(this.width / 2 - 100, this.height / 4 + 120, 200, 20)
                                .build());

        configButton =
                this.addRenderableWidget(
                        Button.builder(
                                        WTEM_CONFIG,
                                        button -> {
                                            if (this.worldExtractor.getExtractionStatus()
                                                    != ExtractionStatus.READY) return;
                                            this.navigatingToChild = true;
                                            try {
                                                Screen config = WtemConfigScreen.create(this);
                                                //? if >=26.2 {
                                                this.minecraft.setScreenAndShow(config);
                                                //?} else {
                                                /*this.minecraft.setScreen(config);
                                                *///?}
                                            } catch (RuntimeException exception) {
                                                this.navigatingToChild = false;
                                                Wtem.LOGGER.warn(
                                                        "Failed to open WTEM configuration screen",
                                                        exception);
                                            }
                                        })
                                .bounds(this.width / 2 - 100, this.height / 4 + 90, 200, 20)
                                .build());

        selectButton =
                this.addRenderableWidget(
                        Button.builder(
                                        Component.translatable("gui.wtem.select.button"),
                                        button -> {
                                            if (this.worldExtractor.getExtractionStatus()
                                                    != ExtractionStatus.READY) return;
                                            this.navigatingToChild = true;
                                            try {
                                                Screen selection =
                                                        WtemSelectionScreen.create(this, this.choices);
                                                //? if >=26.2 {
                                                this.minecraft.setScreenAndShow(selection);
                                                //?} else {
                                                /*this.minecraft.setScreen(selection);
                                                *///?}
                                            } catch (RuntimeException exception) {
                                                this.navigatingToChild = false;
                                                Wtem.LOGGER.warn(
                                                        "Failed to open WTEM selection screen",
                                                        exception);
                                            }
                                        })
                                .bounds(this.width / 2 - 100, this.height / 4 + 60, 200, 20)
                                .build());
    }

    private void finish(boolean successful) {
        if (this.completionHandled) return;
        this.completionHandled = true;
        this.callback.accept(successful);
    }

    @Override
    public void onClose() {
        ExtractionStatus status = this.worldExtractor.getExtractionStatus();
        if (!status.isTerminal()) this.worldExtractor.cancel();
        this.finish(status.isTerminal() && status.isSuccessful());
    }

    @Override
    public void removed() {
        if (!this.navigatingToChild && !this.worldExtractor.getExtractionStatus().isTerminal()) {
            this.worldExtractor.cancel();
        }
    }

    @Override
    public void extractRenderState(
            GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(guiGraphics, mouseX, mouseY, partialTick);
        guiGraphics.centeredText(this.font, this.title, this.width / 2, 20, -1);
        int left = this.width / 2 - 150;
        int right = this.width / 2 + 150;
        int bottom = this.height / 4 + 100;
        int top = bottom + 10;
        ExtractionProgress progress = this.worldExtractor.getExtractionProgress();
        AiTranslationProgress aiProgress = this.worldExtractor.getAiTranslationProgress();
        ExtractionReport report = this.worldExtractor.getReport();
        ExtractionStatus status = this.worldExtractor.getExtractionStatus();
        boolean aiTranslating = status == ExtractionStatus.AI_TRANSLATING;
        boolean started = status != ExtractionStatus.READY;
        this.closeButton.setMessage(
                status.isTerminal()
                        ? Component.translatable("gui.wtem.main.exit")
                        : CommonComponents.GUI_CANCEL);
        extractButton.visible = !started;
        selectButton.visible = !started;
        configButton.visible = !started;
        // Before extraction the progress bar occupies the same vertical area as the configuration
        // button. It also has no meaningful counters yet. Region extraction may be disabled, so the
        // run state rather than totalChunks decides when the progress/report area becomes visible.
        if (!started) return;

        guiGraphics.fill(left - 1, bottom - 1, right + 1, top + 1, -16777216);
        guiGraphics.text(
                this.font,
                Component.translatable(
                        aiTranslating
                                ? "gui.wtem.main.extraction.ai_translating"
                                : status.isTerminal()
                                ? "gui.wtem.main.extraction.finished"
                                : "gui.wtem.main.extraction.working"),
                left,
                40,
                -1);
        guiGraphics.text(
                this.font,
                Component.translatable("gui.wtem.main.info.extracted", progress.converted()),
                left,
                40 + 9 + 3,
                -1);
        if (aiTranslating) {
            guiGraphics.text(
                    this.font,
                    Component.translatable(
                            "gui.wtem.main.ai.progress.entries",
                            aiProgress.completedEntries(),
                            aiProgress.totalEntries()),
                    left,
                    40 + (9 + 3) * 3,
                    -1);
            guiGraphics.text(
                    this.font,
                    Component.translatable(
                            "gui.wtem.main.ai.progress.batches",
                            aiProgress.completedBatches(),
                            aiProgress.totalBatches()),
                    left,
                    40 + (9 + 3) * 4,
                    -1);
        } else {
            guiGraphics.text(
                    this.font,
                    Component.translatable("gui.wtem.main.info.total", progress.totalChunks()),
                    left,
                    40 + (9 + 3) * 3,
                    -1);
        }
        // Keep this row in the live extraction layout even while the worker has not reported a
        // warning yet.  Diagnostics are appended from the extraction thread; conditionally
        // creating the row made the status area appear to lose the warning counter when the first
        // frame was rendered before a parser warning arrived.
        int warnings = report.failures().size();
        guiGraphics.text(
                this.font,
                Component.translatable("gui.wtem.main.info.warnings", warnings),
                left,
                40 + (9 + 3) * 2,
                -1);
        int process = 0;

        if (aiTranslating) {
            int n = Mth.floor(aiProgress.progress() * (right - left));
            guiGraphics.fill(left, bottom, left + n, top, 0xFF5E9CE6);
        } else {
            for (ExtractionProgress.DimensionProgress dimension : progress.dimensions()) {
                int n = Mth.floor(dimension.progress() * (right - left));
                guiGraphics.fill(
                        left + process,
                        bottom,
                        left + process + n,
                        top,
                        -2236963 * dimension.level().hashCode());
                process += n;
            }
        }

        Component component;
        Component component2;
        if (aiTranslating) {
            component =
                    Component.translatable(
                            "gui.wtem.main.ai.progress.batches",
                            aiProgress.completedBatches(),
                            aiProgress.totalBatches());
            component2 =
                    Component.translatable(
                            "gui.wtem.main.ai.progress.percentage",
                            Mth.floor(aiProgress.progress() * 100.0F));
        } else {
            int o = progress.converted() + progress.skipped();
            component =
                    Component.translatable(
                            "gui.wtem.main.progress.counter", o, progress.totalChunks());
            component2 =
                    Component.translatable(
                            "gui.wtem.main.progress.percentage",
                            Mth.floor(progress.totalProgress() * 100.0F));
        }
        guiGraphics.centeredText(this.font, component, this.width / 2, bottom + 2 * 9, -1);
        guiGraphics.centeredText(
                this.font, component2, this.width / 2, bottom + (top - bottom) / 2 - 9 / 2, -1);
        guiGraphics.text(
                this.font,
                Component.translatable(
                        "gui.wtem.main.info.summary",
                        report.translatedEntries(),
                        report.modifiedChunks(),
                        report.modifiedResources(),
                        report.modifiedSavedData()),
                left,
                top + 18,
                -1);
    }
}
