package me.fengming.wtem.common.core.extraction.ai;

import com.google.gson.JsonObject;
import java.util.LinkedHashMap;
import java.util.Map;
import me.fengming.wtem.common.Wtem;
import me.fengming.wtem.common.config.WtemConfig;
import me.fengming.wtem.common.core.extraction.service.ExtractionSession;

/** On-demand semantic key naming with caching, validation, and a run-scoped circuit breaker.
 *
 * @author FengMing
 * */
public final class AiKeyNamer {
    private static final int MAX_KEY_LENGTH = 160;

    private final WtemConfig.AiTranslation settings;
    private final ExtractionSession session;
    private final AiChatClient client;
    private final Map<String, String> cache = new LinkedHashMap<>();
    private boolean disabled;
    private boolean warnedMissingKey;

    public AiKeyNamer(WtemConfig.AiTranslation settings, ExtractionSession session) {
        this.settings = settings;
        this.session = session;
        this.client = settings.apiKey().isBlank() ? null : new AiChatClient(settings);
    }

    /** Returns null when AI naming is unavailable, invalid, or disabled for the rest of this run. */
    public String suggest(String path, String text) {
        if (this.disabled || text == null || text.isBlank()) return null;
        if (this.client == null) {
            if (!this.warnedMissingKey) {
                this.warnedMissingKey = true;
                Wtem.LOGGER.warn(
                        "AI key naming is selected but no API key is configured; using structured keys");
            }
            return null;
        }

        String cacheKey = path + '\u0000' + text;
        if (this.cache.containsKey(cacheKey)) return this.cache.get(cacheKey);
        try {
            JsonObject input = new JsonObject();
            input.addProperty("text", text);
            input.addProperty("suggested_path", path);
            String response =
                    this.client.complete(
                            this.settings.keyNamingPrompt(), input.toString(), 0.1);
            JsonObject result = AiChatClient.parseJsonObject(response);
            String key = result.has("key") ? result.get("key").getAsString().trim() : "";
            if (!validKey(key)) {
                throw new IllegalStateException("AI key naming returned an invalid key");
            }
            this.cache.put(cacheKey, key);
            return key;
        } catch (RuntimeException exception) {
            // Stop after the first failure so an unavailable service cannot multiply the extraction
            // time by one timeout per text entry. The structured path remains deterministic.
            this.disabled = true;
            if (this.session != null) {
                this.session.diagnostics().record("ai_key_naming", path, exception);
            }
            Wtem.LOGGER.warn(
                    "AI key naming failed; structured keys will be used for the rest of this run",
                    exception);
            return null;
        }
    }

    private static boolean validKey(String key) {
        return !key.isBlank()
                && key.length() <= MAX_KEY_LENGTH
                && key.matches("[a-z0-9_]+(?:\\.[a-z0-9_]+)*");
    }
}
