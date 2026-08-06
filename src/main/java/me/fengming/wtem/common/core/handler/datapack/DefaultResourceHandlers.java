package me.fengming.wtem.common.core.handler.datapack;

import me.fengming.wtem.common.core.handler.datapack.command.FunctionHandler;

import java.util.List;

/**
 * Defines the built-in extraction schema in deterministic processing order.
 *
 * @author FengMing
 */
public final class DefaultResourceHandlers {
    private DefaultResourceHandlers() {}

    public static List<HandlerFactory> create() {
        return List.of(
                SimpleJsonHandler.factory(
                        "advancement", "display.title", "display.description"),
                SimpleJsonHandler.factory("enchantment", "description"),
                SimpleJsonHandler.factory("jukebox_song", "description"),
                SimpleJsonHandler.factory("trim_material", "description"),
                SimpleJsonHandler.factory("trim_pattern", "description"),
                SimpleJsonHandler.factory("painting_variant", "title", "author"),
                SimpleJsonHandler.factory("instrument", "description"),
                SimpleJsonHandler.factory(
                        "dialog",
                        "title",
                        "external_title",
                        "description",
                        "body[*].contents",
                        "body[*].description",
                        "inputs[*].label",
                        "inputs[*].description",
                        "inputs[*].options[*].display",
                        "action",
                        "action.label",
                        "action.tooltip",
                        "action.description",
                        "yes",
                        "yes.label",
                        "yes.tooltip",
                        "yes.description",
                        "no",
                        "no.label",
                        "no.tooltip",
                        "no.description",
                        "exit_action",
                        "exit_action.label",
                        "exit_action.tooltip",
                        "exit_action.description",
                        "actions[*].action",
                        "actions[*].label",
                        "actions[*].tooltip",
                        "actions[*].description"),
                SimpleJsonHandler.factory(
                        "dimension_type",
                        "attributes.minecraft:gameplay/bed_rule.error_message"),
                ItemModifierHandler.FACTORY,
                LootTableHandler.FACTORY,
                PredicateHandler.FACTORY,
                FunctionHandler.FACTORY,
                StructureHandler.FACTORY);
    }
}
