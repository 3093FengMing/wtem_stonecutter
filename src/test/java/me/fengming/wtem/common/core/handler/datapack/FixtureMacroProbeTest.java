package me.fengming.wtem.common.core.handler.datapack;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import me.fengming.wtem.common.core.extraction.TranslationContext;
import me.fengming.wtem.common.core.extraction.service.ExtractionDiagnostics;
import me.fengming.wtem.common.core.handler.datapack.command.FunctionHandler;
import me.fengming.wtem.common.util.ResourceIds;
import net.minecraft.SharedConstants;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Strict regression coverage for the supplied real-world macro data pack. */
class FixtureMacroProbeTest {
    private static final Path FIXTURE = findFixture();
    private static final Map<String, String> SOURCES = new LinkedHashMap<>();
    private static final ExtractionDiagnostics DIAGNOSTICS = new ExtractionDiagnostics();

    @BeforeAll
    static void bootstrapMinecraft() throws IOException {
        assumeTrue(
                FIXTURE != null,
                "Skipping the real-world macro fixture: tools/testdatpack is not available");
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        SOURCES.putAll(readFunctions());
        FunctionHandler.initializeParser(
                net.minecraft.data.registries.VanillaRegistries.createLookup(), DIAGNOSTICS);
        FunctionHandler.initializeMacroCallGraph(SOURCES);
    }

    @AfterAll
    static void releaseMinecraftHooks() {
        FunctionHandler.releaseParser();
        TranslationContext.release();
    }

    @Test
    void extractsParameterizedLoreFromTheRealFixture() {
        String result = extract("cstore:dev/apply_lore");
        Map<String, String> catalog = TranslationContext.snapshot();

        assertTrue(result.contains("\"with\":[{\"text\":\"$(sale)\"}]"), result);
        assertTrue(result.contains("\"with\":[{\"text\":\"$(cost)\"},{\"text\":\"$(pack)\"}]"), result);
        assertTrue(catalog.containsValue("판매가: %s원"), catalog::toString);
        assertTrue(catalog.containsValue("정가: %s원"), catalog::toString);
        assertTrue(catalog.containsValue("할인: %s%%"), catalog::toString);
        assertTrue(catalog.containsValue("발주: %s원 / %s개"), catalog::toString);
        assertTrue(catalog.values().stream().noneMatch(value -> value.contains("$(")), catalog::toString);
        assertFalse(result.contains("\"color\":\"1\""), result);
    }

    @Test
    void resolvesCallerStylesAndPreservesOnlyTextMacrosInActivityDialog() {
        String result = extract("cstore:ui/activity_idle_dynamic");
        Map<String, String> catalog = TranslationContext.snapshot();

        assertTrue(result.contains("\"with\":[{\"text\":\"$(event_name)\"}]"), result);
        assertTrue(result.contains("\"with\":[{\"text\":\"$(reqwait)\"}]"), result);
        assertFalse(result.contains("\"color\":\"$("), result);
        assertFalse(result.contains("\"shadow_color\":\"$("), result);
        assertFalse(result.contains("\"color\":\"1\""), result);
        assertFalse(result.contains("\"shadow_color\":[1"), result);
        assertTrue(catalog.values().stream().noneMatch(value -> value.contains("$(")), catalog::toString);
    }

    @Test
    void extractsDescriptionContentsAndActionlessButtonsFromRequestDialog() {
        String result = extract("cstore:ui/request_idle_dynamic");
        Map<String, String> catalog = TranslationContext.snapshot();

        assertTrue(result.contains("\"translate\""), result);
        assertTrue(result.contains("$(reqx)"), result);
        assertTrue(catalog.values().stream().anyMatch(value -> value.contains("%s %s %s")), catalog::toString);
        assertTrue(catalog.values().stream().anyMatch(value -> value.contains("%s초")), catalog::toString);
        assertTrue(catalog.values().stream().anyMatch(value -> value.contains("请求") || value.contains("손님 요청")), catalog::toString);
        assertTrue(catalog.values().stream().noneMatch(value -> value.contains("$(")), catalog::toString);
    }

