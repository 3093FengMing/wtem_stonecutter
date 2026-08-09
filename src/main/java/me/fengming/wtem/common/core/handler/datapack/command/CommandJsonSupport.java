package me.fengming.wtem.common.core.handler.datapack.command;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import java.util.List;
import java.util.Optional;

/**
 * JSON helpers used by the command extractor's component fallback.
 *
 * <p>Brigadier remains the authoritative parser for normal commands. This class only handles the
 * narrow fallback needed when unresolved macros prevent Brigadier from producing a complete
 * component argument range. Keeping the small lexer here separate makes that boundary explicit
 * and keeps command argument handling focused on Minecraft's parsed values.
 */
final class CommandJsonSupport {
    private static final List<String> COMPONENT_KEYWORDS =
            List.of("dialog show", " title ", " subtitle ", " actionbar ", " tellraw ", "tellraw ");

    private CommandJsonSupport() {}

    static boolean isComponentCommand(String source) {
        String command = source == null ? "" : source.trim();
        if (command.startsWith("$")) command = command.substring(1);
        return command.contains(" dialog show ")
                || command.startsWith("dialog show ")
                || command.contains(" title ")
                || command.contains(" subtitle ")
                || command.contains(" actionbar ")
                || command.contains(" tellraw ")
                || command.startsWith("tellraw ");
    }

    static Optional<JsonElement> parse(String value) {
        if (value == null || value.isBlank()) return Optional.empty();
        try {
            return Optional.of(JsonParser.parseString(value));
        } catch (JsonParseException | IllegalStateException ignored) {
            return Optional.empty();
        }
    }

    /** Parses generated component JSON while treating an unquoted macro as one component value. */
    static Optional<JsonElement> parseWithBareComponentMacros(String value) {
        Optional<JsonElement> direct = parse(value);
        if (direct.isPresent()) return direct;
        String normalized = replaceBareComponentMacros(value);
        return normalized.equals(value) ? Optional.empty() : parse(normalized);
    }

    static boolean looksLikeDialogSchema(JsonElement json) {
        if (json == null || !json.isJsonObject()) return false;
        JsonObject object = json.getAsJsonObject();
        return object.has("type")
                && object.get("type").isJsonPrimitive()
                && object.get("type").getAsJsonPrimitive().isString()
                && object.get("type").getAsString().contains(":");
    }

    /**
     * Locates the JSON argument of a component command when unresolved macros prevent Brigadier
     * from producing an argument range. Selector brackets are skipped by trying each balanced
     * candidate and accepting only a candidate that parses as JSON.
     */
    static Range locateComponentJson(String command) {
        if (command == null || command.isBlank()) return null;
        int start = -1;
        for (String keyword : COMPONENT_KEYWORDS) {
            int found = command.indexOf(keyword);
            if (found < 0) continue;
            int candidate = found + keyword.length();
            int json = findNextJson(command, candidate);
            if (json >= 0 && (start < 0 || json < start)) start = json;
        }
        if (start < 0) return null;
        int end = balancedJsonEnd(command, start);
        return end < 0 ? null : new Range(start, end);
    }

    private static String replaceBareComponentMacros(String value) {
        StringBuilder result = new StringBuilder(value.length());
        char quote = 0;
        boolean escaped = false;
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (quote != 0) {
                result.append(character);
                if (escaped) escaped = false;
                else if (character == '\\') escaped = true;
                else if (character == quote) quote = 0;
                continue;
            }
            if (character == '\"' || character == '\'') {
                quote = character;
                result.append(character);
                continue;
            }
            if (character != '$'
                    || index + 2 >= value.length()
                    || value.charAt(index + 1) != '(') {
                result.append(character);
                continue;
            }
            int close = value.indexOf(')', index + 2);
            if (close < 0) {
                result.append(character);
                continue;
            }
            int previous = index - 1;
            while (previous >= 0 && Character.isWhitespace(value.charAt(previous))) previous--;
            int next = close + 1;
            while (next < value.length() && Character.isWhitespace(value.charAt(next))) next++;
            boolean elementBoundary =
                    previous >= 0
                            && (value.charAt(previous) == '['
                                    || value.charAt(previous) == ','
                                    || value.charAt(previous) == ':')
                            && next < value.length()
                            && (value.charAt(next) == ']'
                                    || value.charAt(next) == ','
                                    || value.charAt(next) == '}');
            if (!elementBoundary) {
                result.append(value, index, close + 1);
                index = close;
                continue;
            }
            String macro = value.substring(index, close + 1);
            result.append("{\"text\":\"").append(macro).append("\"}");
            index = close;
        }
        return result.toString();
    }

    private static int findNextJson(String command, int start) {
        char quote = 0;
        boolean escaped = false;
        for (int i = start; i < command.length(); i++) {
            char character = command.charAt(i);
            if (quote != 0) {
                if (escaped) escaped = false;
                else if (character == '\\') escaped = true;
                else if (character == quote) quote = 0;
                continue;
            }
            if (character == '\'' || character == '\"') {
                quote = character;
            } else if (character == '{' || character == '[') {
                int end = balancedJsonEnd(command, i);
                if (end > i
                        && parseWithBareComponentMacros(command.substring(i, end)).isPresent()) {
                    return i;
                }
            }
        }
        return -1;
    }

    private static int balancedJsonEnd(String command, int start) {
        int curly = 0;
        int square = 0;
        char quote = 0;
        boolean escaped = false;
        for (int i = start; i < command.length(); i++) {
            char character = command.charAt(i);
            if (quote != 0) {
                if (escaped) escaped = false;
                else if (character == '\\') escaped = true;
                else if (character == quote) quote = 0;
                continue;
            }
            if (character == '\'' || character == '\"') {
                quote = character;
            } else if (character == '{') {
                curly++;
            } else if (character == '}') {
                if (--curly == 0 && square == 0) return i + 1;
            } else if (character == '[') {
                square++;
            } else if (character == ']') {
                if (--square == 0 && curly == 0) return i + 1;
            }
        }
        return -1;
    }

    record Range(int start, int end) {}
}
