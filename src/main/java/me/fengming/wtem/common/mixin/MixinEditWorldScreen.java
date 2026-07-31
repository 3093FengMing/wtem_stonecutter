package me.fengming.wtem.common.mixin;

import static net.minecraft.client.gui.screens.worldselection.EditWorldScreen.makeBackupAndShowToast;

import it.unimi.dsi.fastutil.booleans.BooleanConsumer;
import me.fengming.wtem.common.gui.WtemScreen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.BackupConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.worldselection.EditWorldScreen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.storage.LevelStorageSource;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * @author FengMing
 */
@Mixin(EditWorldScreen.class)
public abstract class MixinEditWorldScreen extends Screen {
    @Shadow @Final private LinearLayout layout;
    @Shadow @Final private LevelStorageSource.LevelStorageAccess levelAccess;
    @Shadow @Final private BooleanConsumer callback;

    @Unique private static final Component WTEM_EXTRACT = Component.translatable("gui.wtem.extract");

    @Unique
    private static final Component WTEM_MAIN_WARN = Component.translatable("gui.wtem.main.warn");

    protected MixinEditWorldScreen(Component component) {
        super(component);
    }

    //~if >=26.1 '9' -> '11'
    @Inject(method = "<init>", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/layouts/LinearLayout;addChild(Lnet/minecraft/client/gui/layouts/LayoutElement;)Lnet/minecraft/client/gui/layouts/LayoutElement;", shift = At.Shift.AFTER, ordinal = 11))
    private void onInit(CallbackInfo ci) {
        this.layout.addChild(
                Button.builder(
                                WTEM_EXTRACT,
                                button ->
                                        wtem$setScreen(
                                                new BackupConfirmScreen(
                                                        () -> wtem$setScreen(this),
                                                        (isConfirm, isCancel) -> {
                                                            if (isConfirm) makeBackupAndShowToast(levelAccess);
                                                            wtem$setScreen(
                                                                    WtemScreen.create(
                                                                            minecraft,
                                                                            this.callback,
                                                                            minecraft.getFixerUpper(),
                                                                            levelAccess));
                                                        },
                                                        WTEM_MAIN_WARN,
                                                        WTEM_MAIN_WARN,
                                                        //? if >=1.21.4
                                                        CommonComponents.GUI_CONTINUE,
                                                        false)))
                        .width(200)
                        .build());
    }

    @Unique
    private void wtem$setScreen(Screen screen) {
        //? if >=26.2 {
        minecraft.setScreenAndShow(screen);
        //?} else
        //minecraft.setScreen(screen);
    }
}
