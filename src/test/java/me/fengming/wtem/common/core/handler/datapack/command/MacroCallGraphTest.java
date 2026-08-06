package me.fengming.wtem.common.core.handler.datapack.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import me.fengming.wtem.common.core.extraction.TranslationContext;
import me.fengming.wtem.common.core.extraction.service.ExtractionDiagnostics;
import net.minecraft.SharedConstants;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class MacroCallGraphTest {
    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        FunctionHandler.initializeParser(VanillaRegistries.createLookup(), new ExtractionDiagnostics());
    }

    @AfterEach
    void releaseCallGraph() {
        FunctionHandler.releaseMacroCallGraph();
        TranslationContext.release();
    }

    @Test
    void followsStorageValuesFromAFunctionCaller() {
        MacroCallGraph graph =
                MacroCallGraph.build(
                        Map.of(
                                "test:caller.mcfunction",
                                        "data modify storage test:runtime args set value {event_color:\"yellow\",reqwait:5}\n"
                                                + "function test:dialog with storage test:runtime args",
                                "test:dialog.mcfunction",
                                        "$title @a title {\"text\":\"$(reqwait)\",\"color\":\"$(event_color)\"}"));

        assertEquals(Set.of("5"), graph.values("test:dialog", "reqwait"));
        assertEquals(Set.of("yellow"), graph.values("test:dialog", "event_color"));
        assertEquals(
                List.of(Map.of("reqwait", "5", "event_color", "yellow")),
                graph.bindings("test:dialog"));
        assertTrue(graph.callers("test:dialog").contains("test:caller"));
        assertTrue(!graph.unresolved("test:dialog", "reqwait"));
    }

    @Test
    void asksBrigadierToRecognizeFunctionCallSyntaxBeforeIndexingIt() {
        MacroCallGraph.ParsedInvocation parsed =
                FunctionHandler.parseFunctionInvocation(
                        "function test:dialog with storage test:runtime args");

        assertEquals("test:dialog", parsed.target());
        assertEquals("test:runtime", parsed.storageId());
        assertEquals("args", parsed.path());
    }

    @Test
    void parameterizesTextMacrosAfterMaterializingTheirCallerValue() {
        FunctionHandler.initializeMacroCallGraph(
                Map.of(
                        "test:caller.mcfunction",
                                "data modify storage test:runtime args set value {marco:\"item.the_best_sword.name\"}\n"
                                        + "function test:dialog with storage test:runtime args",
                        "test:dialog.mcfunction", "$title @a title {\"text\":\"$(marco)\"}"));
        TranslationContext.clear();
        TranslationContext.setKey("datapack.test.dialog");

        String result =
                FunctionHandler.processFunction(
                        "$title @a title {\"text\":\"$(marco)\"}", "test:dialog.mcfunction");

        assertTrue(result.contains("\"translate\":\"datapack.test.dialog\""), result);
        assertTrue(result.contains("\"with\":[{\"text\":\"$(marco)\"}]"), result);
        assertEquals("%s", TranslationContext.snapshot().get("datapack.test.dialog"));
    }

    @Test
    void resolvesNonTextStyleMacrosButKeepsTextMacrosAsWithArguments() {
        String command =
                "$title @a title {\"text\":\"Hello $(name)\",\"color\":\"$(color)\"}";
        FunctionHandler.initializeMacroCallGraph(
                Map.of(
                        "test:caller.mcfunction",
                                "data merge storage test:runtime {name:\"World\",color:\"gold\"}\n"
                                        + "function test:styled with storage test:runtime",
                        "test:styled.mcfunction", command));
        TranslationContext.clear();
        TranslationContext.setKey("datapack.test.styled");

        String result = FunctionHandler.processFunction(command, "test:styled.mcfunction");

        assertTrue(result.contains("\"with\":[{\"text\":\"$(name)\"}]"), result);
        assertTrue(result.contains("\"color\":\"gold\""), result);
        assertTrue(!result.contains("$(color)"), result);
        assertEquals("Hello %s", TranslationContext.snapshot().get("datapack.test.styled"));
    }

    @Test
    void doesNotCreateAFunctionEdgeFromMaskedRuntimeArguments() {
        MacroCallGraph graph =
                MacroCallGraph.build(
                        Map.of(
                                "test:caller.mcfunction",
                                        "function test:dialog with storage test:runtime $(path)",
                                "test:dialog.mcfunction", "$title @a title {\"text\":\"$(value)\"}"));

        assertTrue(graph.callers("test:dialog").isEmpty());
        assertTrue(graph.values("test:dialog", "value").isEmpty());
    }

    @Test
    void materializesNonTextCallerArgumentsBeforeNormalExtraction() {
        FunctionHandler.initializeMacroCallGraph(
                Map.of(
                        "test:caller.mcfunction",
                                "data modify storage test:runtime args set value {entity:\"minecraft:pig\"}\n"
                                        + "function test:summon with storage test:runtime args",
                        "test:summon.mcfunction",
                                "$summon $(entity) ~ ~ ~ {CustomName:'{\"text\":\"Hello\"}'}"));
        TranslationContext.clear();
        TranslationContext.setKey("datapack.test.summon");

        String result =
                FunctionHandler.processFunction(
                        "$summon $(entity) ~ ~ ~ {CustomName:'{\"text\":\"Hello\"}'}",
                        "test:summon.mcfunction");

        assertTrue(result.contains("minecraft:pig"), result);
        assertTrue(!result.contains("$(entity)"), result);
        assertTrue(result.contains("translate"), result);
        assertEquals("Hello", TranslationContext.snapshot().get("entity.pig.1.name"));
    }

    @Test
    void doesNotTreatAnOrdinaryNbtNameFieldAsComponentText() {
        String command =
                "$summon minecraft:pig ~ ~ ~ {name:\"$(name)\",CustomName:'{\"text\":\"Guard\"}'}";
        FunctionHandler.initializeMacroCallGraph(
                Map.of(
                        "test:caller.mcfunction",
                                "data merge storage test:runtime {name:\"Alex\"}\n"
                                        + "function test:summon with storage test:runtime",
                        "test:summon.mcfunction", command));
        TranslationContext.clear();
        TranslationContext.setKey("datapack.test.summon");

        String result = FunctionHandler.processFunction(command, "test:summon.mcfunction");

        assertTrue(result.contains("Alex"), result);
        assertTrue(!result.contains("$(name)"), result);
        assertTrue(result.contains("translate"), result);
        assertEquals("Guard", TranslationContext.snapshot().get("entity.pig.1.name"));
    }

    @Test
    void keepsAScoreNameAsAStructuredTranslationArgument() {
        String command =
                "$title @a title [{\"text\":\"Score: \"},{\"score\":{\"name\":\"$(player)\",\"objective\":\"points\"}}]";
        FunctionHandler.initializeMacroCallGraph(
                Map.of(
                        "test:caller.mcfunction",
                                "data merge storage test:runtime {player:\"Alex\"}\n"
                                        + "function test:score with storage test:runtime",
                        "test:score.mcfunction", command));
        TranslationContext.clear();
        TranslationContext.setKey("datapack.test.score");

        String result = FunctionHandler.processFunction(command, "test:score.mcfunction");

        assertTrue(result.contains("\"translate\":\"datapack.test.score\""), result);
        assertTrue(result.contains("\"score\":{\"name\":\"$(player)\""), result);
        assertTrue(!result.contains("Alex"), result);
        assertEquals("Score: %s", TranslationContext.snapshot().get("datapack.test.score"));
    }

    //? if >=1.21.10 {
    @Test
    void emitsAllStructuredComponentMacrosAsRawWithArgumentsAndReparsesThem() {
        Map<String, String> components =
                Map.of(
                        "score", "{score:{name:\"@s\",objective:\"points\"}}",
                        "keybind", "{keybind:\"key.jump\"}",
                        "selector", "{selector:\"@s\"}",
                        "nbt", "{nbt:\"foo\",storage:\"test:runtime\"}",
                        "object", "{object:\"atlas\",atlas:\"minecraft:blocks\",sprite:\"minecraft:stone\"}");

        for (Map.Entry<String, String> component : components.entrySet()) {
            String command =
                    "$tellraw @s {\"text\":\"Before $(marco) after\",\"color\":\"gold\"}";
            FunctionHandler.initializeMacroCallGraph(
                    Map.of(
                            "test:caller.mcfunction",
                                    "data merge storage test:runtime {marco:"
                                            + component.getValue()
                                            + "}\nfunction test:structured with storage test:runtime",
                            "test:structured.mcfunction", command));
            TranslationContext.clear();
            TranslationContext.setKey("datapack.test.structured." + component.getKey());

            String result = FunctionHandler.processFunction(command, "test:structured.mcfunction");
            assertTrue(result.contains("\"with\":[$(marco)]"), component.getKey() + ": " + result);
            assertFalse(
                    result.contains("\"with\":[{\"text\":\"$(marco)\"}]"),
                    component.getKey() + ": " + result);

            String instantiated =
                    MacroArgumentRestorer.CommandLine.of(result, Map.of("marco", component.getValue()))
                            .text();
            assertTrue(
                    CommandParseSupport.isValidCommand(
                            CommandParseSupport.parserContext(), instantiated),
                    component.getKey() + " did not reparse: " + instantiated);
            FunctionHandler.releaseMacroCallGraph();
        }
    }
    //?}

    @Test
    void keepsArgumentsCorrelatedPerCallSite() {
        MacroCallGraph graph =
                MacroCallGraph.build(
                        Map.of(
                                "test:caller_a.mcfunction",
                                        "data modify storage test:a args set value {count:1,color:\"red\"}\n"
                                                + "function test:target with storage test:a args",
                                "test:caller_b.mcfunction",
                                        "data modify storage test:b args set value {count:2,color:\"blue\"}\n"
                                                + "function test:target with storage test:b args",
                                "test:target.mcfunction",
                                        "$title @a title {\"text\":\"$(count)\",\"color\":\"$(color)\"}"));

        assertEquals(
                Set.of(
                        Map.of("count", "1", "color", "red"),
                        Map.of("count", "2", "color", "blue")),
                Set.copyOf(graph.bindings("test:target")));
    }

    @Test
    void readsRootStorageArgumentsFromDataMerge() {
        MacroCallGraph graph =
                MacroCallGraph.build(
                        Map.of(
                                "test:caller.mcfunction",
                                        "data merge storage test:runtime {entity:\"minecraft:pig\"}\n"
                                                + "function test:target with storage test:runtime",
                                "test:target.mcfunction", "$summon $(entity) ~ ~ ~"));

        assertEquals(Set.of("minecraft:pig"), graph.values("test:target", "entity"));
        assertEquals(
                List.of(Map.of("entity", "minecraft:pig")),
                graph.bindings("test:target"));
    }

    @Test
    void combinesFieldsAssignedIndividuallyBeforeTheCallSite() {
        MacroCallGraph graph =
                MacroCallGraph.build(
                        Map.of(
                                "test:caller.mcfunction",
                                        "data modify storage test:runtime args.count set value 7\n"
                                                + "data modify storage test:runtime args.color set value \"gold\"\n"
                                                + "function test:target with storage test:runtime args",
                                "test:target.mcfunction",
                                        "$title @a title {\"text\":\"$(count)\",\"color\":\"$(color)\"}"));

        assertEquals(
                List.of(Map.of("count", "7", "color", "gold")),
                graph.bindings("test:target"));
    }

    @Test
    void readsAChildCompoundFromARootStorageMerge() {
        MacroCallGraph graph =
                MacroCallGraph.build(
                        Map.of(
                                "test:caller.mcfunction",
                                        "data merge storage test:runtime {args:{count:7,color:\"gold\"}}\n"
                                                + "function test:target with storage test:runtime args",
                                "test:target.mcfunction",
                                        "$title @a title {\"text\":\"$(count)\",\"color\":\"$(color)\"}"));

        assertEquals(
                List.of(Map.of("count", "7", "color", "gold")),
                graph.bindings("test:target"));
    }

    @Test
    void doesNotTreatAppendAsAStaticStorageReplacement() {
        assertNull(
                FunctionHandler.parseStorageAssignment(
                        "data modify storage test:runtime args append value {name:\"Later\"}"));

        MacroCallGraph graph =
                MacroCallGraph.build(
                        Map.of(
                                "test:caller.mcfunction",
                                        "data modify storage test:runtime args append value {name:\"Later\"}\n"
                                                + "function test:target with storage test:runtime args",
                                "test:target.mcfunction",
                                        "$title @a title {\"text\":\"$(name)\"}"));
        assertTrue(graph.values("test:target", "name").isEmpty());
        assertTrue(graph.unresolved("test:target", "name"));
    }

    @Test
    void materializesAMacroInsideAnItemIdentifier() {
        //? if >=1.21.5 {
        String command = "$give @s $(item)[item_name={\"text\":\"Hello\"}]";
        //?} else {
        /*String command = "$give @s $(item)[item_name='{\"text\":\"Hello\"}']";
        *///?}
        FunctionHandler.initializeMacroCallGraph(
                Map.of(
                        "test:caller.mcfunction",
                                "data merge storage test:runtime {item:\"minecraft:paper\"}\n"
                                        + "function test:item with storage test:runtime",
                        "test:item.mcfunction", command));
        TranslationContext.clear();
        TranslationContext.setKey("datapack.test.item");

        String result = FunctionHandler.processFunction(command, "test:item.mcfunction");

        assertTrue(result.contains("minecraft:paper"), result);
        assertTrue(!result.contains("$(item)"), result);
        assertTrue(result.contains("translate"), result);
        assertTrue(TranslationContext.snapshot().containsValue("Hello"));
    }

    @Test
    void parameterizesATextMacroInsideAnItemComponent() {
        //? if >=1.21.5 {
        String command = "$give @s paper[item_name={\"text\":\"$(name)\"}]";
        //?} else {
        /*String command = "$give @s paper[item_name='{\"text\":\"$(name)\"}']";
        *///?}
        FunctionHandler.initializeMacroCallGraph(
                Map.of(
                        "test:caller.mcfunction",
                                "data merge storage test:runtime {name:\"Hello\"}\n"
                                        + "function test:item_name with storage test:runtime",
                        "test:item_name.mcfunction", command));
        TranslationContext.clear();
        TranslationContext.setKey("datapack.test.item_name");

        String result = FunctionHandler.processFunction(command, "test:item_name.mcfunction");

        assertTrue(result.contains("translate"), result);
        assertTrue(result.contains("$(name)"), result);
        assertTrue(!result.contains("Hello"), result);
        assertEquals("%s", TranslationContext.snapshot().get("item.paper.1.item_name"));
    }

    @Test
    void keepsOneParameterizedTemplateAcrossMultipleCallerValues() {
        String command = "$title @a title {\"text\":\"Hello $(name)\"}";
        FunctionHandler.initializeMacroCallGraph(
                Map.of(
                        "test:caller_a.mcfunction",
                                "data merge storage test:a {name:\"Alice\"}\n"
                                        + "function test:greeting with storage test:a",
                        "test:caller_b.mcfunction",
                                "data merge storage test:b {name:\"Bob\"}\n"
                                        + "function test:greeting with storage test:b",
                        "test:greeting.mcfunction", command));
        TranslationContext.clear();
        TranslationContext.setKey("datapack.test.greeting");

        String result = FunctionHandler.processFunction(command, "test:greeting.mcfunction");

        assertTrue(result.contains("\"translate\":\"datapack.test.greeting\""), result);
        assertTrue(result.contains("\"with\":[{\"text\":\"$(name)\"}]"), result);
        assertEquals("Hello %s", TranslationContext.snapshot().get("datapack.test.greeting"));
        assertEquals(Set.of("datapack.test.greeting"), TranslationContext.snapshot().keySet());
    }

    @Test
    void keepsOnlyTheMacroOccurrenceWhenEqualStaticValuesSurroundIt() {
        String command = "$title @a title {\"text\":\"1 $(value) 1\"}";
        FunctionHandler.initializeMacroCallGraph(
                Map.of(
                        "test:caller.mcfunction",
                                "data merge storage test:runtime {value:\"1\"}\n"
                                        + "function test:repeated_value with storage test:runtime",
                        "test:repeated_value.mcfunction", command));
        TranslationContext.clear();
        TranslationContext.setKey("datapack.test.repeated_value");

        String result =
                FunctionHandler.processFunction(command, "test:repeated_value.mcfunction");

        assertTrue(result.contains("\"translate\":\"datapack.test.repeated_value\""), result);
        assertTrue(result.contains("\"with\":[{\"text\":\"$(value)\"}]"), result);
    }

    @Test
    void keepsDifferentTextMacrosThatReceiveTheSameValueDistinct() {
        String command = "$title @a title {\"text\":\"$(left)/$(right)\"}";
        FunctionHandler.initializeMacroCallGraph(
                Map.of(
                        "test:caller.mcfunction",
                                "data merge storage test:runtime {left:\"same\",right:\"same\"}\n"
                                        + "function test:same_values with storage test:runtime",
                        "test:same_values.mcfunction", command));
        TranslationContext.clear();
        TranslationContext.setKey("datapack.test.same_values");

        String result = FunctionHandler.processFunction(command, "test:same_values.mcfunction");

        assertTrue(result.contains("\"translate\":\"datapack.test.same_values\""), result);
        assertTrue(result.contains("\"text\":\"$(left)\""), result);
        assertTrue(result.contains("\"text\":\"$(right)\""), result);
    }

    @Test
    void keepsEveryOccurrenceOfTheSameTextMacro() {
        String command = "$title @a title {\"text\":\"$(name) + $(name)\"}";
        FunctionHandler.initializeMacroCallGraph(
                Map.of(
                        "test:caller.mcfunction",
                                "data merge storage test:runtime {name:\"Alex\"}\n"
                                        + "function test:repeated_macro with storage test:runtime",
                        "test:repeated_macro.mcfunction", command));
        TranslationContext.clear();
        TranslationContext.setKey("datapack.test.repeated_macro");

        String result =
                FunctionHandler.processFunction(command, "test:repeated_macro.mcfunction");

        assertTrue(result.contains("\"translate\":\"datapack.test.repeated_macro\""), result);
        assertTrue(result.contains("\"with\":[{\"text\":\"$(name)\"},{\"text\":\"$(name)\"}]"), result);
    }

    @Test
    void materializesAMacroInsideABlockIdentifier() {
        String command =
                "$setblock ~ ~ ~ $(block){CustomName:'{\"text\":\"Storage\"}'}";
        FunctionHandler.initializeMacroCallGraph(
                Map.of(
                        "test:caller.mcfunction",
                                "data merge storage test:runtime {block:\"minecraft:chest\"}\n"
                                        + "function test:block with storage test:runtime",
                        "test:block.mcfunction", command));
        TranslationContext.clear();
        TranslationContext.setKey("datapack.test.block");

        String result = FunctionHandler.processFunction(command, "test:block.mcfunction");

        assertTrue(result.contains("minecraft:chest"), result);
        assertTrue(!result.contains("$(block)"), result);
        assertTrue(result.contains("translate"), result);
        assertTrue(TranslationContext.snapshot().containsValue("Storage"));
    }
}
