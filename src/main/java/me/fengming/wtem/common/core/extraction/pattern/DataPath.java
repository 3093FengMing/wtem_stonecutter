package me.fengming.wtem.common.core.extraction.pattern;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * A small, schema-neutral path used by user extraction rules.
 *
 * <p>The syntax deliberately describes the tree rather than the serialized text.  Object keys
 * are separated with dots and list selectors use {@code [*]} or a numeric index, for example
 * {@code body[*].contents} and {@code entries[0].name}.  A backslash escapes the next character in
 * an object key, which makes keys containing dots addressable without introducing a second parser.
 * This class never uses a regular expression; callers decide how a parsed JSON/NBT tree is walked.
 *
 * @author FengMing
 */
public final class DataPath {
    private final String source;
    private final List<Segment> segments;

    private DataPath(String source, List<Segment> segments) {
        this.source = source;
        this.segments = List.copyOf(segments);
    }

    public static DataPath parse(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Path must not be blank");
        }

        String source = value.trim();
        List<Segment> result = new ArrayList<>();
        StringBuilder key = new StringBuilder();
        boolean escaped = false;
        boolean escapedWildcard = false;
        boolean afterIndex = false;
        for (int index = 0; index < source.length(); index++) {
            char character = source.charAt(index);
            if (escaped) {
                key.append(character);
                escapedWildcard |= character == '*';
                escaped = false;
                continue;
            }
            if (character == '\\') {
                escaped = true;
                continue;
            }
            if (character == '.') {
                if (key.isEmpty() && !afterIndex) {
                    throw invalid(source);
                }
                if (!key.isEmpty()) result.add(Segment.key(key.toString(), !escapedWildcard));
                key.setLength(0);
                escapedWildcard = false;
                afterIndex = false;
                continue;
            }
            if (character == '[') {
                if (!key.isEmpty()) {
                    result.add(Segment.key(key.toString(), !escapedWildcard));
                    key.setLength(0);
                    escapedWildcard = false;
                } else if (!afterIndex && !result.isEmpty()) {
                    throw invalid(source);
                }
                int end = source.indexOf(']', index + 1);
                if (end < 0) throw invalid(source);
                String selector = source.substring(index + 1, end).trim();
                if ("*".equals(selector)) {
                    result.add(Segment.indexWildcard());
                } else {
                    int parsed;
                    try {
                        parsed = Integer.parseInt(selector);
                    } catch (NumberFormatException exception) {
                        throw invalid(source);
                    }
                    if (parsed < 0) throw invalid(source);
                    result.add(Segment.index(parsed));
                }
                index = end;
                afterIndex = true;
                continue;
            }
            if (afterIndex) {
                throw invalid(source);
            }
            key.append(character);
        }
        if (escaped) throw invalid(source);
        if (!key.isEmpty()) result.add(Segment.key(key.toString(), !escapedWildcard));
        if (result.isEmpty() || source.endsWith(".")) throw invalid(source);
        return new DataPath(source, result);
    }

    private static IllegalArgumentException invalid(String source) {
        return new IllegalArgumentException("Invalid data path: " + source);
    }

    public String source() {
        return this.source;
    }

    public List<Segment> segments() {
        return this.segments;
    }

    public boolean matches(List<Location> location) {
        if (location == null || location.size() != this.segments.size()) return false;
        for (int index = 0; index < this.segments.size(); index++) {
            if (!this.segments.get(index).matches(location.get(index))) return false;
        }
        return true;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof DataPath path && this.source.equals(path.source);
    }

    @Override
    public int hashCode() {
        return this.source.hashCode();
    }

    @Override
    public String toString() {
        return this.source;
    }

    public static Location keyLocation(String name) {
        return Location.key(name);
    }

    public static Location indexLocation(int index) {
        return Location.index(index);
    }

    public sealed interface Segment permits KeySegment, IndexSegment {
        boolean matches(Location location);

        static Segment key(String name, boolean wildcardAllowed) {
            return new KeySegment(name, wildcardAllowed && "*".equals(name));
        }

        static Segment index(int index) {
            return new IndexSegment(index, false);
        }

        static Segment indexWildcard() {
            return new IndexSegment(-1, true);
        }
    }

    public record KeySegment(String name, boolean wildcard) implements Segment {
        @Override
        public boolean matches(Location location) {
            return location != null
                    && location.kind() == LocationKind.KEY
                    && (this.wildcard || Objects.equals(this.name, location.name()));
        }
    }

    public record IndexSegment(int index, boolean wildcard) implements Segment {
        @Override
        public boolean matches(Location location) {
            return location != null
                    && location.kind() == LocationKind.INDEX
                    && (this.wildcard || this.index == location.index());
        }
    }

    public enum LocationKind {
        KEY,
        INDEX
    }

    public record Location(LocationKind kind, String name, int index) {
        private static Location key(String name) {
            return new Location(LocationKind.KEY, name, -1);
        }

        private static Location index(int index) {
            return new Location(LocationKind.INDEX, null, index);
        }
    }
}
