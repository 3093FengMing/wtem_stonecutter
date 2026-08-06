package me.fengming.wtem.common.core.extraction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;
import me.fengming.wtem.common.config.WtemConfig;
import me.fengming.wtem.common.core.extraction.ai.AiKeyNamer;
import me.fengming.wtem.common.core.extraction.service.ExtractionSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** End-to-end coverage for the small OpenAI-compatible semantic key request. */
class AiKeyNamerTest {
    @AfterEach
    void tearDown() {
        TranslationContext.release();
    }

    @Test
    void usesAValidatedSemanticKeyReturnedByTheConfiguredEndpoint() throws Exception {
        try (FakeAiServer server =
                new FakeAiServer(
                        "{\"choices\":[{\"message\":{\"content\":"
                                + "\"{\\\"key\\\":\\\"item.the_best_sword.name\\\"}\"}}]}")) {
            WtemConfig.AiTranslation settings =
                    new WtemConfig.AiTranslation(
                            false,
                            server.baseEndpoint(),
                            "test-key",
                            "test-model",
                            "zh-CN",
                            "zh_cn.json",
                            10,
                            10,
                            "Translate JSON values to {target_language}.",
                            "Semantic key prompt marker");
            TranslationContext.clear();
            TranslationContext.setKeyNaming(
                    new WtemConfig.KeyNaming(WtemConfig.KeyNaming.Scheme.AI, 8));
            TranslationContext.setAiKeyNamer(
                    new AiKeyNamer(settings, new ExtractionSession()));
            TranslationContext.setKey("item.wooden_sword.1.name");

            assertEquals(
                    "item.the_best_sword.name",
                    TranslationContext.addEntry("The Best Sword"));
            assertEquals(
                    "The Best Sword",
                    TranslationContext.snapshot().get("item.the_best_sword.name"));
            assertTrue(server.requestBody().contains("Semantic key prompt marker"));
            assertTrue(server.requestBody().contains("item.wooden_sword.1.name"));
            assertTrue(server.requestBody().contains("The Best Sword"));
            assertTrue(server.requestHeaders().startsWith("POST /v1/chat/completions "));
        }
    }

    @Test
    void readsTheResponsesProtocolOutputText() throws Exception {
        try (FakeAiServer server =
                new FakeAiServer("{\"output_text\":\"{\\\"key\\\":\\\"item.responses.name\\\"}\"}")) {
            WtemConfig.AiTranslation defaults = WtemConfig.AiTranslation.DEFAULT;
            WtemConfig.AiTranslation settings =
                    new WtemConfig.AiTranslation(
                            false,
                            server.baseEndpoint(),
                            "test-key",
                            "test-model",
                            "zh-CN",
                            "zh_cn.json",
                            10,
                            10,
                            defaults.translationPrompt(),
                            defaults.keyNamingPrompt(),
                            WtemConfig.AiTranslation.Protocol.RESPONSES);
            TranslationContext.clear();
            TranslationContext.setKeyNaming(
                    new WtemConfig.KeyNaming(WtemConfig.KeyNaming.Scheme.AI, 8));
            TranslationContext.setAiKeyNamer(
                    new AiKeyNamer(settings, new ExtractionSession()));
            TranslationContext.setKey("item.paper.1.name");

            assertEquals("item.responses.name", TranslationContext.addEntry("Responses"));
            assertTrue(server.requestHeaders().startsWith("POST /v1/responses "));
            assertTrue(server.requestBody().contains("\"input\""));
            assertTrue(!server.requestBody().contains("\"messages\""));
        }
    }

    /** Minimal one-request HTTP/1.1 server; avoids adding a test dependency or JDK module. */
    private static final class FakeAiServer implements AutoCloseable {
        private final ServerSocket server;
        private final Thread worker;
        private final String response;
        private final AtomicReference<Throwable> failure = new AtomicReference<>();
        private volatile String requestBody = "";
        private volatile String requestHeaders = "";

        private FakeAiServer(String response) throws IOException {
            this.response = response;
            this.server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
            this.worker = new Thread(this::serve, "wtem-ai-test-server");
            this.worker.setDaemon(true);
            this.worker.start();
        }

        String baseEndpoint() {
            return "http://127.0.0.1:" + this.server.getLocalPort() + "/v1";
        }

        String requestBody() {
            Throwable throwable = this.failure.get();
            if (throwable != null) throw new AssertionError(throwable);
            return this.requestBody;
        }

        String requestHeaders() {
            Throwable throwable = this.failure.get();
            if (throwable != null) throw new AssertionError(throwable);
            return this.requestHeaders;
        }

        private void serve() {
            try (Socket socket = this.server.accept()) {
                InputStream input = socket.getInputStream();
                String headers = readHeaders(input);
                this.requestHeaders = headers;
                int contentLength = contentLength(headers);
                this.requestBody =
                        new String(input.readNBytes(contentLength), StandardCharsets.UTF_8);

                byte[] body = this.response.getBytes(StandardCharsets.UTF_8);
                OutputStream output = socket.getOutputStream();
                output.write(
                        ("HTTP/1.1 200 OK\r\n"
                                        + "Content-Type: application/json\r\n"
                                        + "Content-Length: "
                                        + body.length
                                        + "\r\nConnection: close\r\n\r\n")
                                .getBytes(StandardCharsets.US_ASCII));
                output.write(body);
                output.flush();
            } catch (Throwable throwable) {
                if (!this.server.isClosed()) this.failure.set(throwable);
            }
        }

        private static String readHeaders(InputStream input) throws IOException {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            int matched = 0;
            while (matched < 4) {
                int next = input.read();
                if (next < 0) throw new IOException("Unexpected end of HTTP headers");
                bytes.write(next);
                int expected = switch (matched) {
                    case 0, 2 -> '\r';
                    case 1, 3 -> '\n';
                    default -> -1;
                };
                matched = next == expected ? matched + 1 : next == '\r' ? 1 : 0;
            }
            return bytes.toString(StandardCharsets.US_ASCII);
        }

        private static int contentLength(String headers) throws IOException {
            for (String line : headers.lines().toList()) {
                String lower = line.toLowerCase(Locale.ROOT);
                if (lower.startsWith("content-length:")) {
                    return Integer.parseInt(line.substring(line.indexOf(':') + 1).trim());
                }
            }
            throw new IOException("Missing Content-Length header");
        }

        @Override
        public void close() throws Exception {
            this.server.close();
            this.worker.join(2_000);
            Throwable throwable = this.failure.get();
            if (throwable != null) throw new AssertionError(throwable);
        }
    }
}
