package me.fengming.wtem.common.core.handler.datapack.command;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Represents the source spelling of a macro command and maps parser ranges back to it.
 *
 * <p>The parser needs concrete stand-ins for non-text macros. Text-component macros remain in the
 * parser input so the translation utility can emit them as {@code with} arguments; resolved
 * non-text macros are written as caller values. The important invariant is that any fallback
 * restoration is range based: it may restore a complete source token, never an arbitrary
 * occurrence of a short mask such as {@code 1} in the serialized command.
 */
final class MacroArgumentRestorer {
    private MacroArgumentRestorer() {}

    record CommandLine(String source, String text, List<Mask> masks) {
        private static final String MARKER = "$";
        private static final String VARIABLE_PREFIX = "$(";
        private static final String MASK_VALUE = "1";
        private static final Set<String> TEXT_FIELDS =
                Set.of("text", "contents", "description",
                    "title", "external_title", "label",
                    "tooltip", "display", "separator",
                    "extra");
        private static final Set<String> STRUCTURED_FIELDS =
                Set.of("text", "contents", "description",
                    "title", "external_title", "label",
                    "tooltip", "display", "separator",
                    "extra", "name", "translate",
                    "fallback", "with", "score",
                    "selector", "nbt", "keybind",
                    "object", "color", "shadow_color",
                    "bold", "italic", "underlined",
                    "strikethrough", "obfuscated",
                    "font", "insertion", "clickEvent",
                    "click_event", "hoverEvent",
                    "hover_event", "action", "command",
                    "value", "type", "id", "raw",
                    "path", "width", "columns");
        private static final Set<String> SCALAR_COMPONENT_FIELDS =
                Set.of("color", "shadow_color", "font", "insertion", "type", "id", "path");

        static CommandLine of(String source) {
            return of(source, Map.of());
        }

        static CommandLine of(String source, Map<String, String> binding) {
            if (source == null || !source.startsWith(MARKER)) {
                return new CommandLine(source == null ? "" : source, source == null ? "" : source, List.of());
            }

            String body = source.substring(MARKER.length());
            StringBuilder masked = new StringBuilder(body.length());
            List<Mask> masks = new ArrayList<>();
            int copied = 0;
            int cursor = body.indexOf(VARIABLE_PREFIX);
            while (cursor >= 0) {
                int close = body.indexOf(')', cursor + VARIABLE_PREFIX.length());
                if (close < 0) break;
                close++;

                masked.append(body, copied, cursor);
                int maskedStart = masked.length();
                String name = body.substring(cursor + VARIABLE_PREFIX.length(), close - 1);
                boolean resolved = binding != null && binding.containsKey(name)
                        && binding.get(name) != null;
                String materialized = resolved ? binding.get(name) : MASK_VALUE;
                boolean preserved = isTextMacro(body, cursor) || isInsideCommandValue(body, cursor);
                String parserValue = preserved ? body.substring(cursor, close) : materialized;
                masked.append(parserValue);
                masks.add(
                        new Mask(
                                new Range(cursor, close),
                                new Range(maskedStart, masked.length()),
                                name,
                                materialized,
                                resolved,
                                preserved));
                copied = close;
                cursor = body.indexOf(VARIABLE_PREFIX, close);
            }
            masked.append(body, copied, body.length());
            return new CommandLine(source, masked.toString(), List.copyOf(masks));
        }

        boolean macro() {
            return source.startsWith(MARKER);
        }

        boolean isMasked(int start, int end) {
            return masks.stream()
                    .anyMatch(mask -> mask.masked().start() < end && start < mask.masked().end());
        }

        boolean hasUnresolvedMask(int start, int end) {
            return masks.stream()
                    .anyMatch(
                            mask ->
                                    !mask.resolved()
                                            && mask.masked().start() < end
                                            && start < mask.masked().end());
        }

        /**
         * Returns the first caller value that cannot safely be substituted into this command.
         *
         * <p>Function macro substitution is textual. A structured component such as
         * {@code {translate:"example.key"}} is therefore not safe inside a quoted JSON/SNBT
         * value: inserting it after the opening quote produces a second, unescaped set of quotes.
         * Keeping the whole source command is safer than translating only its unrelated siblings.
         */
        String unsafeResolvedMacro() {
            return unsafeResolvedMacro(Set.of());
        }

        /**
         * Allows a structured component caller value for a text macro.  The materializer will
         * later emit that occurrence as an unquoted {@code with:[$(name)]} argument; treating it
         * as an unsafe quoted scalar here would discard the very component translation we need.
         */
        String unsafeResolvedMacro(Set<String> structuredComponentMacros) {
            String body = source.substring(offset());
            for (Mask mask : masks) {
                if (!mask.resolved()) continue;
                if (structuredComponentMacros.contains(mask.name())
                        && isTextMacro(body, mask.source().start())) {
                    continue;
                }
                if (isUnsafeResolvedValue(body, mask)) return mask.name();
            }
            return null;
        }

