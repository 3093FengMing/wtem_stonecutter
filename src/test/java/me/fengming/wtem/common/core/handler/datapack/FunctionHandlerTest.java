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

    private static String mergeContinuationLines(String value) {
        return value.replaceAll("\\\\[ \\t]*\\R[ \\t]*", "");
    }
}
