package me.fengming.wtem.common;

import me.fengming.wtem.common.config.WtemConfig;
import me.fengming.wtem.common.core.handler.datapack.DefaultResourceHandlers;
import me.fengming.wtem.common.core.handler.datapack.ResourceHandlers;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author FengMing
 */
public final class Wtem implements ClientModInitializer {
    public static final String MOD_ID = "wtem";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitializeClient() {
        ResourceHandlers.initialize(DefaultResourceHandlers.create());
        WtemConfig.initialize(
                WtemConfig.loadOrCreate(FabricLoader.getInstance().getConfigDir()));
    }
}