    @Test
    void foldsStructuredArgumentsInThePairRequestDialog() {
        String result = extract("cstore:ui/request_pair_dynamic");
        Map<String, String> catalog = TranslationContext.snapshot();

        assertTrue(result.contains("$(req1_name)"), result);
        assertTrue(result.contains("$(reqc1)"), result);
        assertTrue(result.contains("$(reqreward)"), result);
        assertTrue(catalog.values().stream().anyMatch(value -> value.contains("%s/%s")), catalog::toString);
        assertTrue(catalog.values().stream().anyMatch(value -> value.contains("%s원")), catalog::toString);
        assertTrue(catalog.values().stream().noneMatch(value -> value.contains("$(")), catalog::toString);
    }

    @Test
    void keepsTheMainDialogJsonParseableWhenStorageMacrosShareOneComponent() {
        String result = extract("cstore:ui/main_dynamic");

        assertTrue(result.contains("\"translate\""), result);
        assertFalse(result.contains("{translate:"), result);
        assertFalse(result.contains("\"color\":\"{translate:"), result);
        int jsonStart = result.indexOf('{', result.indexOf("dialog show"));
        assertTrue(jsonStart >= 0, result);
        JsonParser.parseString(result.substring(jsonStart));
    }

    @Test
    void keepsTheMainDialogStorageInputsAsQuotedScalars() {
        String function = "cstore:ui/show_main";
        TranslationContext.clear();
        TranslationContext.setKey("fixture." + function.replace(':', '.'));
        String result = FunctionHandler.processFunction(SOURCES.get(function), function);

        assertTrue(result.contains("main_req set value \""), result);
        assertTrue(result.contains("main_req_color set value \""), result);
        assertFalse(result.contains("main_req set value {"), result);
        assertFalse(result.contains("main_req_color set value {"), result);
    }

    @Test
    void auditsEveryFunctionInTheRealFixture() {
        assumeTrue(isTargetFixtureVersion(), "The supplied data pack targets Minecraft 26.2");

        List<String> problems = new ArrayList<>();
        int diagnosticsBefore = DIAGNOSTICS.failures().size();
        for (Map.Entry<String, String> entry : SOURCES.entrySet()) {
            String function = entry.getKey();
            TranslationContext.clear();
            TranslationContext.setKey("fixture." + function.replace(':', '.'));

            String result = FunctionHandler.processFunction(entry.getValue(), function);
            if (hasEmbeddedComponentObject(result)) {
                problems.add(function + ": embedded a component object into a quoted scalar");
            }
            if (TranslationContext.snapshot().values().stream()
                    .anyMatch(value -> value.contains("$("))) {
                problems.add(function + ": emitted an unresolved macro into the catalog");
            }

            TranslationContext.clear();
            TranslationContext.setKey("fixture." + function.replace(':', '.'));
            String repeated = FunctionHandler.processFunction(result, function);
            if (!result.equals(repeated)) {
                problems.add(function + ": extraction is not idempotent");
            }
        }

        addUnexpectedDiagnostics(problems, diagnosticsBefore);

        assertTrue(problems.isEmpty(), () -> String.join("\n", problems));
    }

