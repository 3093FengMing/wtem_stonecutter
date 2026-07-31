package me.fengming.wtem.common.core.handler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import java.util.Map;
import me.fengming.wtem.common.core.extraction.TranslationContext;
import me.fengming.wtem.common.util.NbtUtils;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class StructureTemplateWHandlerTest {
    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @BeforeEach
    void setUp() {
        TranslationContext.clear();
        TranslationContext.setKeepDuplicates(true);
    }

    @AfterEach
    void tearDown() {
        TranslationContext.release();
    }

    @Test
    void translatesBlockEntitiesAndEntitiesOfATemplate() {
        CompoundTag template = nbt("""
                {"blocks":[{"pos":[0,0,0],"state":0,"nbt":{"id":"minecraft:sign",
                   "front_text":{"messages":["{\\"text\\":\\"Shop\\"}","\\"\\"","\\"\\"","\\"\\""]}}}],
                 "entities":[{"pos":[0.0,0.0,0.0],"blockPos":[0,0,0],
                   "nbt":{"id":"minecraft:villager","CustomName":"{\\"text\\":\\"Trader\\"}"}}]}
                """);

        StructureTemplateWHandler.Result result =
                new StructureTemplateWHandler().process(template);

        assertTrue(result.changed());
        Map<String, String> entries = TranslationContext.snapshot();
        assertTrue(entries.containsValue("Shop"), entries::toString);
        assertTrue(entries.containsValue("Trader"), entries::toString);
    }

    @Test
    void skipsBlocksAndEntitiesWithoutNbt() {
        CompoundTag template = nbt("""
                {"blocks":[{"pos":[0,0,0],"state":0}],
                 "entities":[{"pos":[0.0,0.0,0.0],"blockPos":[0,0,0]}]}
                """);

        StructureTemplateWHandler.Result result =
                new StructureTemplateWHandler().process(template);

        assertFalse(result.changed());
        assertEquals(Map.of(), TranslationContext.snapshot());
    }

    @Test
    void returnsAnEmptyResultForAnAbsentTemplate() {
        StructureTemplateWHandler handler = new StructureTemplateWHandler();

        for (StructureTemplateWHandler.Result result :
                new StructureTemplateWHandler.Result[] {
                    handler.process((CompoundTag) null), handler.process(new CompoundTag())
                }) {
            assertFalse(result.changed());
            assertTrue(result.tag().isEmpty());
        }
        assertEquals(Map.of(), TranslationContext.snapshot());
    }

    @Test
    void namesItselfAfterTheResourceItProcesses() {
        assertEquals("structure", new StructureTemplateWHandler().getName());
    }

    private static CompoundTag nbt(String json) {
        CompoundTag tag = NbtUtils.fromJson(JsonParser.parseString(json).getAsJsonObject());
        assertFalse(tag.isEmpty(), json);
        return tag;
    }
}
