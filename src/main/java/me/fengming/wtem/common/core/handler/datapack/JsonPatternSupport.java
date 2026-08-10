package me.fengming.wtem.common.core.handler.datapack;

import com.google.gson.JsonElement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import me.fengming.wtem.common.core.extraction.TranslationContext;
import me.fengming.wtem.common.core.extraction.pattern.DataPath;
import me.fengming.wtem.common.core.extraction.pattern.ExtractionPatterns;
import me.fengming.wtem.common.util.TranslationUtils;
import net.minecraft.resources.Identifier;

/**
 * Applies configured JSON patterns after a resource handler's schema-specific work.
 *
 * @author FengMing
 */
final class JsonPatternSupport {
    private JsonPatternSupport() {}

    static boolean apply(
            JsonElement root, String resourceDirectory, Identifier resource, List<String> builtIns) {
        Map<DataPath, ExtractionPatterns.ValueKind> rules = new LinkedHashMap<>();
        for (String path : builtIns) {
            rules.put(DataPath.parse(path), ExtractionPatterns.ValueKind.COMPONENT);
        }
        for (ExtractionPatterns.JsonRule rule : TranslationContext.config().patterns().json()) {
            if (rule.matches(resourceDirectory, resource)) {
                // Built-in schema knowledge is authoritative for a path. A user rule can add
                // selectors, but cannot turn a built-in component into a raw string.
                rules.putIfAbsent(rule.path(), rule.kind());
            }
        }

        boolean changed = false;
        for (Map.Entry<DataPath, ExtractionPatterns.ValueKind> entry : rules.entrySet()) {
            if (entry.getValue() == ExtractionPatterns.ValueKind.PLAIN_STRING) {
                TranslationUtils.catalogJsonStrings(root, entry.getKey());
            } else {
                changed |= TranslationUtils.translateJsonElement(root, entry.getKey());
            }
        }
        return changed;
    }

    static boolean apply(JsonElement root, String resourceDirectory, Identifier resource) {
        return apply(root, resourceDirectory, resource, List.of());
    }
}
