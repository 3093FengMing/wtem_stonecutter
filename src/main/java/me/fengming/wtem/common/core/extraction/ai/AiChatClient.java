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

/** Minimal OpenAI-compatible Responses/Chat Completions client shared by translation and key naming.
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
        JsonObject request = new JsonObject();
        request.addProperty("model", this.settings.model());
        request.addProperty("temperature", temperature);
        WtemConfig.AiTranslation.Protocol protocol = effectiveProtocol();
        if (protocol == WtemConfig.AiTranslation.Protocol.RESPONSES) {
            JsonArray input = new JsonArray();
            input.add(inputMessage("system", systemPrompt));
            input.add(inputMessage("user", userPrompt));
            request.add("input", input);
        } else {
            JsonArray messages = new JsonArray();
            messages.add(message("system", systemPrompt));
            messages.add(message("user", userPrompt));
            request.add("messages", messages);
        }

        HttpRequest httpRequest =
                HttpRequest.newBuilder(endpointUri(protocol))
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
            return extractContent(root, protocol);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("AI request interrupted", exception);
        } catch (Exception exception) {
            if (exception instanceof RuntimeException runtime) throw runtime;
            throw new IllegalStateException("AI request failed", exception);
        }
    }

    /** Accepts a complete protocol URL or an OpenAI-compatible API base URL. */
    private URI endpointUri(WtemConfig.AiTranslation.Protocol protocol) {
        String endpoint = this.settings.endpoint().trim();
        while (endpoint.endsWith("/")) {
            endpoint = endpoint.substring(0, endpoint.length() - 1);
        }
        if (!endpoint.endsWith("/chat/completions") && !endpoint.endsWith("/responses")) {
            endpoint +=
                    protocol == WtemConfig.AiTranslation.Protocol.RESPONSES
                            ? "/responses"
                            : "/chat/completions";
        }
        return URI.create(endpoint);
    }

    private WtemConfig.AiTranslation.Protocol effectiveProtocol() {
        String endpoint = this.settings.endpoint().trim();
        while (endpoint.endsWith("/")) endpoint = endpoint.substring(0, endpoint.length() - 1);
        if (endpoint.endsWith("/responses")) return WtemConfig.AiTranslation.Protocol.RESPONSES;
        if (endpoint.endsWith("/chat/completions")) {
            return WtemConfig.AiTranslation.Protocol.CHAT_COMPLETIONS;
        }
        return this.settings.protocol();
    }

    private static String extractContent(
            JsonObject root, WtemConfig.AiTranslation.Protocol protocol) {
        if (protocol == WtemConfig.AiTranslation.Protocol.RESPONSES) {
            JsonElement direct = root.get("output_text");
            if (direct != null && direct.isJsonPrimitive() && direct.getAsJsonPrimitive().isString()) {
                return direct.getAsString();
            }
            JsonElement output = root.get("output");
            if (output != null && output.isJsonArray()) {
                for (JsonElement item : output.getAsJsonArray()) {
                    if (!item.isJsonObject()) continue;
                    JsonElement content = item.getAsJsonObject().get("content");
                    if (content == null || !content.isJsonArray()) continue;
                    for (JsonElement part : content.getAsJsonArray()) {
                        if (!part.isJsonObject()) continue;
                        JsonElement text = part.getAsJsonObject().get("text");
                        if (text != null
                                && text.isJsonPrimitive()
                                && text.getAsJsonPrimitive().isString()) {
                            return text.getAsString();
                        }
                    }
                }
            }
            throw new IllegalStateException("AI Responses endpoint returned no output text");
        }

        JsonArray choices = root.getAsJsonArray("choices");
        if (choices == null || choices.isEmpty()) {
            throw new IllegalStateException("AI endpoint returned no choices");
        }
        JsonObject message = choices.get(0).getAsJsonObject().getAsJsonObject("message");
        if (message == null || !message.has("content")) {
            throw new IllegalStateException("AI endpoint returned no message content");
        }
        return message.get("content").getAsString();
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

    private static JsonObject inputMessage(String role, String content) {
        JsonObject message = new JsonObject();
        message.addProperty("role", role);
        JsonArray parts = new JsonArray();
        JsonObject text = new JsonObject();
        text.addProperty("type", "input_text");
        text.addProperty("text", content);
        parts.add(text);
        message.add("content", parts);
        return message;
    }
}
