package me.fengming.wtem.common.core.handler.datapack.command;

import java.util.ArrayList;
import java.util.List;

/**
 * Preserves the physical layout of a function while exposing commands joined with Minecraft's
 * backslash-continuation rules.
 *
 * @author FengMing
 */
public final class FunctionSource {
    private final List<String> lines;
    private final List<String> delimiters;

    private FunctionSource(List<String> lines, List<String> delimiters) {
        this.lines = lines;
        this.delimiters = delimiters;
    }

    public static FunctionSource parse(String source) {
        List<String> lines = new ArrayList<>();
        List<String> delimiters = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < source.length(); i++) {
            char character = source.charAt(i);
            if (character != '\r' && character != '\n') continue;

            lines.add(source.substring(start, i));
            if (character == '\r'
                    && i + 1 < source.length()
                    && source.charAt(i + 1) == '\n') {
                delimiters.add("\r\n");
                i++;
            } else {
                delimiters.add(String.valueOf(character));
            }
            start = i + 1;
        }
        if (start < source.length() || lines.isEmpty()) {
            lines.add(source.substring(start));
            delimiters.add("");
        }
        return new FunctionSource(List.copyOf(lines), List.copyOf(delimiters));
    }

    public List<String> lines() {
        return this.lines;
    }

    public String render(List<String> processedLines) {
        if (processedLines.size() != this.lines.size()) {
            throw new IllegalArgumentException("Function line count changed during extraction");
        }
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < processedLines.size(); i++) {
            result.append(processedLines.get(i)).append(this.delimiters.get(i));
        }
        return result.toString();
    }

    /** One command after trimming and joining all of its physical continuation lines. */
    public static final class LogicalCommand {
        private final String value;
        private final List<LineSegment> segments;
        private final int lastLineIndex;

        private LogicalCommand(String value, List<LineSegment> segments, int lastLineIndex) {
            this.value = value;
            this.segments = segments;
            this.lastLineIndex = lastLineIndex;
        }

        public static LogicalCommand read(List<String> lines, int firstLineIndex) {
            if (firstLineIndex < 0 || firstLineIndex >= lines.size()) {
                throw new IndexOutOfBoundsException(firstLineIndex);
            }

            String value = "";
            List<LineSegment> segments = new ArrayList<>();
            int lineIndex = firstLineIndex;
            boolean continues;
            do {
                AppendedLine appended = append(value, lineIndex, lines.get(lineIndex));
                value = appended.value();
                segments.add(appended.segment());
                continues = appended.continues();
                if (continues && ++lineIndex >= lines.size()) {
                    throw new IllegalArgumentException("Line continuation at end of file");
                }
            } while (continues);

            return new LogicalCommand(value, List.copyOf(segments), lineIndex);
        }

        public String value() {
            return this.value;
        }

        public int lastLineIndex() {
            return this.lastLineIndex;
        }

        public void write(List<String> lines, String updated) {
            int cursor = 0;
            for (int i = 0; i < this.segments.size(); i++) {
                LineSegment segment = this.segments.get(i);
                boolean continuation = i + 1 < this.segments.size();
                int remaining = updated.length() - cursor;
                int length = continuation ? Math.min(segment.logicalLength(), remaining) : remaining;
                String piece = updated.substring(cursor, cursor + length);
                cursor += length;

                String source = lines.get(segment.lineIndex());
                String prefix = source.substring(0, segment.sourceStart());
                String suffix = source.substring(segment.sourceSuffixStart());
                lines.set(
                        segment.lineIndex(),
                        prefix + piece + (continuation ? "\\" : "") + suffix);
            }
        }

        private static AppendedLine append(String prefix, int lineIndex, String source) {
            int start = 0;
            int end = source.length();
            // Minecraft's CommandFunction loader calls String.trim(), whose range is <= U+0020.
            while (start < end && source.charAt(start) <= ' ') start++;
            while (end > start && source.charAt(end - 1) <= ' ') end--;

            int suffixStart = end;
            boolean continues = end > start && source.charAt(end - 1) == '\\';
            if (continues) end--;
            String segmentText = source.substring(start, end);
            return new AppendedLine(
                    prefix + segmentText,
                    new LineSegment(lineIndex, start, suffixStart, segmentText.length()),
                    continues);
        }
    }

    private record AppendedLine(String value, LineSegment segment, boolean continues) {}

    private record LineSegment(int lineIndex, int sourceStart, int sourceSuffixStart, int logicalLength) {}
}
