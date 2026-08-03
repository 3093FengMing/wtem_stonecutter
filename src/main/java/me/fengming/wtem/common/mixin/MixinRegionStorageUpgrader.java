package me.fengming.wtem.common.mixin;

import me.fengming.wtem.common.core.extraction.WorldExtractor;
//~if >= 26.1 'WorldUpgrader' -> 'RegionStorageUpgrader'
import net.minecraft.util.worldupdate.RegionStorageUpgrader;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * @author FengMing
 */
//~if >= 26.1 'WorldUpgrader.AbstractUpgrader' -> 'RegionStorageUpgrader'
@Mixin(RegionStorageUpgrader.class)
public class MixinRegionStorageUpgrader {
    @ModifyVariable(method = "upgrade", ordinal = 1, at = @At(value = "LOAD", ordinal = 0), name = "converted")
    private boolean ensureConvert(boolean converted) {
        return (Object) this instanceof WorldExtractor.ChunkExtractor;
    }
}

