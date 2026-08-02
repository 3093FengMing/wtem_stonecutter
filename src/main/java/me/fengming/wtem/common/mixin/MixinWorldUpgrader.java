package me.fengming.wtem.common.mixin;

import me.fengming.wtem.common.core.extraction.WorldExtractor;
import net.minecraft.util.worldupdate.WorldUpgrader;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * @author FengMing
 */
@Mixin(WorldUpgrader.class)
public class MixinWorldUpgrader {
    @Redirect(method = "<init>",at = @At(value = "INVOKE", target = "Ljava/lang/Thread;start()V"), require = 0)
    private void avoidDirectlyStartThread(Thread thread) {
        if ((Object) this instanceof WorldExtractor) return;
        thread.start();
    }
}
