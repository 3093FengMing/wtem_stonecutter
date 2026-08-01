package me.fengming.wtem.common.core.extraction.table;

/**
 * One occurrence of extracted text, with the key it was given and where it was found.
 *
 * <p>The language catalog answers what a key says; this answers where the key came from. They are
 * kept apart because the catalog is a resource-pack file that has to stay exactly what the game
 * expects, and because a key that several occurrences share has one catalog entry but a record for
 * each place it was found.
 *
 * @param key the generated translation key
 * @param text the original text the key replaced
 * @param origin where the text was found
 * @param reused whether the key already existed, so this occurrence added no catalog entry
 */
public record ExtractionRecord(String key, String text, ExtractionOrigin origin, boolean reused) {
    public ExtractionRecord {
        origin = origin == null ? ExtractionOrigin.UNKNOWN : origin;
    }
}
