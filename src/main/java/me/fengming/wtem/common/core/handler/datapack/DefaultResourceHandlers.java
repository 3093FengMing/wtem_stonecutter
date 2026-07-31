package me.fengming.wtem.common.core.handler.datapack;

import java.util.List;

/** Defines the built-in extraction schema in deterministic processing order.
 * @author FengMing*/
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
                        "actions[*].tooltip"),
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
