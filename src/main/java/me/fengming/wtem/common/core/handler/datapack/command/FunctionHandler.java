package me.fengming.wtem.common.core.handler.datapack.command;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import me.fengming.wtem.common.core.extraction.TranslationContext;
import me.fengming.wtem.common.core.extraction.service.ExtractionDiagnostics;
import me.fengming.wtem.common.core.handler.datapack.HandlerFactory;
import me.fengming.wtem.common.core.handler.datapack.NonExtraResourceHandler;
import me.fengming.wtem.common.util.ResourceIo;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.IoSupplier;

/**
 * Extracts and rewrites function resources while keeping command parsing and macro mechanics in
 * dedicated helpers.
 *
 * @author FengMing
 */
public class FunctionHandler extends NonExtraResourceHandler {
    public static final HandlerFactory FACTORY = FunctionHandler::new;

    public FunctionHandler(Function<Identifier, Path> filePath, Context context) {
        super("function", filePath, context);
    }

    @Override
    protected String fileExtension() {
        return ".mcfunction";
    }

    @Override
    protected boolean innerHandle(Identifier resource, IoSupplier<InputStream> supplier) {
        String previousFunction = MacroCommandMaterializer.currentFunctionId();
        MacroCommandMaterializer.setCurrentFunctionId(
                resource.getNamespace() + ":" + resource.getPath());
        try {
            FunctionResult result = processFunction(supplier);
            if (result.changed()) ResourceIo.writeString(getFilePath(resource), result.value());
            return result.changed();
        } finally {
            MacroCommandMaterializer.restoreCurrentFunctionId(previousFunction);
        }
    }

    private static FunctionResult processFunction(IoSupplier<InputStream> supplier) {
        String source;
        try (InputStream input = supplier.get()) {
            source = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
        return processSource(source);
    }

    public static String processFunction(String source) {
        return processSource(source).value();
    }

    /** Processes a function with a stable id so call-chain values can be applied in unit tools. */
    public static String processFunction(String source, String functionId) {
        String previousFunction = MacroCommandMaterializer.currentFunctionId();
        MacroCommandMaterializer.setCurrentFunctionId(functionId);
        try {
            return processSource(source).value();
        } finally {
            MacroCommandMaterializer.restoreCurrentFunctionId(previousFunction);
        }
    }

    private static FunctionResult processSource(String source) {
        FunctionSource sourceFile = FunctionSource.parse(source);
        ProcessedLines processed = processLines(sourceFile.lines());
        return processed.changed()
                ? new FunctionResult(sourceFile.render(processed.lines()), true)
                : new FunctionResult(source, false);
    }

    public static String processFunction(List<String> lines) {
        return String.join("\n", processLines(lines).lines());
    }

    private static ProcessedLines processLines(List<String> lines) {
        CommandParseSupport.ParserContext parser = CommandParseSupport.parserContext();
        List<String> modified = new ArrayList<>(lines);
        boolean changed = false;

        for (int i = 0; i < lines.size(); i++) {
            FunctionSource.LogicalCommand logicalLine =
                    FunctionSource.LogicalCommand.read(lines, i);
            i = logicalLine.lastLineIndex();

            String source = logicalLine.value();
            if (source.isEmpty() || source.startsWith("#")) continue;

            MacroArgumentRestorer.CommandLine line =
                    MacroArgumentRestorer.CommandLine.of(source);
            if (line.text().isEmpty()) continue;

            try (var transaction = TranslationContext.beginTransaction()) {
                int recordsBefore = TranslationContext.recordCount();
                CommandExtraction extraction =
                        line.macro()
                                ? MacroCommandMaterializer.extract(parser, line)
                                : CommandParseSupport.extractRegularCommand(parser, line);
                if (!extraction.changed()) {
                    if (TranslationContext.hasOnlyCatalogEntriesSince(recordsBefore)) {
                        transaction.commit();
                    }
                    continue;
                }

                logicalLine.write(modified, extraction.value());
                transaction.commit();
                changed = true;
            }
        }
        return new ProcessedLines(List.copyOf(modified), changed);
    }

    public static void initializeParser(
            RegistryAccess registries, ExtractionDiagnostics diagnostics) {
        CommandParseSupport.initializeParser(registries, diagnostics);
    }

    /** Installs a parser from a lookup provider, useful to callers that only expose a provider. */
    public static void initializeParser(
            HolderLookup.Provider registries, ExtractionDiagnostics diagnostics) {
        CommandParseSupport.initializeParser(registries, diagnostics);
    }

    /** Installs the source-level function call index for one data-pack extraction pass. */
    public static void initializeMacroCallGraph(Map<String, String> functionSources) {
        MacroCommandMaterializer.initializeMacroCallGraph(
                functionSources, CommandParseSupport.parserContext());
    }

    /** Releases the call index after a data pack has finished processing. */
    public static void releaseMacroCallGraph() {
        MacroCommandMaterializer.releaseMacroCallGraph();
    }

    public static void releaseParser() {
        CommandParseSupport.releaseParser();
        releaseMacroCallGraph();
    }

    // These package-level adapters preserve the small parsing API used by MacroCallGraph and its
    // tests while keeping the implementation in CommandParseSupport.
    static MacroCallGraph.ParsedInvocation parseFunctionInvocation(String sourceLine) {
        return CommandParseSupport.parseFunctionInvocation(sourceLine);
    }

    static MacroCallGraph.StorageAssignment parseStorageAssignment(String sourceLine) {
        return CommandParseSupport.parseStorageAssignment(sourceLine);
    }

    private record FunctionResult(String value, boolean changed) {}

    private record ProcessedLines(List<String> lines, boolean changed) {}
}
