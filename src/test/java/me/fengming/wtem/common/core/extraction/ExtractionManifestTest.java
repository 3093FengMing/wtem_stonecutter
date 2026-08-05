package me.fengming.wtem.common.core.extraction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import me.fengming.wtem.common.core.extraction.manifest.ExtractionManifest;
import me.fengming.wtem.common.core.extraction.manifest.ExtractionOrigin;
import me.fengming.wtem.common.core.extraction.manifest.ExtractionRecord;
import org.junit.jupiter.api.Test;

/**
 * Boundary coverage for the CSV extraction report.
 *
 * <p>The report is read by a spreadsheet rather than by the game, so the cases that matter are the
 * ones a spreadsheet gets wrong: text carrying the delimiter, the quote character, or a line break.
 */
class ExtractionManifestTest {
    private static final String HEADER =
            "\"key\",\"text\",\"source\",\"location\",\"subject\",\"reused\",\"replaced\"\r\n";

    @Test
    void writesAHeaderRowEvenWithNothingToReport() {
        assertEquals(HEADER, ExtractionManifest.render(List.of()));
    }

    @Test
    void writesOneRowPerRecordInOrder() {
        String csv =
                ExtractionManifest.render(
                        List.of(
                                record("container.chest.1.name", "Loot", false),
                                record("item.stick.1.name", "Wand", true)));

        assertEquals(
                HEADER
                        + "\"container.chest.1.name\",\"Loot\",\"region\","
                        + "\"minecraft:overworld chunk [1, 2]\",\"minecraft:chest\",\"false\",\"true\"\r\n"
                        + "\"item.stick.1.name\",\"Wand\",\"region\","
                        + "\"minecraft:overworld chunk [1, 2]\",\"minecraft:chest\",\"true\",\"true\"\r\n",
                csv);
    }

    @Test
    void keepsTextThatWouldOtherwiseSplitARow() {
        // A sign line or a lore line legitimately contains all three of these, so each has to stay
        // inside its own field.
        String csv =
                ExtractionManifest.render(
                        List.of(
                                new ExtractionRecord(
                                        "sign.1.front_text.0",
                                        "Say \"hi\", then\nleave",
                                        ExtractionOrigin.UNKNOWN,
                                        false)));

        assertEquals(
                HEADER
                        + "\"sign.1.front_text.0\",\"Say \"\"hi\"\", then\nleave\",\"\",\"\",\"\","
                        + "\"false\",\"true\"\r\n",
                csv);
    }

    @Test
    void quotesEveryFieldSoRowsStayComparableAcrossRuns() {
        // Nothing here needs quoting, and quoting it anyway is what keeps a row from changing shape
        // when a translator edits the text it carries.
        String csv =
                ExtractionManifest.render(
                        List.of(
                                new ExtractionRecord(
                                        "block.command_block",
                                        "Welcome",
                                        ExtractionOrigin.of("region"),
                                        false)));

        assertEquals(
                HEADER + "\"block.command_block\",\"Welcome\",\"region\",\"\",\"\",\"false\",\"true\"\r\n",
                csv);
    }

    @Test
    void marksCatalogOnlyTextAsNotReplaced() {
        String csv =
                ExtractionManifest.render(
                        List.of(
                                new ExtractionRecord(
                                        "writable_book.1.content.page0",
                                        "Notes",
                                        ExtractionOrigin.of("region"),
                                        false,
                                        false)));

        assertEquals(
                HEADER
                        + "\"writable_book.1.content.page0\",\"Notes\",\"region\",\"\",\"\","
                        + "\"false\",\"false\"\r\n",
                csv);
    }

    @Test
    void endsEveryRowWithTheLineEndingTheCsvGrammarSpecifies() {
        String csv = ExtractionManifest.render(List.of(record("a.b", "c", false)));

        assertTrue(csv.endsWith("\r\n"), csv);
        assertEquals(2, csv.split("\r\n").length, csv);
        // A lone LF anywhere else would be text carried by a field, and there is none here.
        assertEquals(csv.replace("\r\n", ""), csv.replace("\r", "").replace("\n", ""));
    }

    @Test
    void namesTheReportAfterTheCatalogItAccompanies() {
        assertEquals("zh_cn.csv", ExtractionManifest.fileName("zh_cn.json"));
        assertEquals("lang/zh_cn.csv", ExtractionManifest.fileName("lang/zh_cn.json"));
        // A name without an extension still gets one rather than losing its last path segment.
        assertEquals("zh_cn.csv", ExtractionManifest.fileName("zh_cn"));
    }

    private static ExtractionRecord record(String key, String text, boolean reused) {
        ExtractionOrigin origin =
                ExtractionOrigin.of("region")
                        .addLocation("minecraft:overworld chunk [1, 2]")
                        .addSubject("minecraft:chest");
        return new ExtractionRecord(key, text, origin, reused);
    }
}