        /**
         * Restores only non-component tokens that a structured serializer had to materialize.
         * Text-component macros are deliberately kept in the parser input and become {@code with}
         * arguments during extraction.  Resolved non-text macros are deliberately kept concrete;
         * only an unresolved non-text mask may need restoration when a serializer retained its
         * placeholder instead of the source spelling.
         */
        Replacement restoreMacros(
                Replacement replacement, Set<String> ignoredCatalogKeys, int ignoredRecordIndex) {
            if (replacement.value() == null) {
                return replacement;
            }

            String restored = replacement.value();
            for (Mask mask : overlappingMasks(replacement)) {
                if (mask.preserved() || mask.resolved()) continue;
                String macro = "$(" + mask.name() + ")";
                if (restored.contains(macro)) continue;

                String sourceToken = sourceToken(mask);
                if (sourceToken == null || !sourceToken.contains(macro)) return null;
                String materializedToken = sourceToken.replace(macro, mask.materialized());
                String updated = replaceNearest(restored, materializedToken, macro, mask, replacement);
                if (updated == null) return null;
                restored = updated;
            }
            return new Replacement(replacement.start(), replacement.end(), restored);
        }

        /** Applies replacements to the original source line, preserving the macro marker. */
        String render(List<Replacement> replacements) {
            StringBuilder body = new StringBuilder(source.substring(offset()));
            List<RenderedReplacement> rendered = new ArrayList<>();
            for (Replacement replacement : replacements) {
                Range range = sourceRange(replacement.start(), replacement.end());
                // The translation argument already contains caller values for non-text fields,
                // while text fields retain their macro as a with argument.  Do not run a broad
                // string replacement here: equal macro names can legitimately occur in both kinds
                // of field inside one component.
                rendered.add(new RenderedReplacement(range, replacement.value()));
            }

            // Macros outside a translated argument (summon IDs, item counts, selectors, and so
            // on) still have to be written back as their caller values.  They are edits in source
            // coordinates, so repeated/equal values cannot steal one another's occurrence.
            for (Mask mask : masks) {
                if (mask.preserved() || !mask.resolved()) continue;
                if (rendered.stream().anyMatch(edit -> contains(edit.range(), mask.source()))) continue;
                rendered.add(new RenderedReplacement(mask.source(), mask.materialized()));
            }

            rendered.sort(
                    (left, right) -> Integer.compare(right.range().start(), left.range().start()));
            for (RenderedReplacement replacement : rendered) {
                body.replace(
                        replacement.range().start(),
                        replacement.range().end(),
                        replacement.value());
            }
            return macro() ? MARKER + body : body.toString();
        }

        private static boolean contains(Range outer, Range inner) {
            return outer.start() <= inner.start() && inner.end() <= outer.end();
        }

        String sourceArgument(int start, int end) {
            Range range = sourceRange(start, end);
            return source.substring(offset() + range.start(), offset() + range.end());
        }

        String materializedArgument(int start, int end) {
            Range range = sourceRange(start, end);
            String body = source.substring(offset());
            StringBuilder result = new StringBuilder(range.length());
            int cursor = range.start();
            for (Mask mask : masks) {
                if (mask.source().start() < range.start()) continue;
                if (mask.source().start() >= range.end()) break;
                result.append(body, cursor, mask.source().start());
                result.append(mask.parserValue(body));
                cursor = mask.source().end();
            }
            result.append(body, cursor, range.end());
            return result.toString();
        }

        /**
         * Returns the source spelling used by the translation visitors.  A caller value replaces a
         * macro only when it is outside a text component; text macros stay available for the
         * translation utility to turn into {@code with} arguments.  Unknown non-text macros also
         * stay in this source view so an extracted component never bakes the internal mask into a
         * language value.
         */
        String translationArgument(int start, int end) {
            Range range = sourceRange(start, end);
            String body = source.substring(offset());
            StringBuilder result = new StringBuilder(range.length());
            int cursor = range.start();
            for (Mask mask : masks) {
                if (mask.source().start() < range.start()) continue;
                if (mask.source().start() >= range.end()) break;
                result.append(body, cursor, mask.source().start());
                result.append(mask.translationValue(body));
                cursor = mask.source().end();
            }
            result.append(body, cursor, range.end());
            return result.toString();
        }

        private List<Mask> overlappingMasks(Replacement replacement) {
            return masks.stream()
                    .filter(
                            mask ->
                                    mask.masked().start() < replacement.end()
                                            && replacement.start() < mask.masked().end())
                    .toList();
        }

