package me.fengming.wtem.common.core.extraction.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import me.fengming.wtem.common.config.WtemConfig;

/** Minimal OpenAI-compatible chat-completions client shared by translation and key naming.
 *
 * @author FengMing
 * */
public final class AiChatClient {
    private final WtemConfig.AiTranslation settings;
    private final HttpClient client;

    public AiChatClient(WtemConfig.AiTranslation settings) {
        this.settings = settings;
        this.client =
                HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(settings.timeoutSeconds()))
                        .build();
    }

    public String complete(String systemPrompt, String userPrompt, double temperature) {
        JsonArray messages = new JsonArray();
        messages.add(message("system", systemPrompt));
        messages.add(message("user", userPrompt));

        JsonObject request = new JsonObject();
        request.addProperty("model", this.settings.model());
        request.add("messages", messages);
        request.addProperty("temperature", temperature);

        HttpRequest httpRequest =
                HttpRequest.newBuilder(URI.create(this.settings.endpoint()))
                        .timeout(Duration.ofSeconds(this.settings.timeoutSeconds()))
                        .header("Authorization", "Bearer " + this.settings.apiKey())
                        .header("Content-Type", "application/json")
                        .POST(
                                HttpRequest.BodyPublishers.ofString(
                                        request.toString(), StandardCharsets.UTF_8))
                        .build();
        try {
            HttpResponse<String> response =
                    this.client.send(
                            httpRequest,
                            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException(
                        "AI endpoint returned HTTP " + response.statusCode());
            }
            JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
            JsonArray choices = root.getAsJsonArray("choices");
            if (choices == null || choices.isEmpty()) {
                throw new IllegalStateException("AI endpoint returned no choices");
            }
            JsonObject message = choices.get(0).getAsJsonObject().getAsJsonObject("message");
            if (message == null || !message.has("content")) {
                throw new IllegalStateException("AI endpoint returned no message content");
            }
            return message.get("content").getAsString();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("AI request interrupted", exception);
        } catch (Exception exception) {
            if (exception instanceof RuntimeException runtime) throw runtime;
            throw new IllegalStateException("AI request failed", exception);
        }
    }

    public static JsonObject parseJsonObject(String content) {
        if (content == null) throw new IllegalStateException("AI endpoint returned no content");
        String cleaned = content.trim();
        if (cleaned.startsWith("```") && cleaned.endsWith("```")) {
            int newline = cleaned.indexOf('\n');
            cleaned =
                    newline < 0
                            ? cleaned.substring(3, cleaned.length() - 3)
                            : cleaned.substring(newline + 1, cleaned.length() - 3);
        }
        JsonElement json = JsonParser.parseString(cleaned.trim());
        if (!json.isJsonObject()) {
            throw new IllegalStateException("AI response is not a JSON object");
        }
        return json.getAsJsonObject();
    }

    private static JsonObject message(String role, String content) {
        JsonObject message = new JsonObject();
        message.addProperty("role", role);
        message.addProperty("content", content);
        return message;
    }
}
