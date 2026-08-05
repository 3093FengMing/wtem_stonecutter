package me.fengming.wtem.common.core.handler.datapack.command;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import me.fengming.wtem.common.core.extraction.TranslationContext;

/**
 * Maps ranges in a masked/materialized command back to the original macro source and restores
 * caller placeholders after ordinary Brigadier extraction has serialized an argument.
 *
 * @author FengMing
 */
final class MacroArgumentRestorer {
    private MacroArgumentRestorer() {}

    /**
     * A function line presented to the command parser, with macro interpolations masked out.
     *
     * <p>A macro line is an ordinary command behind a {@code $} marker, except that {@code $(name)}
     * interpolations stand where the grammar expects a concrete value. The dispatcher rejects those
     * outright, which is why macro lines used to be skipped entirely. Each interpolation is replaced
     * by the short, broadly valid stand-in {@code 1}. A short stand-in matters for arguments backed by
     * a finite name table: for example, {@code inventory.$(slot)} can parse as {@code inventory.1},
     * whereas an equal-length string of digits names no inventory slot.
     *
     * <p>Shortening an interpolation moves every argument behind it. Each mask therefore remembers
     * both its source and parser ranges so replacements found in {@code text} can still be applied to
     * the corresponding range in {@code source} without altering the macro variables themselves.
     */
    record CommandLine(String source, String text, List<Mask> masks) {
        private static final String MARKER = "$";
        private static final String VARIABLE_PREFIX = "$(";
        // One is accepted by numeric arguments that reject zero and is also valid inside strings and
        // resource locations. No stand-in can satisfy every command grammar. String-valued color
        // fields are the important exception: "1" is not a legal color and would make an entire
        // dialog component fail to parse, so maskValue chooses a valid color for that context.
        private static final String MASK_VALUE = "1";
        private static final String COLOR_MASK_VALUE = "white";

        static CommandLine of(String source) {
            return of(source, Map.of());
        }

        static CommandLine of(String source, Map<String, String> binding) {
            if (!source.startsWith(MARKER)) return new CommandLine(source, source, List.of());

            String body = source.substring(MARKER.length());
            StringBuilder masked = new StringBuilder(body.length());
            List<Mask> masks = new ArrayList<>();
            int copied = 0;
            int start = body.indexOf(VARIABLE_PREFIX);
            while (start >= 0) {
                int end = body.indexOf(')', start);
                if (end < 0) break;
                end++;

                masked.append(body, copied, start);
                int maskedStart = masked.length();
                String name = body.substring(start + VARIABLE_PREFIX.length(), end - 1);
                boolean resolved = binding != null && binding.containsKey(name);
                String materialized =
                        resolved ? binding.get(name) : maskValue(body, start);
                masked.append(materialized);
                masks.add(
                        new Mask(
                                new Range(start, end),
                                new Range(maskedStart, masked.length()),
                                name,
                                materialized,
                                resolved));
                copied = end;
                start = body.indexOf(VARIABLE_PREFIX, end);
            }
            masked.append(body, copied, body.length());
            return new CommandLine(source, masked.toString(), List.copyOf(masks));
        }

        private static String maskValue(String body, int macroStart) {
            // A macro in a JSON color field is parsed as a Style color. Minecraft accepts named
            // colours but rejects numeric strings, so use a stable legal value while retaining the
            // original $(name) in the source-to-parser mapping.
            String before = body.substring(0, macroStart);
            if (before.matches("(?s).*\"(?:color|shadow_color)\"\\s*:\\s*\"$")) {
                return COLOR_MASK_VALUE;
            }
            return MASK_VALUE;
        }

        boolean macro() {
            return this.source.startsWith(MARKER);
        }

        /** Reports whether {@code [start, end)} overlaps a masked interpolation. */
        boolean isMasked(int start, int end) {
            return this.masks.stream()
                    .map(Mask::masked)
                    .anyMatch(mask -> mask.start() < end && start < mask.end());
        }

        boolean hasUnresolvedMask(int start, int end) {
            return this.masks.stream()
                    .anyMatch(
                            mask ->
                                    !mask.resolved()
                                            && mask.masked().start() < end
                                            && start < mask.masked().end());
        }

