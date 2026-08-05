package me.fengming.wtem.common.core.handler.datapack.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
    void feedsKnownValuesBackIntoMacroExtractionWithoutReplacingTheRuntimeMacro() {
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

        assertTrue(result.contains("\"translate\":\"$(marco)\""), result);
        assertTrue(TranslationContext.snapshot().containsValue("item.the_best_sword.name"));
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
    void materializesCallerArgumentsForNormalExtractionThenRestoresTheMacro() {
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

        assertTrue(result.contains("$(entity)"), result);
        assertTrue(result.contains("translate"), result);
        assertEquals("Hello", TranslationContext.snapshot().get("entity.pig.1.name"));
    }

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
    void restoresAMacroInsideANormallySerializedItemArgument() {
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

        assertTrue(result.contains("$(item)"), result);
        assertTrue(!result.contains("minecraft:$(item)"), result);
        assertTrue(result.contains("translate"), result);
        assertTrue(TranslationContext.snapshot().containsValue("Hello"));
    }

    @Test
    void restoresATranslatedMacroValueInsideAnItemComponent() {
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
        assertTrue(TranslationContext.snapshot().containsValue("Hello"));
    }

    @Test
    void catalogsEveryCallerValueUnderTheRuntimeTranslationKey() {
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

        assertTrue(result.contains("\"translate\":\"Hello $(name)\""), result);
        assertEquals("Hello Alice", TranslationContext.snapshot().get("Hello Alice"));
        assertEquals("Hello Bob", TranslationContext.snapshot().get("Hello Bob"));
        assertEquals(Set.of("Hello Alice", "Hello Bob"), TranslationContext.snapshot().keySet());
    }

    @Test
    void restoresOnlyTheMacroOccurrenceWhenEqualStaticValuesSurroundIt() {
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

        assertTrue(result.contains("\"translate\":\"1 $(value) 1\""), result);
    }

    @Test
    void restoresDifferentMacrosThatReceiveTheSameValue() {
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

        assertTrue(result.contains("\"translate\":\"$(left)/$(right)\""), result);
    }

    @Test
    void restoresEveryOccurrenceOfTheSameMacro() {
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

        assertTrue(result.contains("\"translate\":\"$(name) + $(name)\""), result);
    }

    @Test
    void restoresAMacroInsideANormallySerializedBlockArgument() {
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

        assertTrue(result.contains("$(block)"), result);
        assertTrue(!result.contains("minecraft:$(block)"), result);
        assertTrue(result.contains("translate"), result);
        assertTrue(TranslationContext.snapshot().containsValue("Storage"));
    }
}
