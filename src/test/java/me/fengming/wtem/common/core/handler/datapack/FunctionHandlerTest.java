package me.fengming.wtem.common.core.handler.datapack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
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

        String[] lines = result.split("\\n", -1);
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

        String[] lines = result.split("\r\n", -1);
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
    void keepsMacroVariablesInsideTranslatableArguments() {
        String command = "$title @a title {\"text\":\"Hello $(name)\"}";

        String result = FunctionHandler.processFunction(List.of(command));

        assertEquals(command, result);
        assertTrue(TranslationContext.snapshot().isEmpty(), TranslationContext.snapshot()::toString);
    }

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

        assertEquals(command, result);
        assertTrue(TranslationContext.snapshot().isEmpty(), TranslationContext.snapshot()::toString);
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
        String command = "execute if score segment animation matches 5 run data modify entity @e[tag=eye_03,limit=1] text set value \"\\uE005\"";
        String result = FunctionHandler.processFunction(List.of(command));

        assertNotEquals(command, result);
    }

    @Test
    void testSimpleDataModify2() {
        String command = "data modify entity @n[tag=station_craft_string] text set value [{\"color\":\"yellow\",\"text\":\"5 Flowers\"}, {\"color\":\"white\",\"text\":\" -> \"}, {\"color\":\"green\",\"text\":\"Jump Boost Potion\"}]";
        String result = FunctionHandler.processFunction(List.of(command));

        assertNotEquals(command, result);
    }

    private static String mergeContinuationLines(String value) {
        return value.replaceAll("\\\\[ \\t]*\\R[ \\t]*", "");
    }
}