        /** Finds the complete token containing a macro, including a quoted NBT/JSON string. */
        private String sourceToken(Mask mask) {
            String body = source.substring(offset());
            int start = mask.source().start();
            int end = mask.source().end();

            char quote = enclosingQuote(body, start, end);
            if (quote != 0) {
                int quotedStart = start - 1;
                while (quotedStart >= 0 && body.charAt(quotedStart) != quote) quotedStart--;
                int quotedEnd = end;
                while (quotedEnd < body.length() && body.charAt(quotedEnd) != quote) quotedEnd++;
                if (quotedStart >= 0 && quotedEnd > end) {
                    return body.substring(quotedStart + 1, quotedEnd);
                }
            }

            while (start > 0 && !isTokenDelimiter(body.charAt(start - 1))) start--;
            while (end < body.length() && !isTokenDelimiter(body.charAt(end))) end++;
            String token = body.substring(start, end);
            return token.indexOf(' ') >= 0 || token.indexOf('\t') >= 0 ? null : token;
        }

        private static char enclosingQuote(String body, int start, int end) {
            char quote = 0;
            boolean escaped = false;
            for (int i = 0; i < end && i < body.length(); i++) {
                char value = body.charAt(i);
                if (quote != 0) {
                    if (escaped) escaped = false;
                    else if (value == '\\') escaped = true;
                    else if (value == quote) quote = 0;
                } else if (value == '\'' || value == '"') {
                    quote = value;
                }
            }
            return quote;
        }

        private static String replaceNearest(
                String output, String search, String replacement, Mask mask, Replacement argument) {
            if (search == null || search.isEmpty()) return null;
            List<Integer> occurrences = new ArrayList<>();
            int from = 0;
            while (from <= output.length() - search.length()) {
                int index = output.indexOf(search, from);
                if (index < 0) break;
                occurrences.add(index);
                from = index + search.length();
            }
            if (occurrences.isEmpty()) return null;

            double expected =
                    argument.end() <= argument.start()
                            ? 0.5
                            : (double) (mask.masked().start() - argument.start())
                                    / (argument.end() - argument.start());
            int best = occurrences.getFirst();
            double distance = Double.MAX_VALUE;
            for (int index : occurrences) {
                double actual = (double) index / output.length();
                double candidate = Math.abs(actual - expected);
                if (candidate < distance) {
                    distance = candidate;
                    best = index;
                }
            }
            return output.substring(0, best)
                    + replacement
                    + output.substring(best + search.length());
        }

        private static boolean isUnsafeResolvedValue(String body, Mask mask) {
            String value = mask.materialized() == null ? "" : mask.materialized().trim();
            if (value.isEmpty()) return false;

            String field = nearestStructuredField(body, mask.source().start());
            boolean quoted = enclosingQuoteStart(body, mask.source().start()) >= 0;
            boolean structured = looksLikeStructuredValue(value);

            // A component object is never a valid value for these scalar component members,
            // regardless of whether the source happened to quote the macro.
            if (structured && field != null && SCALAR_COMPONENT_FIELDS.contains(field)) return true;

            // In a quoted member, raw quotes, backslashes, and structured values would change the
            // surrounding JSON/SNBT grammar. Plain values such as red, #d2691e, or 125 remain safe.
            return quoted && (structured || value.indexOf('"') >= 0 || value.indexOf('\\') >= 0);
        }

        private static boolean looksLikeStructuredValue(String value) {
            if (value.startsWith("[") && value.contains("{")) return true;
            return value.startsWith("{") && value.contains(":");
        }

        private Range sourceRange(int start, int end) {
            return new Range(sourceOffset(start, true), sourceOffset(end, false));
        }

        private int sourceOffset(int maskedOffset, boolean beginning) {
            int expansion = 0;
            for (Mask mask : masks) {
                if (maskedOffset < mask.masked().start()) break;
                if (maskedOffset <= mask.masked().end()) {
                    if (maskedOffset < mask.masked().end()) {
                        return beginning ? mask.source().start() : mask.source().end();
                    }
                    expansion += mask.source().length() - mask.masked().length();
                    break;
                }
                expansion += mask.source().length() - mask.masked().length();
            }
            return maskedOffset + expansion;
        }

        private static boolean isTokenDelimiter(char value) {
            return Character.isWhitespace(value) || "{}[],:'\"=".indexOf(value) >= 0;
        }

        private int offset() {
            return macro() ? MARKER.length() : 0;
        }

