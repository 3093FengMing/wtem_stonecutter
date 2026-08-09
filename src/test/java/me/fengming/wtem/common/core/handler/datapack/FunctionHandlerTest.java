package me.fengming.wtem.common.core.handler.datapack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import me.fengming.wtem.common.core.extraction.service.ExtractionDiagnostics;
import me.fengming.wtem.common.core.extraction.TranslationContext;
import me.fengming.wtem.common.core.handler.datapack.command.FunctionHandler;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class FunctionHandlerTest {
    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @BeforeEach
    void setUp() {
        TranslationContext.clear();
        TranslationContext.setKey("datapack.test.function");
    }

    @AfterEach
    void tearDown() {
        FunctionHandler.releaseParser();
        TranslationContext.release();
    }

    @Test
    void translatesComponentSpanningContinuationLines() {
        String result =
                FunctionHandler.processFunction(
                        List.of("  title @a title {\"text\":\\  ", "    \"Hello\"}  "));

        String[] lines = splitLines(result, "\n");
        assertEquals(2, lines.length);
        assertTrue(lines[0].startsWith("  "));
        assertTrue(lines[0].endsWith("\\  "));
        assertTrue(lines[1].startsWith("    "));
        assertTrue(lines[1].endsWith("  "));
        assertTrue(mergeContinuationLines(result).contains("translate"), result);
        assertEquals("Hello", TranslationContext.snapshot().get("datapack.test.function"));
    }

    @Test
    void preservesCrlfAndPhysicalLayoutAcrossContinuationLines() {
        String source =
                "  title @a title {\"text\":\\  \r\n"
                        + "    \"Hello\"}  \r\n"
                        + "# untouched\r\n";

        String result = FunctionHandler.processFunction(source);

        String[] lines = splitLines(result, "\r\n");
        assertEquals(4, lines.length);
        assertTrue(lines[0].startsWith("  "));
        assertTrue(lines[0].endsWith("\\  "));
        assertTrue(lines[1].startsWith("    "));
        assertTrue(lines[1].endsWith("  "));
        assertEquals("# untouched", lines[2]);
        assertEquals("", lines[3]);
        assertTrue(mergeContinuationLines(result).contains("translate"), result);
    }

    @Test
    void translatesGiveItemComponents() {
        String command =
                "give @s minecraft:paper[!minecraft:item_name,"
                        + "minecraft:custom_name='{\"text\":\"Hello\"}',"
                        + "minecraft:custom_data={foo:1b}]";

        String result = FunctionHandler.processFunction(List.of(command));

        assertNotEquals(command, result, result);
        assertTrue(result.contains("!minecraft:item_name"), result);
        assertTrue(result.contains("minecraft:custom_name"));
        assertTrue(result.contains("minecraft:custom_data"), result);
        assertTrue(result.contains("foo:1b"), result);
        assertTrue(result.contains("translate"));
        assertEquals("Hello", TranslationContext.snapshot().get("item.paper.1.name"));
    }

    @Test
    void writesAPlainTextItemNameBackAsAComponentRatherThanAString() {
        // A quoted component argument holds the literal text of a component, not a component
        // serialized to JSON, so the translation has to be written back as structured NBT. Writing
        // JSON there would leave an item literally named '{"translate":...}'.
        String command = "give @s wooden_pickaxe[item_name=\"Harvesting Implement\"]";

        String result = FunctionHandler.processFunction(List.of(command));

        //? if >=1.21.5 {
        assertEquals(
                "give @s minecraft:wooden_pickaxe"
                        + "[minecraft:item_name={translate:\"item.wooden_pickaxe.1.item_name\"}]",
                result);
        assertEquals(
                "Harvesting Implement",
                TranslationContext.snapshot().get("item.wooden_pickaxe.1.item_name"));
        // Before 1.21.5 a component argument had to be spelled as JSON, so the line below is not a
        // valid command on those versions and is left exactly as it stands.
        //?} else {
        /*assertEquals(command, result);
        assertTrue(TranslationContext.snapshot().isEmpty(), TranslationContext.snapshot()::toString);
        *///?}
    }

    @Test
    void keepsAJsonItemNameInItsSerializedForm() {
        // The same field spelled as JSON was already carrying a serialized component, and staying in
        // that form keeps the output as close to the input as the translation allows.
        String result =
                FunctionHandler.processFunction(
                        List.of(
                                "give @s wooden_pickaxe"
                                        + "[item_name='{\"text\":\"Harvesting Implement\"}']"));

        assertEquals(
                "give @s minecraft:wooden_pickaxe"
                        + "[minecraft:item_name='{\"translate\":\"item.wooden_pickaxe.1.item_name\"}']",
                result);
        assertEquals(
                "Harvesting Implement",
                TranslationContext.snapshot().get("item.wooden_pickaxe.1.item_name"));
    }

    @Test
    void translatesSetBlockEntityNbt() {
        String command =
                "setblock ~ ~ ~ minecraft:chest[facing=north]"
                        + "{CustomName:'{\"text\":\"Storage\"}'}";

        String result = FunctionHandler.processFunction(List.of(command));
        assertNotEquals(command, result);
        assertTrue(result.startsWith("setblock ~ ~ ~ minecraft:chest[facing=north]{"));
        assertTrue(result.contains("translate"));
        assertEquals("Storage", TranslationContext.snapshot().get("container.chest.1.name"));
    }

    // The supplied command uses the 26.2 writable-book component codec. The block-entity-level
    // regression test covers the same NBT path on every supported version.
    //? if >=26.2 {
    @Test
    void catalogsLecternWritableBookPagesFromTheSuppliedSetblockCommand() {
        String command =
                """
                /setblock 2 0 53 minecraft:lectern[facing=west,has_book=true,powered=false]{Book:{components:{"minecraft:writable_book_content":{pages:[{raw:" 붕어빵 기계는 우클릭을 하여 반죽을 구울 수 있다.\\n\\n 왼쪽 흰색 종을 우클릭하여 장사를 시작할 수 있다.\\n붕어빵을 15번 팔면 장사가 종료되며 쉴 수 있다.\\n\\n 흰색 종 옆 책을 우클릭 하여 현재 메뉴를 볼 수 있다.\\n\\n 노란색 종을 우클릭하여 메뉴를 거절할 수 있다."},{raw:"[ 붕어빵 결합 ]\\n\\n 종 옆에 있는 화로를 활용하여 붕어빵을 제조 할 수 있다.\\n\\n 화로 위에 붕어빵 1개를 던지고 알맞는 아이템을 던지면 새로운 붕어빵 반죽이 완성된다.\\n\\nex) 붕어빵 1개+슈크림 1개"}]}},count:1,id:"minecraft:writable_book"},Page:1,components:{}}
                """
                        .strip();

        String result = "/" + FunctionHandler.processFunction(command.substring(1));

        assertEquals(command, result, "plain writable pages must not be rewritten as JSON text");
        assertEquals(2, TranslationContext.snapshot().size());
        assertTrue(
                TranslationContext.snapshot().get("writable_book.1.content.page0")
                        .startsWith(" 붕어빵 기계는"));
        assertTrue(
                TranslationContext.snapshot().get("writable_book.1.content.page1")
                        .startsWith("[ 붕어빵 결합 ]"));
        assertTrue(TranslationContext.records().stream().noneMatch(record -> record.replaced()));
    }
    //?}

    @Test
    void translatesMacroLineWithoutInterpolations() {
        String command = "$title @a title {\"text\":\"Hello\"}";

        String result = FunctionHandler.processFunction(List.of(command));

        assertNotEquals(command, result, result);
        assertTrue(result.startsWith("$title @a title "), result);
        assertTrue(result.contains("translate"), result);
        assertEquals("Hello", TranslationContext.snapshot().get("datapack.test.function"));
    }

    @Test
    void turnsMixedMacroTextIntoADynamicTranslateTemplate() {
        String command = "$title @a title {\"text\":\"Hello $(name)\"}";

        String result = FunctionHandler.processFunction(List.of(command));

        assertTrue(result.contains("\"translate\":\"datapack.test.function\""), result);
        assertTrue(result.contains("\"with\":[{\"text\":\"$(name)\"}]"), result);
        assertEquals("Hello %s", TranslationContext.snapshot().get("datapack.test.function"));
    }

    @Test
    void turnsAnExactMacroTextIntoATranslateKeyWithoutAWarning() {
        ExtractionDiagnostics diagnostics = new ExtractionDiagnostics();
        FunctionHandler.initializeParser(
                net.minecraft.data.registries.VanillaRegistries.createLookup(), diagnostics);

        String command = "$title @a title {\"text\":\"$(marco)\"}";
        String source = "# warning location regression\n" + command;
        String result = FunctionHandler.processFunction(source, "cstore:ui/main_dynamic");

        assertEquals(
                "# warning location regression\n"
                        + "$title @a title {\"translate\":\"datapack.test.function\",\"with\":[{\"text\":\"$(marco)\"}]}",
                result);
        assertEquals("%s", TranslationContext.snapshot().get("datapack.test.function"));
        assertTrue(
                diagnostics.failures().stream()
                        .noneMatch(f -> "function_macro_component".equals(f.scope())),
                diagnostics.failures()::toString);
    }

    @Test
    void usesAValidMaskForDynamicColoursAndStillExtractsLiteralSiblings() {
        String command = "$title @a title {\"text\":\"Hello\",\"color\":\"$(event_color)\"}";

        String result = FunctionHandler.processFunction(command);

        assertTrue(result.contains("\"translate\":\"datapack.test.function\""), result);
        assertTrue(result.contains("\"color\":\"$(event_color)\""), result);
        assertEquals("Hello", TranslationContext.snapshot().get("datapack.test.function"));
    }

    @Test
    void warnsWhenASelectorUsesANameOption() {
        ExtractionDiagnostics diagnostics = new ExtractionDiagnostics();
        FunctionHandler.initializeParser(
                net.minecraft.data.registries.VanillaRegistries.createLookup(), diagnostics);

        String command = "execute as @e[name=???] run say hello";
        assertEquals(command, FunctionHandler.processFunction(command));
        assertTrue(
                diagnostics.failures().stream()
                        .anyMatch(f -> "function_selector_name".equals(f.scope())),
                diagnostics.failures()::toString);
    }

    @Test
    void extractsLiteralSiblingsFromACompositeMacroDialog() {
        String command =
                "$dialog show @s {\"type\":\"minecraft:multi_action\","
                        + "\"title\":{\"text\":\"영업 활동\",\"color\":\"yellow\"},"
                        + "\"body\":[{\"type\":\"minecraft:plain_message\","
                        + "\"contents\":{\"text\":\"👤 현재 손님 요청 없음\",\"color\":\"gray\"}},"
                        + "{\"type\":\"minecraft:plain_message\","
                        + "\"contents\":{\"text\":\"$(reqwait)초\",\"color\":\"$(event_color)\"}}]}";

        String result = FunctionHandler.processFunction(command);

        assertTrue(result.contains("translate"), result);
        assertTrue(result.contains("$(reqwait)"), result);
        assertTrue(result.contains("$(event_color)"), result);
        assertEquals(3, TranslationContext.snapshot().size());
        assertTrue(TranslationContext.snapshot().containsValue("영업 활동"));
        assertTrue(TranslationContext.snapshot().containsValue("👤 현재 손님 요청 없음"));
        assertTrue(TranslationContext.snapshot().containsValue("%s초"));
        assertTrue(!result.contains("\"color\":\"1\""), result);
    }

    @Test
    void extractsDialogArraysAndActionEventsFromAMacroCommand() {
        String command =
                "$dialog show @s {\"type\":\"minecraft:multi_action\","
                        + "\"description\":{\"text\":\"Dialog description\"},"
                        + "\"body\":[{\"type\":\"minecraft:plain_message\","
                        + "\"contents\":[{\"text\":\"First body\"},{\"text\":\"$(wait)초\"}]}],"
                        + "\"actions\":[{\"label\":{\"text\":\"Action label\","
                        + "\"hoverEvent\":{\"action\":\"show_text\",\"contents\":{\"text\":\"Action hover\"}},"
                        + "\"clickEvent\":{\"action\":\"run_command\",\"command\":\"title @s title {\\\"text\\\":\\\"Click command\\\"}\"}},"
                        + "\"action\":{\"type\":\"run_command\",\"command\":\"title @s title {\\\"text\\\":\\\"Action command\\\"}\"}}]}";

        String result = FunctionHandler.processFunction(List.of(command));

        assertTrue(result.contains("\"translate\""), result);
        assertTrue(result.contains("$(wait)"), result);
        assertTrue(result.contains("\"with\":[{\"text\":\"$(wait)\"}]"), result);
        assertTrue(TranslationContext.snapshot().containsValue("Dialog description"));
        assertTrue(
                TranslationContext.snapshot().values().stream()
                        .anyMatch(value -> value.startsWith("First body%s")),
                TranslationContext.snapshot()::toString);
        assertTrue(TranslationContext.snapshot().containsValue("Action label"));
        assertTrue(TranslationContext.snapshot().containsValue("Action hover"));
        assertTrue(TranslationContext.snapshot().containsValue("Click command"), TranslationContext.snapshot()::toString);
        assertTrue(TranslationContext.snapshot().containsValue("Action command"), TranslationContext.snapshot()::toString);
    }

    //? if >=26.2 {
    @Test
    void doesNotEmbedAComponentValueIntoAQuotedScalarMacroField() {
        String command =
                "$dialog show @s {\"type\":\"minecraft:multi_action\","
                        + "\"body\":[{\"type\":\"minecraft:plain_message\","
                        + "\"contents\":[{\"text\":\"$(main_req)\","
                        + "\"color\":\"$(main_req_color)\"}]}]}";
        FunctionHandler.initializeMacroCallGraph(
                Map.of(
                        "test:caller.mcfunction",
                                "data merge storage test:runtime {"
                                        + "main_req:{translate:\"known.request\"},"
                                        + "main_req_color:{translate:\"known.color\"}}\n"
                                        + "function test:scalar_guard with storage test:runtime",
                        "test:scalar_guard.mcfunction", command));
        ExtractionDiagnostics diagnostics = new ExtractionDiagnostics();
        FunctionHandler.initializeParser(
                net.minecraft.data.registries.VanillaRegistries.createLookup(), diagnostics);
        TranslationContext.clear();
        TranslationContext.setKey("datapack.test.scalar_guard");

        String result = FunctionHandler.processFunction(command, "test:scalar_guard.mcfunction");

        assertTrue(result.equals(command), result);
        assertTrue(!result.contains("\"color\":\"{translate:"), result);
        assertTrue(
                diagnostics.failures().stream()
                        .anyMatch(
                                failure ->
                                        "function_macro_binding".equals(failure.scope())
                                                && failure.cause() == null),
                diagnostics.failures()::toString);
    }
    //?}

    //? if >=1.21.8 {
    @Test
    void emitsStructuredTextMacroArgumentsAsRawWithComponents() {
        String command =
                "$tellraw @s {\"text\":\"Before $(marco) after\",\"color\":\"gold\"}";
        FunctionHandler.initializeMacroCallGraph(
                Map.of(
                        "test:caller.mcfunction",
                                "data merge storage test:runtime {marco:{translate:\"known.request\"}}\n"
                                        + "function test:target with storage test:runtime",
                        "test:target.mcfunction", command));
        FunctionHandler.initializeParser(
                net.minecraft.data.registries.VanillaRegistries.createLookup(),
                new ExtractionDiagnostics());
        TranslationContext.clear();
        TranslationContext.setKey("datapack.test.target");

        String result = FunctionHandler.processFunction(command, "test:target.mcfunction");

        assertTrue(result.contains("\"with\":[$(marco)]"), result);
        assertFalse(result.contains("\"with\":[{\"text\":\"$(marco)\"}]"), result);
        assertEquals("Before %s after", TranslationContext.snapshot().get("datapack.test.target"));
    }
    //?}

    // The inline dialog command is available in the 26.2 command tree. This verifies that a known
    // caller binding is materialized before command extraction and that the original macros are
    // restored after nested dialog components and action commands have been translated.
    //? if >=26.2 {
    @Test
    void materializesACompleteDialogMacroFromItsKnownCallerBinding() {
        String command =
                "$dialog show @s {\"type\":\"minecraft:multi_action\","
                        + "\"title\":{\"text\":\"Title $(event_name)\"},"
                        + "\"description\":{\"contents\":[{\"text\":\"Description $(reqwait)\"}]},"
                        + "\"body\":[{\"type\":\"minecraft:plain_message\","
                        + "\"contents\":[{\"text\":\"Body $(event_color)\"}]}],"
                        + "\"actions\":[{\"label\":{\"text\":\"Label $(event_name)\","
                        + "\"hoverEvent\":{\"action\":\"show_text\",\"contents\":{\"text\":\"Hover $(event_name)\"}},"
                        + "\"clickEvent\":{\"action\":\"run_command\",\"command\":\"title @s title {\\\"text\\\":\\\"Click $(event_name)\\\",\\\"color\\\":\\\"$(event_color)\\\"}\"}},"
                        + "\"action\":{\"type\":\"run_command\",\"command\":\"title @s title {\\\"text\\\":\\\"Action $(reqwait)\\\"}\"}}]}";
        FunctionHandler.initializeMacroCallGraph(
                Map.of(
                        "test:caller.mcfunction",
                                "data merge storage test:runtime {reqwait:5,event_color:\"yellow\",event_name:\"known.event\"}\n"
                                        + "function test:dialog with storage test:runtime",
                        "test:dialog.mcfunction", command));
        TranslationContext.clear();
        TranslationContext.setKey("datapack.test.dialog");

        String result = FunctionHandler.processFunction(command, "test:dialog.mcfunction");

        assertTrue(result.contains("$(reqwait)"), result);
        assertTrue(result.contains("$(event_color)"), result);
        assertTrue(result.contains("$(event_name)"), result);
        assertTrue(result.contains("\\\"color\\\":\\\"yellow\\\""), result);
        assertTrue(result.contains("translate"), result);
        for (String value :
                List.of(
                        "Title %s",
                        "Description %s",
                        "Body %s",
                        "Label %s",
                        "Hover %s",
                        "Click %s",
                        "Action %s")) {
            assertTrue(
                    TranslationContext.snapshot().containsValue(value),
                    () -> value + " not found in " + TranslationContext.snapshot());
        }
        assertTrue(
                TranslationContext.snapshot().values().stream()
                        .noneMatch(value -> value.contains("known.event") || value.equals("5") || value.equals("yellow")),
                TranslationContext.snapshot()::toString);
    }
    //?}

    @Test
    void translatesMacroLineArgumentBesideAnInterpolatedArgument() {
        String command =
                "$give @s minecraft:paper[minecraft:custom_name='{\"text\":\"Hello\"}'] $(count)";

        String result = FunctionHandler.processFunction(List.of(command));

        assertNotEquals(command, result, result);
        assertTrue(result.startsWith("$give @s "), result);
        assertTrue(result.endsWith(" $(count)"), result);
        assertTrue(result.contains("translate"), result);
        assertEquals("Hello", TranslationContext.snapshot().get("item.paper.1.name"));
    }

    @Test
    void translatesMacroLineWhereInterpolationSpansAWholeArgument() {
        String command = "$title @a title {\"text\":\"Hello\"} $(extra)";

        String result = FunctionHandler.processFunction(List.of(command));

        assertTrue(result.contains("\"translate\":\"datapack.test.function\""), result);
        assertTrue(TranslationContext.snapshot().containsValue("Hello"), TranslationContext.snapshot()::toString);
    }

    @Test
    void leavesInterpolatedResourceArgumentUntouched() {
        String command = "$setblock ~ ~ ~ $(block)";

        assertEquals(command, FunctionHandler.processFunction(List.of(command)));
        assertTrue(TranslationContext.snapshot().isEmpty(), TranslationContext.snapshot()::toString);
    }

    @Test
    void treatsLoneMacroMarkerAsUntranslatable() {
        assertEquals("$", FunctionHandler.processFunction(List.of("$")));
    }

    @Test
    void testCommandBlockLine() {
        String command = "/execute if score @e[tag=mem,limit=1] rtimer matches 0..8 at @e[tag=cyclist,tag=car_stop,tag=!bike_accid,y_rotation=175..185] unless entity @e[tag=cyclist1,distance=..2] run summon armor_stand ~ ~1.5 ~ {NoGravity:1b,Invulnerable:1b,Silent:1b,ShowArms:1b,NoBasePlate:1b,Tags:[\"cyclist\",\"cyclist1\",\"ped_hb\",\"pedm\"],Pose:{Body:[10f,0f,0f],LeftArm:[-38f,8f,0f],RightArm:[-40f,-10f,0f],LeftLeg:[-40f,0f,0f],RightLeg:[-40f,0f,0f],Head:[9f,0f,0f]},DisabledSlots:4144959,Rotation:[1F,0F],Passengers:[{id:\"minecraft:interaction\",width:0.52f,height:-1.4f,response:1b,Tags:[\"ped_int\",\"sp_ped_int\",\"cyclist\"]}],equipment:{legs:{id:\"minecraft:leather_leggings\",count:1,components:{\"minecraft:dyed_color\":5263961}},chest:{id:\"minecraft:leather_chestplate\",count:1,components:{\"minecraft:dyed_color\":6081023}},head:{id:\"minecraft:player_head\",count:1,components:{\"minecraft:profile\":{properties:[{name:\"textures\",value:\"eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvZjg0ZTY5MGVjODEyM2QzNmRjMDFhZmQ5YmYwNTgyMmY1YzZiNDk3ZWFhNTliNmMyYWFhNGJjMWU2NTViOTMyYSJ9fX0=\"}]}}}}}";
        assertNotEquals(command, "/" + FunctionHandler.processFunction(List.of(command)));
    }

    @Test
    void testSimpleDataModify() {
        String command = "execute if score segment animation matches 5 run data modify entity @e[tag=eye_03,limit=1] text set value \"TEST\"";
        String result = FunctionHandler.processFunction(List.of(command));

        assertNotEquals(command, result);
    }

    @Test
    void testSimpleDataModify2() {
        String command = "data modify entity @n[tag=station_craft_string] text set value [{\"color\":\"yellow\",\"text\":\"5 Flowers\"}, {\"color\":\"white\",\"text\":\" -> \"}, {\"color\":\"green\",\"text\":\"Jump Boost Potion\"}]";
        String result = FunctionHandler.processFunction(List.of(command));

        assertNotEquals(command, result);
    }

    @Test
    void leavesOrdinaryStorageStringsAsMacroScalars() {
        String text = "data modify storage cstore:ui main_req set value \"요청 상자 미등록\"";
        String color = "data modify storage cstore:ui main_req_color set value \"red\"";
        ExtractionDiagnostics diagnostics = new ExtractionDiagnostics();
        FunctionHandler.initializeParser(
                net.minecraft.data.registries.VanillaRegistries.createLookup(), diagnostics);

        String result = FunctionHandler.processFunction(text + "\n" + color, "cstore:storage");

        assertEquals(text + "\n" + color, result);
        assertTrue(TranslationContext.snapshot().isEmpty(), TranslationContext.snapshot()::toString);
        assertEquals(
                2,
                diagnostics.failures().stream()
                        .filter(failure -> "function_storage_string".equals(failure.scope()))
                        .count(),
                diagnostics.failures()::toString);
        assertTrue(
                diagnostics.failures().stream()
                        .filter(failure -> "function_storage_string".equals(failure.scope()))
                        .allMatch(
                                failure ->
                                        failure.resource()
                                                .contains("cstore:function/storage.mcfunction")),
                diagnostics.failures()::toString);
    }

    @Test
    void testItemInMarco() {
        // Before 1.21.5 item text components have to use serialized JSON strings. The command shape
        // under test is otherwise identical, especially the bounded slot argument after $(slot).
        //? if >=1.21.5 {
        String command = "$execute as @s[tag=locked_$(texture),tag=!unlocked_all] run item replace entity @s inventory.$(slot) with bone[item_name={\"bold\":false,\"color\":\"#666666\",\"italic\":false,\"text\":\"LOCKED\"},lore=[{\"bold\":false,\"color\":\"#666666\",\"italic\":false,\"text\":\"Unlock using advancements\"}]]";
        //?} else {
        /*String command = "$execute as @s[tag=locked_$(texture),tag=!unlocked_all] run item replace entity @s inventory.$(slot) with bone[item_name='{\"bold\":false,\"color\":\"#666666\",\"italic\":false,\"text\":\"LOCKED\"}',lore=['{\"bold\":false,\"color\":\"#666666\",\"italic\":false,\"text\":\"Unlock using advancements\"}']]";
        *///?}
        String result = FunctionHandler.processFunction(command);

        assertNotEquals(command, result, result);
        assertTrue(result.contains("tag=locked_$(texture)"), result);
        assertTrue(result.contains("inventory.$(slot)"), result);
        assertTrue(result.contains("translate"), result);
        assertEquals(
                "LOCKED", TranslationContext.snapshot().get("item.bone.1.item_name"));
        assertEquals(
                "Unlock using advancements",
                TranslationContext.snapshot().get("item.bone.1.lore.line0"));
        assertEquals(2, TranslationContext.snapshot().size());
    }

    @Test
    void extractsTextDisplayComponentSequencesFromSummonNbt() {
        String command =
                "summon minecraft:text_display ~1 ~2.4 ~1 "
                        + "{alignment:\"center\",line_width:200,Tags:[\"antimatter_reactor_text\","
                        + "\"production_text\",\"new_antimatter_reactor_text\"],"
                        + "text:[{text:\"\",color:\"yellow\"},{text:\"\\n저장: \",color:\"green\"},"
                        + "{text:\"0\",color:\"white\"}],billboard:\"vertical\",shadow:1b,background:0,"
                        + "transformation:{translation:[0f,0f,0f],scale:[0.6f,0.6f,0.6f],"
                        + "left_rotation:[0f,0f,0f,1f],right_rotation:[0f,0f,0f,1f]}}";

        String result = FunctionHandler.processFunction(List.of(command));

        assertNotEquals(command, result, result);
        assertTrue(result.contains("translate"), result);
        assertTrue(
                TranslationContext.snapshot().values().stream()
                        .anyMatch(value -> value.contains("저장:")),
                TranslationContext.snapshot()::toString);
    }

    @Test
    void extractsTellrawAfterAnExecuteScoreCondition() {
        String command =
                "execute unless score #money_ok variables matches 1 run tellraw "
                        + "@a[distance=..8] {\"text\":\"资金不足。研究费用: 100,000,000元\",\"color\":\"red\"}";

        String result = FunctionHandler.processFunction(List.of(command));

        assertNotEquals(command, result, result);
        assertTrue(result.startsWith("execute unless score #money_ok variables matches 1 run tellraw"), result);
        assertTrue(result.contains("translate"), result);
        assertTrue(
                TranslationContext.snapshot().containsValue("资金不足。研究费用: 100,000,000元"),
                TranslationContext.snapshot()::toString);
    }

    @Test
    void foldsScoreBetweenTextComponentsIntoOneParameterizedTranslation() {
        String command =
                "execute unless score #allow cs_tmp matches 1 run title @s actionbar "
                        + "[{text:'작동 가능한 진열대 한도 초과  G키에서 진열대를 구매하세요 (',color:'red'},"
                        + "{score:{name:'@s',objective:'cs_shelfcap'},color:'yellow'},"
                        + "{text:'개)',color:'red'}]";

        String result = FunctionHandler.processFunction(List.of(command));

        assertTrue(
                result.contains(
                        "[{\"translate\":\"datapack.test.function\",\"color\":\"red\","
                                + "\"with\":[{\"score\":{\"name\":\"@s\","
                                + "\"objective\":\"cs_shelfcap\"},\"color\":\"yellow\"}]}]"),
                result);
        assertEquals(
                "작동 가능한 진열대 한도 초과  G키에서 진열대를 구매하세요 (%s개)",
                TranslationContext.snapshot().get("datapack.test.function"),
                TranslationContext.snapshot()::toString);
        assertEquals(1, TranslationContext.snapshot().size(), TranslationContext.snapshot()::toString);
    }

    @Test
    void preservesEveryStyledTextComponentInASequence() {
        String command =
                "title @s actionbar "
                        + "[{text:'第一位守护者正在',color:'#ffffff',shadow_color:-16777216},"
                        + "{text:'沙漠',color:'#e8f807',shadow_color:-16777216},"
                        + "{text:'等待着你。',color:'#ffffff',shadow_color:-16777216}]";

        String result = FunctionHandler.processFunction(List.of(command));

        assertEquals(
                "title @s actionbar "
                        + "[{\"translate\":\"datapack.test.function\",\"color\":\"#ffffff\","
                        + "\"shadow_color\":-16777216},"
                        + "{\"translate\":\"datapack.test.function.1\",\"color\":\"#e8f807\","
                        + "\"shadow_color\":-16777216},"
                        + "{\"translate\":\"datapack.test.function.2\",\"color\":\"#ffffff\","
                        + "\"shadow_color\":-16777216}]",
                result);
        assertEquals(
                Map.of(
                        "datapack.test.function", "第一位守护者正在",
                        "datapack.test.function.1", "沙漠",
                        "datapack.test.function.2", "等待着你。"),
                TranslationContext.snapshot());
    }

    @Test
    void extractsTextOnlyFromDataMergeEntityNbt() {
        String command =
                "data merge entity @e[type=minecraft:text_display,tag=research_text_damage_10,limit=1] "
                        + "{text:{text:\"伤害增加\\n10%\\n完成\",color:\"green\"}}";

        String result = FunctionHandler.processFunction(List.of(command));

        assertNotEquals(command, result, result);
        assertTrue(result.startsWith("data merge entity @e[type=minecraft:text_display"), result);
        assertTrue(result.contains("translate"), result);
        assertTrue(
                TranslationContext.snapshot().containsValue("伤害增加\n10%%\n完成"),
                TranslationContext.snapshot()::toString);
    }

    @Test
    void extractsOnlyTheTextFieldFromFunctionArgumentNbt() {
        String command =
                "function takoyaki:text_log/summon "
                        + "{text:\"おたま\",add:\"-\",coler:\"red\",money:\"125\"}";

        String result = FunctionHandler.processFunction(List.of(command));

        assertNotEquals(command, result, result);
        assertTrue(result.startsWith("function takoyaki:text_log/summon"), result);
        assertTrue(result.contains("translate"), result);
        assertTrue(result.contains("add:\"-\""), result);
        assertTrue(result.contains("coler:\"red\""), result);
        assertTrue(result.contains("money:\"125\""), result);
        assertTrue(TranslationContext.snapshot().containsValue("おたま"), TranslationContext.snapshot()::toString);
        assertEquals(1, TranslationContext.snapshot().size(), TranslationContext.snapshot()::toString);
    }

    private static String mergeContinuationLines(String value) {
        StringBuilder merged = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character != '\\') {
                merged.append(character);
                continue;
            }
            int cursor = index + 1;
            while (cursor < value.length()
                    && (value.charAt(cursor) == ' ' || value.charAt(cursor) == '\t')) {
                cursor++;
            }
            if (cursor >= value.length()
                    || (value.charAt(cursor) != '\r' && value.charAt(cursor) != '\n')) {
                merged.append(character);
                continue;
            }
            if (value.charAt(cursor) == '\r'
                    && cursor + 1 < value.length()
                    && value.charAt(cursor + 1) == '\n') {
                cursor++;
            }
            cursor++;
            while (cursor < value.length()
                    && (value.charAt(cursor) == ' ' || value.charAt(cursor) == '\t')) {
                cursor++;
            }
            index = cursor - 1;
        }
        return merged.toString();
    }

    private static String[] splitLines(String value, String separator) {
        List<String> lines = new ArrayList<>();
        int start = 0;
        int separatorIndex;
        while ((separatorIndex = value.indexOf(separator, start)) >= 0) {
            lines.add(value.substring(start, separatorIndex));
            start = separatorIndex + separator.length();
        }
        lines.add(value.substring(start));
        return lines.toArray(String[]::new);
    }
}