        /** Restores caller values that fell inside a normally serialized argument replacement. */
        Replacement restoreMacros(
                Replacement replacement,
                Set<String> catalogKeysBefore,
                int recordsBefore) {
            String restored = replacement.value();
            List<String> dynamicTranslationKeys = new ArrayList<>();
            Set<String> replacedCatalogKeys = new LinkedHashSet<>();
            for (Mask mask : this.masks) {
                if (!mask.resolved()
                        || mask.masked().start() >= replacement.end()
                        || replacement.start() >= mask.masked().end()) {
                    continue;
                }

                String macro = "$(" + mask.name() + ")";
                int required = getRequired(replacement, mask);
                double expected = materializedRelativePosition(mask, replacement);
                for (int i = 0; i < dynamicTranslationKeys.size(); i++) {
                    String template = dynamicTranslationKeys.get(i);
                    if (occurrences(template, macro) >= required) continue;
                    String updated =
                            replaceClosestOccurrence(
                                    template, mask.materialized(), macro, expected);
                    if (updated != null) dynamicTranslationKeys.set(i, updated);
                }
                if (occurrences(restored, macro) >= required) continue;

                RestoredMacro next =
                        restoreOneMacro(
                                restored,
                                mask,
                                macro,
                                replacement.start(),
                                replacement.end());
                if (next == null) {
                    CommandParseSupport.recordCommandFailure(
                            "function_macro_restore",
                            replacement.value(),
                            new IllegalStateException(
                                    "Could not restore caller value for $(" + mask.name() + ")"));
                    return null;
                }
                restored = next.value();
                if (next.dynamicTranslationKey() != null
                        && !dynamicTranslationKeys.contains(next.dynamicTranslationKey())) {
                    dynamicTranslationKeys.add(next.dynamicTranslationKey());
                }
                if (next.catalogKey() != null) replacedCatalogKeys.add(next.catalogKey());
            }
            for (String key : replacedCatalogKeys) {
                if (catalogKeysBefore.contains(key) || restored.contains(key)) continue;
                TranslationContext.discardEntryAddedSince(key, recordsBefore);
            }
            for (String template : dynamicTranslationKeys) {
                if (!template.contains("$(")) continue;
                MacroCommandMaterializer.recordMacroTemplateWarning(
                        template, replacement.value());
            }
            return new Replacement(
                    replacement.start(),
                    replacement.end(),
                    restored,
                    replacement.fallbackJson());
        }

        private int getRequired(Replacement replacement, Mask mask) {
            int required = 0;
            for (Mask candidate : this.masks) {
                if (candidate.resolved()
                        && candidate.name().equals(mask.name())
                        && candidate.masked().start() < replacement.end()
                        && replacement.start() < candidate.masked().end()
                        && candidate.masked().start() <= mask.masked().start()) {
                    required++;
                }
            }
            return required;
        }

        private RestoredMacro restoreOneMacro(
                String output, Mask mask, String macro, int argumentStart, int argumentEnd) {
            String materialized = mask.materialized();
            if (materialized == null || materialized.isEmpty()) return null;

            double expected =
                    materializedRelativePosition(
                            mask,
                            new Replacement(argumentStart, argumentEnd, output, false));
            List<RestoreSpelling> spellings = new ArrayList<>();
            for (Map.Entry<String, String> entry : TranslationContext.snapshot().entrySet()) {
                String catalogText = entry.getValue();
                if (catalogText.equals(materialized)) {
                    addSpelling(spellings, entry.getKey(), macro, macro, entry.getKey());
                } else if (catalogText.contains(materialized)) {
                    String template =
                            replaceClosestOccurrence(
                                    catalogText, materialized, macro, expected);
                    if (template != null) {
                        addSpelling(
                                spellings,
                                entry.getKey(),
                                template,
                                template,
                                entry.getKey());
                    }
                }
            }
            if (materialized.indexOf(':') < 0
                    && materialized.matches("[a-z0-9_.\\-/]+")) {
                addSpelling(spellings, "minecraft:" + materialized, macro, null, null);
            }
            addSpelling(spellings, materialized, macro, null, null);

            int bestIndex = -1;
            RestoreSpelling best = null;
            double bestDistance = Double.MAX_VALUE;
            for (RestoreSpelling spelling : spellings) {
                int from = 0;
                while (from <= output.length() - spelling.search().length()) {
                    int index = output.indexOf(spelling.search(), from);
                    if (index < 0) break;
                    double actual =
                            output.isEmpty()
                                    ? 0.5
                                    : (double) index / output.length();
                    double distance = Math.abs(actual - expected);
                    if (distance < bestDistance) {
                        bestDistance = distance;
                        bestIndex = index;
                        best = spelling;
                    }
                    from = index + Math.max(1, spelling.search().length());
                }
            }
            if (best == null) return null;
            return new RestoredMacro(
                    output.substring(0, bestIndex)
                            + best.replacement()
                            + output.substring(bestIndex + best.search().length()),
                    best.dynamicTranslationKey(),
                    best.catalogKey());
        }

        private static void addSpelling(
                List<RestoreSpelling> spellings,
                String search,
                String replacement,
                String dynamicTranslationKey,
                String catalogKey) {
            if (search == null || search.isEmpty()) return;
            RestoreSpelling spelling =
                    new RestoreSpelling(search, replacement, dynamicTranslationKey, catalogKey);
            if (!spellings.contains(spelling)) spellings.add(spelling);
        }

        private static double relativePosition(int position, int start, int end) {
            if (end <= start) return 0.5;
            return Math.clamp((double) (position - start) / (end - start), 0.0, 1.0);
        }