        private static boolean isTextMacro(String body, int cursor) {
            // A macro outside a quoted component value is a command/resource argument (for
            // example `$(item)` or `$(count)`), not text merely because an earlier JSON object in
            // the same command happened to contain a `text` member.
            if (enclosingQuoteStart(body, cursor) < 0) return false;
            String field = nearestStructuredField(body, cursor);
            if (field == null) return false;
            // `name` is a structured argument only inside a score component.  Treating every NBT
            // field named `name` as visible text leaves ordinary values such as profile names
            // unmaterialized even when the caller supplies them statically.
            if ("name".equals(field)) {
                return "score".equals(enclosingObjectField(body, cursor));
            }
            return TEXT_FIELDS.contains(field);
        }

        /** Keeps an embedded command intact so its own command parser can classify its macros. */
        private static boolean isInsideCommandValue(String body, int cursor) {
            int opening = enclosingQuoteStart(body, cursor);
            if (opening < 0) return false;
            return "command".equals(nearestStructuredField(body, opening));
        }

        /** Returns the opening quote of the string containing {@code before}, or {@code -1}. */
        private static int enclosingQuoteStart(String body, int before) {
            char quote = 0;
            int opening = -1;
            boolean escaped = false;
            for (int i = 0; i < before; i++) {
                char value = body.charAt(i);
                if (quote != 0) {
                    if (escaped) {
                        escaped = false;
                    } else if (value == '\\') {
                        escaped = true;
                    } else if (value == quote) {
                        quote = 0;
                        opening = -1;
                    }
                } else if (value == '\'' || value == '"') {
                    quote = value;
                    opening = i;
                }
            }
            return opening;
        }

        /** Finds the nearest syntactic field name without treating punctuation in prose as a key. */
        private static String nearestStructuredField(String body, int before) {
            for (int i = before - 1; i >= 0; i--) {
                if (body.charAt(i) != ':' || isEscaped(body, i)) continue;

                int cursor = i - 1;
                while (cursor >= 0 && Character.isWhitespace(body.charAt(cursor))) cursor--;
                if (cursor < 0) continue;

                String name;
                char last = body.charAt(cursor);
                if (last == '\'' || last == '"') {
                    int close = cursor;
                    cursor--;
                    while (cursor >= 0) {
                        if (body.charAt(cursor) == last && !isEscaped(body, cursor)) break;
                        cursor--;
                    }
                    if (cursor < 0) continue;
                    name = body.substring(cursor + 1, close);
                    name = name.replace("\\\"", "\"").replace("\\'", "'");
                } else {
                    int end = cursor + 1;
                    while (cursor >= 0 && isFieldCharacter(body.charAt(cursor))) cursor--;
                    name = body.substring(cursor + 1, end);
                }
                if (STRUCTURED_FIELDS.contains(name)) return name;
            }
            return null;
        }

        /** Returns the field whose value is the innermost object containing {@code before}. */
        private static String enclosingObjectField(String body, int before) {
            List<Integer> openings = new ArrayList<>();
            char quote = 0;
            boolean escaped = false;
            for (int i = 0; i < before; i++) {
                char value = body.charAt(i);
                if (quote != 0) {
                    if (escaped) escaped = false;
                    else if (value == '\\') escaped = true;
                    else if (value == quote) quote = 0;
                    continue;
                }
                if (value == '\'' || value == '"') {
                    quote = value;
                } else if (value == '{') {
                    openings.add(i);
                } else if (value == '}' && !openings.isEmpty()) {
                    openings.removeLast();
                }
            }
            if (openings.isEmpty()) return null;

            int cursor = openings.getLast() - 1;
            while (cursor >= 0 && Character.isWhitespace(body.charAt(cursor))) cursor--;
            if (cursor < 0 || body.charAt(cursor) != ':') return null;
            return nearestStructuredField(body, openings.getLast());
        }

        private static boolean isFieldCharacter(char value) {
            return Character.isLetterOrDigit(value) || value == '_' || value == '-' || value == '.';
        }

        private static boolean isEscaped(String value, int index) {
            int slashes = 0;
            for (int i = index - 1; i >= 0 && value.charAt(i) == '\\'; i--) slashes++;
            return (slashes & 1) != 0;
        }
    }

    record Range(int start, int end) {
        int length() {
            return end - start;
        }
    }

    record Mask(
            Range source,
            Range masked,
            String name,
            String materialized,
            boolean resolved,
            boolean preserved) {
        private String parserValue(String sourceBody) {
            return preserved ? sourceBody.substring(source.start(), source.end()) : materialized;
        }

        private String translationValue(String sourceBody) {
            return preserved || !resolved
                    ? sourceBody.substring(source.start(), source.end())
                    : materialized;
        }
    }

    private record RenderedReplacement(Range range, String value) {}

    record Replacement(int start, int end, String value) {}
}
