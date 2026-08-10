package me.fengming.wtem.common.core.extraction.pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class DataPathTest {
    @Test
    void parsesObjectAndListSelectorsWithoutRegex() {
        DataPath path = DataPath.parse("body[*].contents");

        assertTrue(
                path.matches(
                        List.of(
                                DataPath.keyLocation("body"),
                                DataPath.indexLocation(3),
                                DataPath.keyLocation("contents"))));
        assertFalse(
                path.matches(
                        List.of(
                                DataPath.keyLocation("body"),
                                DataPath.indexLocation(3),
                                DataPath.keyLocation("description"))));
    }

    @Test
    void supportsEscapedObjectKeysAndExactIndexes() {
        DataPath path = DataPath.parse("payload.a\\.b[1]");

        assertTrue(
                path.matches(
                        List.of(
                                DataPath.keyLocation("payload"),
                                DataPath.keyLocation("a.b"),
                                DataPath.indexLocation(1))));
        assertFalse(
                path.matches(
                        List.of(
                                DataPath.keyLocation("payload"),
                                DataPath.keyLocation("a.b"),
                                DataPath.indexLocation(0))));
    }

    @Test
    void treatsAnEscapedStarAsAnOrdinaryObjectKey() {
        DataPath path = DataPath.parse("payload.\\*");

        assertTrue(path.matches(List.of(DataPath.keyLocation("payload"), DataPath.keyLocation("*"))));
        assertFalse(path.matches(List.of(DataPath.keyLocation("payload"), DataPath.keyLocation("name"))));
    }

    @Test
    void rejectsMalformedSelectors() {
        for (String value : List.of("", "body.", "body[abc]", "body[0]tail", "body\\")) {
            org.junit.jupiter.api.Assertions.assertThrows(
                    IllegalArgumentException.class, () -> DataPath.parse(value));
        }
    }
}
