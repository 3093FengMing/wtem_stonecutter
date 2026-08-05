package me.fengming.wtem.common.core.extraction.export;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import me.fengming.wtem.common.Wtem;
import me.fengming.wtem.common.config.WtemConfig;
import me.fengming.wtem.common.core.extraction.ai.AiChatClient;
import me.fengming.wtem.common.core.extraction.service.ExtractionSession;
import me.fengming.wtem.common.util.ResourceIo;

/** Optional OpenAI-compatible translation exporter.  A failed request never fails extraction.
 *
 * @author FengMing
 */
public final class AiTranslationExporter {
    private AiTranslationExporter() {}

    public static boolean export(
            WtemConfig.AiTranslation settings,
            Path output,
            Map<String, String> source,
            ExtractionSession session) {
        if (settings == null || !settings.enabled() || source == null) return false;
        try {
            Map<String, String> translated;
            if (settings.apiKey().isBlank()) {
                // A missing credential should not make the optional exporter destructive or
                // prevent a resource pack from being produced.  Publish an exact copy of the
                // primary catalog and leave a concise diagnostic in the log; the key itself is
                // never included in that message.
                Wtem.LOGGER.warn(
                        "AI translation is enabled but no API key is configured; exporting the source catalog unchanged");
                translated = new LinkedHashMap<>(source);
            } else {
                translated = translate(settings, source);
            }
            ResourceIo.writeJson(output, toJson(source, translated));
            session.recordModifiedResource();
            return true;
        } catch (RuntimeException exception) {
            // Deliberately do not include the endpoint body or request headers: API keys must never
            // leak into the log, and the regular language catalog remains usable on network errors.
            session.diagnostics().record("ai_translation", output.toString(), exception);
            Wtem.LOGGER.warn("AI translation export failed; keeping the extracted catalog", exception);
            return false;
        }
    }

    private static Map<String, String> translate(
            WtemConfig.AiTranslation settings, Map<String, String> source) {
        AiChatClient client = new AiChatClient(settings);
        Map<String, String> result = new LinkedHashMap<>(source);
        List<Map.Entry<String, String>> entries =
                source.entrySet().stream()
                        .filter(entry -> entry.getKey() != null && entry.getValue() != null)
                        .filter(entry -> !entry.getValue().isBlank())
                        .toList();
        for (int offset = 0; offset < entries.size(); offset += settings.batchSize()) {
            List<Map.Entry<String, String>> batch =
                    entries.subList(offset, Math.min(entries.size(), offset + settings.batchSize()));
            Map<String, String> response = requestBatch(client, settings, batch);
            for (Map.Entry<String, String> entry : batch) {
                String translated = response.get(entry.getKey());
                if (translated != null && !translated.isBlank()) result.put(entry.getKey(), translated);
            }
        }
        return result;
    }

    private static Map<String, String> requestBatch(
            AiChatClient client,
            WtemConfig.AiTranslation settings,
            List<Map.Entry<String, String>> batch) {
        JsonObject values = new JsonObject();
        batch.forEach(entry -> values.addProperty(entry.getKey(), entry.getValue()));
        String prompt =
                settings
                        .translationPrompt()
                        .replace("{target_language}", settings.targetLanguage());
        return parseObject(client.complete(prompt, values.toString(), 0.2));
    }

    private static Map<String, String> parseObject(String content) {
        JsonObject json = AiChatClient.parseJsonObject(content);
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : json.entrySet()) {
            if (entry.getValue().isJsonPrimitive() && entry.getValue().getAsJsonPrimitive().isString()) {
                result.put(entry.getKey(), entry.getValue().getAsString());
            }
        }
        return result;
    }

    private static JsonObject toJson(Map<String, String> source, Map<String, String> translated) {
        JsonObject result = new JsonObject();
        source.forEach((key, value) -> result.addProperty(key, translated.getOrDefault(key, value)));
        return result;
    }
}
