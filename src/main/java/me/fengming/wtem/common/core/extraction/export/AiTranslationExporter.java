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

    /** Counts exactly the entries that the single catalog request will send to the endpoint. */
    public static int countTranslatableEntries(Map<String, String> source) {
        if (source == null || source.isEmpty()) return 0;
        int count = 0;
        for (Map.Entry<String, String> entry : source.entrySet()) {
            if (entry.getKey() != null
                    && entry.getValue() != null
                    && !entry.getValue().isBlank()) {
                count++;
            }
        }
        return count;
    }

    /** Counts the requests needed by the translation loop. */
    public static int countBatches(
            WtemConfig.AiTranslation settings, Map<String, String> source) {
        int entries = countTranslatableEntries(source);
        // Translation is deliberately one request for the complete catalog.  Keep this method for
        // progress/reporting source compatibility, but its non-empty result is always one request.
        return entries == 0 || settings == null ? 0 : 1;
    }

    public static boolean export(
            WtemConfig.AiTranslation settings,
            Path output,
            Map<String, String> source,
            ExtractionSession session) {
        if (settings == null || !settings.enabled() || source == null) return false;
        try {
            if (session.isCancellationRequested()) return false;
            Map<String, String> translated;
            if (settings.apiKey().isBlank()) {
                // A missing credential should not make the optional exporter destructive or
                // prevent a resource pack from being produced.  Publish an exact copy of the
                // primary catalog and leave a concise diagnostic in the log; the key itself is
                // never included in that message.
                Wtem.LOGGER.warn(
                        "AI translation is enabled but no API key is configured; exporting the source catalog unchanged");
                translated = new LinkedHashMap<>(source);
                session.recordAiBatch(
                        countTranslatableEntries(source), countBatches(settings, source));
            } else {
                try {
                    translated = translate(settings, source, session);
                    if (translated == null) return false;
                } catch (RuntimeException exception) {
                    // Keep the optional exporter non-destructive.  A partial set of AI responses
                    // is deliberately discarded so one transient request failure cannot create a
                    // mixed, misleading target-language catalog.
                    session.diagnostics().record("ai_translation", output.toString(), exception);
                    Wtem.LOGGER.warn(
                            "AI translation request failed; exporting the source catalog unchanged",
                            exception);
                    translated = new LinkedHashMap<>(source);
                }
            }
            if (session.isCancellationRequested()) return false;
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
            WtemConfig.AiTranslation settings,
            Map<String, String> source,
            ExtractionSession session) {
        AiChatClient client = new AiChatClient(settings);
        Map<String, String> result = new LinkedHashMap<>(source);
        List<Map.Entry<String, String>> entries = translatableEntries(source);
        if (entries.isEmpty()) return result;
        if (session.isCancellationRequested()) return null;

        // Send the complete catalog at once.  This avoids serial or parallel batch overhead and
        // lets the provider optimize the whole JSON translation in one generation.  The endpoint's
        // context limit remains the caller's responsibility; a failed request is handled by the
        // outer non-destructive fallback.
        Map<String, String> response = requestAll(client, settings, entries);
        for (Map.Entry<String, String> entry : entries) {
            String translated = response.get(entry.getKey());
            if (translated != null && !translated.isBlank()) {
                result.put(entry.getKey(), translated);
            }
        }
        session.recordAiBatch(entries.size(), 1);
        return result;
    }

    private static List<Map.Entry<String, String>> translatableEntries(
            Map<String, String> source) {
        if (source == null || source.isEmpty()) return List.of();
        return source.entrySet().stream()
                .filter(entry -> entry.getKey() != null && entry.getValue() != null)
                .filter(entry -> !entry.getValue().isBlank())
                .toList();
    }

    private static Map<String, String> requestAll(
            AiChatClient client,
            WtemConfig.AiTranslation settings,
            List<Map.Entry<String, String>> entries) {
        JsonObject values = new JsonObject();
        entries.forEach(entry -> values.addProperty(entry.getKey(), entry.getValue()));
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
