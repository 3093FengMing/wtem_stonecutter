package me.fengming.wtem.common.core.extraction.manifest;

/**
 * Where a piece of extracted text was found, in terms of the world rather than the language key.
 *
 * <p>A translation key describes the shape of the data it came from: {@code container.chest.3.name}
 * says the text named the third chest, but not which chest, and a hashed or random key says nothing
 * at all. A translator who has to decide how a line should read needs the other half of the answer,
 * so this carries it alongside.
 *
 * @param source the extraction stage, such as {@code region} or {@code datapacks}
 * @param location where the data is stored: a dimension and chunk, a data pack and resource, or a
 *     structure file
 * @param subject what carries the text, innermost last, such as {@code minecraft:chest (12, 64, -30)
 *     > minecraft:written_book}
 */
public record ExtractionOrigin(String source, String location, String subject) {
    public static final ExtractionOrigin UNKNOWN = new ExtractionOrigin("", "", "");

    private static final String SEPARATOR = " > ";

    public ExtractionOrigin {
        source = source == null ? "" : source;
        location = location == null ? "" : location;
        subject = subject == null ? "" : subject;
    }

    /** Starts a fresh origin for {@code source}, discarding any location and subject. */
    public static ExtractionOrigin of(String source) {
        return new ExtractionOrigin(source, "", "");
    }

    /** Narrows the location, keeping what an enclosing scope already said. */
    public ExtractionOrigin addLocation(String segment) {
        String combined = append(this.location, segment);
        return combined.equals(this.location)
                ? this
                : new ExtractionOrigin(this.source, combined, this.subject);
    }

    /** Nests a subject inside the one already being visited. */
    public ExtractionOrigin addSubject(String segment) {
        String combined = append(this.subject, segment);
        return combined.equals(this.subject)
                ? this
                : new ExtractionOrigin(this.source, this.location, combined);
    }

    private static String append(String current, String segment) {
        if (segment == null || segment.isBlank()) return current;
        return current.isEmpty() ? segment : current + SEPARATOR + segment;
    }
}
