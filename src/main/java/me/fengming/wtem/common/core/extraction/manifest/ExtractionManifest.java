package me.fengming.wtem.common.core.extraction.manifest;

import java.util.List;

/**
 * Renders the extraction records as a table, written beside the language catalog.
 *
 * <p>CSV is used rather than a log or a JSON file because the audience is a translator deciding how
 * a line should read.
 *
 * <p>The file is a companion to the catalog, not a replacement: the catalog stays exactly the JSON
 * the game loads, and nothing here is read back in.
 * @author FengMing
 */
public final class ExtractionManifest {
    public static final List<String> COLUMNS =
            List.of("key", "text", "source", "location", "subject", "reused");

    private static final String LINE_ENDING = "\r\n";
    private static final char DELIMITER = ',';
    private static final char QUOTE = '"';

    private ExtractionManifest() {}

    /** Derives the manifest file name from the catalog file name. */
    public static String fileName(String languageFile) {
        int extension = languageFile.lastIndexOf('.');
        String base = extension < 0 ? languageFile : languageFile.substring(0, extension);
        return base + ".csv";
    }

    public static String render(List<ExtractionRecord> records) {
        StringBuilder csv = new StringBuilder();
        writeRow(csv, COLUMNS);
        for (ExtractionRecord record : records) {
            writeRow(
                    csv,
                    List.of(
                            record.key(),
                            record.text(),
                            record.origin().source(),
                            record.origin().location(),
                            record.origin().subject(),
                            Boolean.toString(record.reused())));
        }
        return csv.toString();
    }

    private static void writeRow(StringBuilder csv, List<String> values) {
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) csv.append(DELIMITER);
            csv.append(escape(values.get(i)));
        }
        csv.append(LINE_ENDING);
    }

    /**
     * Quotes a field so that it survives a round trip through a spreadsheet.
     *
     * <p>Extracted text is arbitrary: it carries commas, quotes, and section signs, and a sign line
     * or a lore line legitimately contains a newline. Every field is quoted rather than only the ones
     * that need it, because deciding per field makes the output depend on the text and produces diffs
     * that are hard to read across runs.
     */
    private static String escape(String value) {
        StringBuilder escaped = new StringBuilder(value.length() + 2);
        escaped.append(QUOTE);
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            if (character == QUOTE) escaped.append(QUOTE);
            escaped.append(character);
        }
        return escaped.append(QUOTE).toString();
    }
}
