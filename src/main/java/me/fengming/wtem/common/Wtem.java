package me.fengming.wtem.common;

import com.mojang.logging.LogUtils;
import me.fengming.wtem.common.core.handler.datapack.FunctionHandler;
import me.fengming.wtem.common.core.handler.datapack.ItemModifierHandler;
import me.fengming.wtem.common.core.handler.datapack.LootTableHandler;
import me.fengming.wtem.common.core.handler.datapack.PredicateHandler;
import me.fengming.wtem.common.core.handler.datapack.ResourceHandlers;
import me.fengming.wtem.common.core.handler.datapack.SimpleJsonHandler;
import me.fengming.wtem.common.core.handler.datapack.StructureHandler;
import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;

/**
 * @author FengMing
 */
public class Wtem implements ModInitializer {
    public static final Logger LOGGER = LogUtils.getLogger();

    @Override
    public void onInitialize() {
        ResourceHandlers.addHandler(SimpleJsonHandler.AdvancementHandlerSimple.FACTORY);
        ResourceHandlers.addHandler(SimpleJsonHandler.EnchantmentHandlerSimple.FACTORY);
        ResourceHandlers.addHandler(
                SimpleJsonHandler.factory("jukebox_song", "description"));
        ResourceHandlers.addHandler(
                SimpleJsonHandler.factory("trim_material", "description"));
        ResourceHandlers.addHandler(
                SimpleJsonHandler.factory("trim_pattern", "description"));
        ResourceHandlers.addHandler(
                SimpleJsonHandler.factory("painting_variant", "title", "author"));
        ResourceHandlers.addHandler(
                SimpleJsonHandler.factory("instrument", "description"));
        ResourceHandlers.addHandler(
                SimpleJsonHandler.factory(
                        "dialog",
                        "title",
                        "external_title",
                        "body[*].contents",
                        "inputs[*].label",
                        "inputs[*].options[*].display",
                        "action.label",
                        "action.tooltip",
                        "yes.label",
                        "yes.tooltip",
                        "no.label",
                        "no.tooltip",
                        "actions[*].label",
                        "actions[*].tooltip"));
        ResourceHandlers.addHandler(
                SimpleJsonHandler.factory(
                        "dimension_type",
                        "attributes.minecraft:gameplay/bed_rule.error_message"));
        ResourceHandlers.addHandler(ItemModifierHandler.FACTORY);
        ResourceHandlers.addHandler(LootTableHandler.FACTORY);
        ResourceHandlers.addHandler(PredicateHandler.FACTORY);
        ResourceHandlers.addHandler(FunctionHandler.FACTORY);
        ResourceHandlers.addHandler(StructureHandler.FACTORY);
    }
}
