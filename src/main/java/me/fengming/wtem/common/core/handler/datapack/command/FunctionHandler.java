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
import me.fengming.wtem.common.core.extraction.manifest.ExtractionOrigin;
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
    private static final ThreadLocal<CommandLocation> CURRENT_COMMAND = new ThreadLocal<>();

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

    /**
     * Processes a command embedded in a component event.
     *
     * <p>Commands inside dialog click actions are strings, so they do not carry the leading
     * {@code $} that marks a function macro line even when they contain {@code $(name)}. Adding a
     * temporary marker lets the same caller-binding materialization and macro restoration path run
     * for the embedded command; the marker is removed before the command is written back into the
     * event object.
     */
    public static String processNestedCommand(String source) {
        if (source == null || source.isBlank()) return source;
        boolean hasMacros = !MacroCommandMaterializer.macroNames(source).isEmpty();
        boolean addedMarker = hasMacros && !source.startsWith("$");
        String command = addedMarker ? "$" + source : source;
        String translated = processFunction(command);
        return addedMarker && translated.startsWith("$")
                ? translated.substring(1)
                : translated;
    }

    /** Adds the current function file and physical line range to parser diagnostics. */
    static String diagnosticLocation(String detail) {
        ExtractionOrigin origin = TranslationContext.getOrigin();
        StringBuilder location = new StringBuilder();
        appendLocationPart(location, origin.source());
        appendLocationPart(location, origin.location());
        appendLocationPart(location, origin.subject());

        CommandLocation command = CURRENT_COMMAND.get();
        if (command != null) {
            String file = functionFile(command.functionId());
            if (!file.isBlank() && location.indexOf(file) < 0) appendLocationPart(location, file);
            appendLocationPart(location, command.lineLabel());
        }
        String key = TranslationContext.getKey();
        if (!key.isBlank()) {
            appendLocationPart(location, "key " + key);
        }
        String commandDetail = compactDetail(detail);
        if (!commandDetail.isBlank()) appendLocationPart(location, "command " + commandDetail);
        return location.isEmpty() ? "unknown" : location.toString();
    }

    private static void appendLocationPart(StringBuilder location, String part) {
        if (part == null || part.isBlank()) return;
        if (!location.isEmpty()) location.append(" > ");
        location.append(part);
    }

    private static String functionFile(String functionId) {
        if (functionId == null || functionId.isBlank()) return "";
        int separator = functionId.indexOf(':');
        if (separator <= 0 || separator + 1 >= functionId.length()) return functionId;
        String namespace = functionId.substring(0, separator);
        String path = functionId.substring(separator + 1);
        if (!path.startsWith("function/")) path = "function/" + path;
        if (!path.endsWith(".mcfunction")) path += ".mcfunction";
        return namespace + ":" + path;
    }

    private static String compactDetail(String detail) {
        if (detail == null || detail.isBlank()) return "";
        StringBuilder compactBuilder = new StringBuilder(detail.length());
        boolean pendingSpace = false;
        for (int i = 0; i < detail.length(); i++) {
            char character = detail.charAt(i);
            if (Character.isWhitespace(character)) {
                pendingSpace = !compactBuilder.isEmpty();
                continue;
            }
            if (pendingSpace) compactBuilder.append(' ');
            compactBuilder.append(character);
            pendingSpace = false;
        }
        String compact = compactBuilder.toString();
        return compact.length() <= 240 ? compact : compact.substring(0, 237) + "...";
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
            int firstLineIndex = i;
            FunctionSource.LogicalCommand logicalLine =
                    FunctionSource.LogicalCommand.read(lines, i);
            i = logicalLine.lastLineIndex();

            String source = logicalLine.value();
            if (source.isEmpty() || source.startsWith("#")) continue;

            MacroArgumentRestorer.CommandLine line =
                    MacroArgumentRestorer.CommandLine.of(source);
            if (line.text().isEmpty()) continue;

            try (var commandLocation =
                            pushCommandLocation(
                                    MacroCommandMaterializer.currentFunctionId(),
                                    firstLineIndex,
                                    logicalLine.lastLineIndex(),
                                    source);
                    var transaction = TranslationContext.beginTransaction()) {
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

    private static CommandLocationScope pushCommandLocation(
            String functionId, int firstLineIndex, int lastLineIndex, String source) {
        CommandLocation previous = CURRENT_COMMAND.get();
        CURRENT_COMMAND.set(
                new CommandLocation(functionId, firstLineIndex, lastLineIndex, source));
        return new CommandLocationScope(previous);
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

    private record CommandLocation(
            String functionId, int firstLineIndex, int lastLineIndex, String source) {
        String lineLabel() {
            int first = this.firstLineIndex + 1;
            int last = this.lastLineIndex + 1;
            return first == last ? "line " + first : "lines " + first + "-" + last;
        }
    }

    private static final class CommandLocationScope implements AutoCloseable {
        private final CommandLocation previous;

        private CommandLocationScope(CommandLocation previous) {
            this.previous = previous;
        }

        @Override
        public void close() {
            if (this.previous == null) CURRENT_COMMAND.remove();
            else CURRENT_COMMAND.set(this.previous);
        }
    }
}
