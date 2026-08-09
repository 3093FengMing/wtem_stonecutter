package me.fengming.wtem.common.core.handler.datapack.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class CommandJsonSupportTest {
    @Test
    void locatesComponentJsonAfterSelectorArguments() {
        String command =
                "execute as @a[distance=..8] run tellraw @s [{\"text\":\"Hello\"}]";

        var range = CommandJsonSupport.locateComponentJson(command);

        assertTrue(range != null);
        assertEquals("[{\"text\":\"Hello\"}]", command.substring(range.start(), range.end()));
    }

    @Test
    void parsesAStandaloneBareMacroAsATextComponent() {
        var parsed = CommandJsonSupport.parseWithBareComponentMacros("[{\"text\":$(name)}]");

        assertTrue(parsed.isPresent());
        assertEquals("[{\"text\":\"$(name)\"}]", parsed.get().toString());
    }

    @Test
    void recognizesComponentCommandsWithFunctionMacroMarkers() {
        assertTrue(CommandJsonSupport.isComponentCommand("$dialog show @s {}"));
        assertTrue(CommandJsonSupport.isComponentCommand("execute run tellraw @s {}"));
    }
}