    @Test
    void auditsEveryJsonResourceInTheRealFixture(@TempDir Path output) throws IOException {
        assumeTrue(isTargetFixtureVersion(), "The supplied data pack targets Minecraft 26.2");

        ResourceHandler.Context context = ResourceHandler.Context.of(null, null, null, null);
        Map<String, ResourceHandler> handlers = new LinkedHashMap<>();
        for (HandlerFactory factory : DefaultResourceHandlers.create()) {
            ResourceHandler handler =
                    factory.newHandler(
                            id -> output.resolve(id.getNamespace()).resolve(id.getPath()), context);
            handlers.put(handler.getPath(), handler);
        }

        Path data = FIXTURE.resolve("data");
        List<String> problems = new ArrayList<>();
        int handled = 0;
        int diagnosticsBefore = DIAGNOSTICS.failures().size();
        try {
            JsonParser.parseString(
                    Files.readString(FIXTURE.resolve("pack.mcmeta"), StandardCharsets.UTF_8));
        } catch (RuntimeException | IOException exception) {
            problems.add("pack.mcmeta: " + exception);
        }
        try (var paths = Files.walk(data)) {
            for (Path path :
                    paths.filter(file -> file.toString().endsWith(".json")).sorted().toList()) {
                Path relative = data.relativize(path);
                String label = relative.toString().replace('\\', '/');
                try {
                    JsonElement source =
                            JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8));
                    if (!source.isJsonObject() && !source.isJsonArray()) {
                        problems.add(label + ": resource root is neither an object nor an array");
                        continue;
                    }

                    String directory = relative.getName(1).toString();
                    ResourceHandler handler = handlers.get(directory);
                    if (handler == null) continue;
                    handled++;

                    String resourcePath =
                            relative
                                    .subpath(1, relative.getNameCount())
                                    .toString()
                                    .replace('\\', '/');
                    Identifier id =
                            ResourceIds.create(relative.getName(0).toString(), resourcePath);
                    TranslationContext.clear();
                    TranslationContext.setKey(
                            "fixture." + id.toString().replace(':', '.').replace('/', '.'));
                    boolean changed =
                            handler.innerHandle(id, () -> Files.newInputStream(path));
                    Path resultPath = output.resolve(id.getNamespace()).resolve(id.getPath());
                    String result =
                            changed
                                    ? Files.readString(resultPath, StandardCharsets.UTF_8)
                                    : Files.readString(path, StandardCharsets.UTF_8);
                    JsonParser.parseString(result);

                    if (hasEmbeddedComponentObject(result)) {
                        problems.add(label + ": embedded a component object into a quoted scalar");
                    }
                    if (TranslationContext.snapshot().values().stream()
                            .anyMatch(value -> value.contains("$("))) {
                        problems.add(label + ": emitted an unresolved macro into the catalog");
                    }
                } catch (RuntimeException | IOException exception) {
                    problems.add(label + ": " + exception);
                }
            }
        }

        if (handled != 47) {
            problems.add("expected 47 dialog/item-modifier resources, handled " + handled);
        }
        addUnexpectedDiagnostics(problems, diagnosticsBefore);
        assertTrue(problems.isEmpty(), () -> String.join("\n", problems));
    }

    @Test
    void doesNotReportParserOrMaskRestorationFailuresForTheFixture() {
        // Run the representative resources first so this assertion covers the same path as the
        // extraction tests.
        extract("cstore:dev/apply_lore");
        extract("cstore:ui/activity_idle_dynamic");
        extract("cstore:ui/request_idle_dynamic");
        extract("cstore:ui/request_pair_dynamic");

        assertTrue(
                DIAGNOSTICS.failures().stream()
                        .noneMatch(
                                failure ->
                                        !"function_selector_name".equals(failure.scope())
                                                && !"function_storage_string".equals(
                                                        failure.scope())),
                () -> DIAGNOSTICS.failures().toString());
    }

    private static String extract(String function) {
        String source = SOURCES.get(function);
        assertTrue(source != null && source.contains("$("), function);
        TranslationContext.clear();
        TranslationContext.setKey("fixture." + function.replace(':', '.'));
        return FunctionHandler.processFunction(source, function);
    }

    private static Map<String, String> readFunctions() throws IOException {
        Map<String, String> result = new LinkedHashMap<>();
        try (var paths = Files.walk(FIXTURE.resolve("data"))) {
            paths.filter(path -> path.toString().endsWith(".mcfunction"))
                    .sorted()
                    .forEach(
                            path -> {
                                String normalized = path.toString().replace('\\', '/');
                                int marker = normalized.indexOf("/data/");
                                String relative = normalized.substring(marker + "/data/".length());
                                int function = relative.indexOf("/function/");
                                String id =
                                        relative.substring(0, function).replace('/', ':')
                                                + ":"
                                                + relative.substring(function + "/function/".length());
                                id = id.substring(0, id.length() - ".mcfunction".length());
                                try {
                                    result.put(id, Files.readString(path, StandardCharsets.UTF_8));
                                } catch (IOException exception) {
                                    throw new IllegalStateException(exception);
                                }
                            });
        }
        return result;
    }

    private static Path findFixture() {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (current != null) {
            Path candidate =
                    current.resolve(
                            "tools/testdatpack/convenience_store_tycoon_v7.5.6_26.2_auto_cash_help_fix");
            if (Files.isDirectory(candidate)) return candidate;
            current = current.getParent();
        }
        return null;
    }

    private static boolean isTargetFixtureVersion() {
        Object version = SharedConstants.getCurrentVersion();
        for (String methodName : List.of("name", "getName")) {
            try {
                Object value = version.getClass().getMethod(methodName).invoke(version);
                if (value instanceof String name) return name.startsWith("26.2");
            } catch (ReflectiveOperationException ignored) {
                // Try the accessor used by the other mapping generation.
            }
        }
        return false;
    }

    private static boolean isUnboundFixtureDialogReference(
            ExtractionDiagnostics.Failure failure) {
        if (!"function_parse".equals(failure.scope())) return false;
        String resource = failure.resource();
        int marker = resource.indexOf("dialog show");
        if (marker < 0) return false;
        int cursor = marker + "dialog show".length();
        cursor = skipWhitespace(resource, cursor);
        int targetStart = cursor;
        while (cursor < resource.length() && !Character.isWhitespace(resource.charAt(cursor))) cursor++;
        if (cursor == targetStart) return false;
        cursor = skipWhitespace(resource, cursor);
        if (!resource.startsWith("cstore:", cursor)) return false;
        cursor += "cstore:".length();
        int pathStart = cursor;
        while (cursor < resource.length() && isResourcePathCharacter(resource.charAt(cursor))) cursor++;
        return cursor > pathStart && skipWhitespace(resource, cursor) == resource.length();
    }

    private static int skipWhitespace(String value, int start) {
        int cursor = start;
        while (cursor < value.length() && Character.isWhitespace(value.charAt(cursor))) cursor++;
        return cursor;
    }

    private static boolean isResourcePathCharacter(char character) {
        return (character >= 'a' && character <= 'z')
                || (character >= '0' && character <= '9')
                || character == '_'
                || character == '.'
                || character == '/'
                || character == '-';
    }

    private static boolean hasEmbeddedComponentObject(String source) {
        return source.contains("\"text\":\"{translate:")
                || source.contains("\"color\":\"{translate:")
                || source.contains("\"with\":[{\"text\":\"{translate:");
    }

    private static void addUnexpectedDiagnostics(List<String> problems, int diagnosticsBefore) {
        DIAGNOSTICS.failures().stream()
                .skip(diagnosticsBefore)
                .filter(failure -> !isExpectedFixtureDiagnostic(failure))
                .limit(40)
                .forEach(
                        failure ->
                                problems.add(
                                        failure.scope()
                                                + " at "
                                                + failure.resource()
                                                + ": "
                                                + failure.displayMessage()));
    }

    private static boolean isExpectedFixtureDiagnostic(
            ExtractionDiagnostics.Failure failure) {
        return "function_selector_name".equals(failure.scope())
                || "function_storage_string".equals(failure.scope())
                || isUnboundFixtureDialogReference(failure);
    }
}
