//~ screen
package me.fengming.wtem.common.gui;

import com.mojang.datafixers.DataFixer;
import com.mojang.serialization.Dynamic;
import it.unimi.dsi.fastutil.booleans.BooleanConsumer;
import me.fengming.wtem.common.Wtem;
import me.fengming.wtem.common.core.extraction.WorldExtractor;
import me.fengming.wtem.common.core.extraction.service.ExtractionProgress;
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

    private Button extractButton;
    private final BooleanConsumer callback;
    private final WorldExtractor worldExtractor;
    private boolean completionHandled;

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
            return new WtemScreen(callback, dataFixer, worldStem, levelStorage, registry);
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
            RegistryAccess registryAccess) {
        super(WTEM_SCREEN_TITLE);
        this.callback = callback;
        this.worldExtractor =
                new WorldExtractor(dataFixer, worldStem, levelStorage, registryAccess);
    }

    @Override
    protected void init() {
        super.init();
        this.addRenderableWidget(
                Button.builder(
                                CommonComponents.GUI_CANCEL,
                                button -> {
                                    this.completionHandled = true;
                                    this.worldExtractor.cancel();
                                    this.callback.accept(false);
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
    }

    @Override
    public void tick() {
        if (this.completionHandled) return;
        ExtractionStatus status = this.worldExtractor.getExtractionStatus();
        if (!status.isTerminal()) return;

        this.completionHandled = true;
        this.callback.accept(status.isSuccessful());
    }

    @Override
    public void onClose() {
        this.completionHandled = true;
        this.worldExtractor.cancel();
        this.callback.accept(false);
    }

    @Override
    public void removed() {
        if (!this.worldExtractor.getExtractionStatus().isTerminal()) {
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
        if (progress.totalChunks() <= 0) return;
        extractButton.visible = false;

        guiGraphics.fill(left - 1, bottom - 1, right + 1, top + 1, -16777216);
        guiGraphics.text(
                this.font,
                Component.translatable("gui.wtem.main.info.extracted", progress.converted()),
                left,
                40,
                -1);
        guiGraphics.text(
                this.font,
                Component.translatable("gui.wtem.main.info.total", progress.totalChunks()),
                left,
                40 + (9 + 3) * 2,
                -1);
        int warnings = this.worldExtractor.getReport().failures().size();
        if (warnings > 0) {
            guiGraphics.text(
                    this.font,
                    Component.translatable("gui.wtem.main.info.warnings", warnings),
                    left,
                    40 + 9 + 3,
                    0xFFAA00);
        }
        int process = 0;

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

        int o = progress.converted() + progress.skipped();
        Component component =
                Component.translatable(
                        "gui.wtem.main.progress.counter", o, progress.totalChunks());
        Component component2 =
                Component.translatable(
                        "gui.wtem.main.progress.percentage",
                        Mth.floor(progress.totalProgress() * 100.0F));
        guiGraphics.centeredText(this.font, component, this.width / 2, bottom + 2 * 9 + 2, -1);
        guiGraphics.centeredText(
                this.font, component2, this.width / 2, bottom + (top - bottom) / 2 - 9 / 2, -1);
    }
}
