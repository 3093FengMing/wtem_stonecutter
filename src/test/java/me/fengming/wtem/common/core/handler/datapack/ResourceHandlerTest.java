package me.fengming.wtem.common.core.handler.datapack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import me.fengming.wtem.common.core.extraction.ExtractionDiagnostics;
import me.fengming.wtem.common.core.extraction.TranslationContext;
import me.fengming.wtem.common.core.extraction.service.ExtractionSession;
import me.fengming.wtem.common.util.ResourceIds;
import net.minecraft.SharedConstants;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.packs.resources.IoSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Covers the key naming, transaction, and failure reporting shared by every data-pack handler. */
class ResourceHandlerTest {
    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @BeforeEach
    void setUp() {
        TranslationContext.clear();
    }

    @AfterEach
    void tearDown() {
        TranslationContext.release();
    }

    @Test
    void derivesTheKeyFromTheNamespaceAndTheDirectoryPath() {
        RecordingHandler handler = new RecordingHandler(true);

        assertTrue(handler.handle(id("example", "quests/chapter1/intro.json"), source()));

        // The extension is dropped and directory separators become key segments, so the key can be
        // addressed from a resource-pack language file.
        assertEquals("datapack.example.quests.chapter1.intro", handler.observedKey);
    }

    @Test
    void keepsThePathOfAResourceWithoutAnExtension() {
        RecordingHandler handler = new RecordingHandler(true);

        handler.handle(id("example", "intro"), source());

        assertEquals("datapack.example.intro", handler.observedKey);
    }

    @Test
    void rollsBackEntriesWhenNothingChanged() {
        ResourceHandler handler =
                new RecordingHandler(false) {
                    @Override
                    protected boolean innerHandle(Identifier rl, IoSupplier<InputStream> supplier) {
                        super.innerHandle(rl, supplier);
                        TranslationContext.addEntry("Speculative");
                        return false;
                    }
                };

        assertFalse(handler.handle(id("example", "intro.json"), source()));

        // A handler that reads text but decides not to rewrite the resource must not leave orphan
        // entries in the catalog pointing at text nobody translated.
        assertEquals(Map.of(), TranslationContext.snapshot());
    }

    @Test
    void keepsEntriesWhenTheResourceChanged() {
        ResourceHandler handler =
                new RecordingHandler(true) {
                    @Override
                    protected boolean innerHandle(Identifier rl, IoSupplier<InputStream> supplier) {
                        super.innerHandle(rl, supplier);
                        TranslationContext.addEntry("Kept");
                        return true;
                    }
                };

        assertTrue(handler.handle(id("example", "intro.json"), source()));

        assertEquals(Map.of("datapack.example.intro", "Kept"), TranslationContext.snapshot());
    }

    @Test
    void reportsAFailureWithoutAbortingTheRun() {
        ExtractionSession session = new ExtractionSession();
        RuntimeException failure = new IllegalStateException("broken");
        ResourceHandler handler =
                new FailingHandler(failure, ResourceHandler.Context.of(null, null, null, session));

        assertFalse(handler.handle(id("example", "intro.json"), source()));

        assertTrue(session.diagnostics().hasFailures());
        ExtractionDiagnostics.Failure recorded = session.diagnostics().failures().getFirst();
        assertEquals("datapack", recorded.scope());
        assertEquals("example:intro.json", recorded.resource());
        assertEquals(failure, recorded.cause());
        assertEquals(Map.of(), TranslationContext.snapshot());
    }

    @Test
    void survivesAFailureWithoutASession() {
        // Handlers are also used outside a tracked run, where there is nowhere to record a failure.
        ResourceHandler handler =
                new FailingHandler(
                        new IllegalStateException("broken"),
                        ResourceHandler.Context.of(null, null, null, null));

        assertFalse(handler.handle(id("example", "intro.json"), source()));
    }

    @Test
    void acceptsOnlyResourcesMatchingTheFileExtension() {
        RecordingHandler json = new RecordingHandler(true);

        assertTrue(json.accepts(id("example", "intro.json")));
        assertFalse(json.accepts(id("example", "intro.mcfunction")));
        assertFalse(json.accepts(id("example", "intro")));
    }

    @Test
    void exposesTheDirectoryAndTheOutputPath() {
        Path target = Path.of("out", "intro.json");
        RecordingHandler handler = new RecordingHandler(true, rl -> target);

        assertEquals("test", handler.getPath());
        assertEquals(target, handler.getFilePath(id("example", "intro.json")));
    }

    @Test
    void narrowsTargetsWithoutLosingTheRestOfTheContext() {
        ExtractionSession session = new ExtractionSession();
        ResourceHandler.Context context =
                ResourceHandler.Context.of(List.of("title"), null, null, session);

        ResourceHandler.Context narrowed = context.withTargets(List.of("description"));

        assertEquals(List.of("description"), narrowed.targetPaths());
        assertEquals(session, narrowed.session());
        assertEquals(session.diagnostics(), narrowed.diagnostics());
    }

    private static Identifier id(String namespace, String path) {
        return ResourceIds.create(namespace, path);
    }

    private static IoSupplier<InputStream> source() {
        return () -> new ByteArrayInputStream("{}".getBytes(StandardCharsets.UTF_8));
    }

    private static class RecordingHandler extends ResourceHandler {
        private final boolean changed;
        private String observedKey;

        RecordingHandler(boolean changed) {
            this(changed, rl -> Path.of(rl.getPath()));
        }

        RecordingHandler(boolean changed, Function<Identifier, Path> filePath) {
            super("test", filePath, Context.of(null, null, null, null));
            this.changed = changed;
        }

        @Override
        protected boolean innerHandle(Identifier rl, IoSupplier<InputStream> supplier) {
            this.observedKey = TranslationContext.getKey();
            return this.changed;
        }
    }

    private static final class FailingHandler extends ResourceHandler {
        private final RuntimeException failure;

        FailingHandler(RuntimeException failure, Context context) {
            super("test", rl -> Path.of(rl.getPath()), context);
            this.failure = failure;
        }

        @Override
        protected boolean innerHandle(Identifier rl, IoSupplier<InputStream> supplier) {
            TranslationContext.addEntry("Discarded");
            throw this.failure;
        }
    }
}