        /**
         * Locates a macro inside the materialized source string rather than the shortened command.
         * This keeps equal values in their original order: in {@code "1 $(value) 1"} the macro is
         * the middle {@code 1}, while two macros both bound to {@code same} retain left/right order.
         */
        private double materializedRelativePosition(Mask target, Replacement replacement) {
            Range argument = sourceRange(replacement.start(), replacement.end());
            String body = this.source.substring(offset());
            int segmentStart = argument.start();
            int segmentEnd = argument.end();

            char quote = 0;
            boolean escaped = false;
            int openQuote = -1;
            for (int i = argument.start(); i < target.source().start(); i++) {
                char character = body.charAt(i);
                if (quote != 0) {
                    if (escaped) escaped = false;
                    else if (character == '\\') escaped = true;
                    else if (character == quote) {
                        quote = 0;
                        openQuote = -1;
                    }
                } else if (character == '\'' || character == '"') {
                    quote = character;
                    openQuote = i + 1;
                }
            }
            if (quote != 0 && openQuote >= 0) {
                segmentStart = openQuote;
                escaped = false;
                for (int i = target.source().end(); i < argument.end(); i++) {
                    char character = body.charAt(i);
                    if (escaped) escaped = false;
                    else if (character == '\\') escaped = true;
                    else if (character == quote) {
                        segmentEnd = i;
                        break;
                    }
                }
            }

            int cursor = segmentStart;
            int materializedLength = 0;
            int targetOffset = -1;
            for (Mask mask : this.masks) {
                if (mask.source().end() <= segmentStart) continue;
                if (mask.source().start() >= segmentEnd) break;
                if (mask.source().start() < cursor || mask.source().end() > segmentEnd) continue;
                materializedLength += mask.source().start() - cursor;
                if (mask == target) targetOffset = materializedLength;
                materializedLength += mask.materialized().length();
                cursor = mask.source().end();
            }
            materializedLength += Math.max(0, segmentEnd - cursor);
            if (targetOffset < 0 || materializedLength <= 0) {
                return relativePosition(
                        target.source().start(), argument.start(), argument.end());
            }
            return (double) targetOffset / materializedLength;
        }

        /** Replaces exactly one occurrence, preserving equal static values around a macro value. */
        private static String replaceClosestOccurrence(
                String value, String search, String replacement, double expected) {
            if (value == null || search == null || search.isEmpty()) return null;
            int bestIndex = -1;
            double bestDistance = Double.MAX_VALUE;
            int from = 0;
            while (from <= value.length() - search.length()) {
                int index = value.indexOf(search, from);
                if (index < 0) break;
                double actual = (double) index / value.length();
                double distance = Math.abs(actual - expected);
                if (distance < bestDistance) {
                    bestDistance = distance;
                    bestIndex = index;
                }
                from = index + search.length();
            }
            if (bestIndex < 0) return null;
            return value.substring(0, bestIndex)
                    + replacement
                    + value.substring(bestIndex + search.length());
        }

        private static int occurrences(String value, String needle) {
            int count = 0;
            int cursor = 0;
            while ((cursor = value.indexOf(needle, cursor)) >= 0) {
                count++;
                cursor += Math.max(1, needle.length());
            }
            return count;
        }

        /** Applies {@code replacements} to the original line rather than to the masked form. */
        String render(List<Replacement> replacements) {
            StringBuilder body = new StringBuilder(this.source.substring(offset()));
            for (Replacement replacement : replacements) {
                Range sourceRange = sourceRange(replacement.start(), replacement.end());
                body.replace(sourceRange.start(), sourceRange.end(), replacement.value());
            }
            return this.macro() ? MARKER + body : body.toString();
        }

        /** Maps a parser range back to the source body, including a component containing macros. */
        private Range sourceRange(int start, int end) {
            return new Range(sourceOffset(start, true), sourceOffset(end, false));
        }

        String sourceArgument(int start, int end) {
            Range range = sourceRange(start, end);
            return this.source.substring(offset() + range.start(), offset() + range.end());
        }

        private int sourceOffset(int maskedOffset, boolean beginning) {
            int expansion = 0;
            for (Mask mask : this.masks) {
                if (maskedOffset < mask.masked().start()) break;
                if (maskedOffset <= mask.masked().end()) {
                    if (maskedOffset < mask.masked().end()) {
                        return (beginning ? mask.source().start() : mask.source().end());
                    }
                    expansion += mask.source().length() - mask.masked().length();
                    break;
                }
                expansion += mask.source().length() - mask.masked().length();
            }
            return maskedOffset + expansion;
        }

        private int offset() {
            return this.macro() ? MARKER.length() : 0;
        }
    }

    record Range(int start, int end) {
        int length() {
            return this.end - this.start;
        }
    }

    record Mask(Range source, Range masked, String name, String materialized, boolean resolved) {}

    private record RestoreSpelling(
            String search,
            String replacement,
            String dynamicTranslationKey,
            String catalogKey) {}

    private record RestoredMacro(
            String value, String dynamicTranslationKey, String catalogKey) {}

    record Replacement(int start, int end, String value, boolean fallbackJson) {}
}
