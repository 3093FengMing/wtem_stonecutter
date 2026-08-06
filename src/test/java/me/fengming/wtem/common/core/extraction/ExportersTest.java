package me.fengming.wtem.common.core.extraction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipFile;
import me.fengming.wtem.common.config.WtemConfig;
import me.fengming.wtem.common.core.extraction.export.AiTranslationExporter;
import me.fengming.wtem.common.core.extraction.export.ResourcePackExporter;
import me.fengming.wtem.common.core.extraction.service.ExtractionSession;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ExportersTest {
    @TempDir Path temporaryDirectory;

    @Test
    void aiTranslationWithoutACredentialPublishesANondestructiveSourceCopy() throws Exception {
        WtemConfig.AiTranslation defaults = WtemConfig.AiTranslation.DEFAULT;
        WtemConfig.AiTranslation settings =
                new WtemConfig.AiTranslation(
                        true,
                        defaults.endpoint(),
                        "",
                        defaults.model(),
                        "ko-KR",
                        "ko_kr.json",
                        defaults.batchSize(),
                        defaults.timeoutSeconds(),
                        defaults.translationPrompt(),
                        defaults.keyNamingPrompt());
        Path output = this.temporaryDirectory.resolve("ko_kr.json");
        ExtractionSession session = new ExtractionSession();
        session.start();
        session.beginAiTranslation(1, 1);

        assertTrue(
                AiTranslationExporter.export(
                        settings,
                        output,
                        Map.of("item.best.name", "The Best Sword"),
                        session));

        assertEquals(
                "The Best Sword",
                JsonParser.parseString(Files.readString(output))
                        .getAsJsonObject()
                        .get("item.best.name")
                        .getAsString());
        assertEquals(1, session.report().modifiedResources());
        assertEquals(1, session.aiTranslationProgress().completedEntries());
        assertEquals(1, session.aiTranslationProgress().completedBatches());
    }

    @Test
    void countsOnlyNonBlankCatalogEntriesForAiBatches() {
        WtemConfig.AiTranslation settings = WtemConfig.AiTranslation.DEFAULT;
        Map<String, String> source = new java.util.LinkedHashMap<>();
        source.put("a", "A");
        source.put("blank", " ");
        source.put("null-value", null);

        assertEquals(1, AiTranslationExporter.countTranslatableEntries(source));
        assertEquals(1, AiTranslationExporter.countBatches(settings, source));
    }

    @Test
    void sendsTheCompleteCatalogInOneRequest() throws Exception {
        try (ConcurrentAiServer server = new ConcurrentAiServer(1)) {
            WtemConfig.AiTranslation defaults = WtemConfig.AiTranslation.DEFAULT;
            WtemConfig.AiTranslation settings =
                    new WtemConfig.AiTranslation(
                            true,
                            server.endpoint(),
                            "test-key",
                            "test-model",
                            "zh-CN",
                            "translated.json",
                            1,
                            10,
                            defaults.translationPrompt(),
                            defaults.keyNamingPrompt());
            Map<String, String> source =
                    new LinkedHashMap<>(Map.of("a", "A", "b", "B", "c", "C"));
            Path output = this.temporaryDirectory.resolve("translated.json");
            ExtractionSession session = new ExtractionSession();
            assertTrue(session.start());
            assertTrue(session.beginAiTranslation(3, 1));

            assertTrue(AiTranslationExporter.export(settings, output, source, session));
            var translated = JsonParser.parseString(Files.readString(output)).getAsJsonObject();
            assertEquals("A translated", translated.get("a").getAsString());
            assertEquals("B translated", translated.get("b").getAsString());
            assertEquals("C translated", translated.get("c").getAsString());
            assertEquals(1, server.requestCount.get(), "the complete catalog must use one request");
            assertEquals(1, server.maxActive.get(), "the complete catalog must use one request");
            assertEquals(3, session.aiTranslationProgress().completedEntries());
            assertEquals(1, session.aiTranslationProgress().completedBatches());
        }
    }

    @Test
    void resourcePackExporterPublishesMatchingFolderAndZipOutputs() throws Exception {
        Path language = this.temporaryDirectory.resolve("en_us.json");
        Path translated = this.temporaryDirectory.resolve("zh_cn.json");
        Files.writeString(language, "{\"example.title\":\"Example\"}");
        Files.writeString(translated, "{\"example.title\":\"示例\"}");
        WtemConfig.ResourcePack settings =
                new WtemConfig.ResourcePack(
                        true,
                        WtemConfig.ResourcePack.Format.BOTH,
                        "translations",
                        "Translation test",
                        42);
        ExtractionSession session = new ExtractionSession();

        assertTrue(
                ResourcePackExporter.export(
                        settings,
                        this.temporaryDirectory,
                        language,
                        translated,
                        session));

        Path outputDirectory = ResourcePackExporter.outputBaseDirectory(this.temporaryDirectory);
        Path expectedOutputDirectory = this.temporaryDirectory.toAbsolutePath().normalize();
        //? if >=26.1
        expectedOutputDirectory = expectedOutputDirectory.resolve("resourcepacks");
        assertEquals(expectedOutputDirectory, outputDirectory);
        Path folder = outputDirectory.resolve("translations");
        assertTrue(Files.isRegularFile(folder.resolve("pack.mcmeta")));
        assertTrue(Files.isRegularFile(folder.resolve("assets/wtem/lang/en_us.json")));
        assertTrue(Files.isRegularFile(folder.resolve("assets/wtem/lang/zh_cn.json")));
        assertEquals(
                42,
                JsonParser.parseString(Files.readString(folder.resolve("pack.mcmeta")))
                        .getAsJsonObject()
                        .getAsJsonObject("pack")
                        .get("pack_format")
                        .getAsInt());

        try (ZipFile zip =
                new ZipFile(outputDirectory.resolve("translations.zip").toFile())) {
            Set<String> entries = new TreeSet<>();
            zip.stream().forEach(entry -> entries.add(entry.getName()));
            assertEquals(
                    Set.of(
                            "pack.mcmeta",
                            "assets/wtem/lang/en_us.json",
                            "assets/wtem/lang/zh_cn.json"),
                    entries);
        }
        assertEquals(2, session.report().modifiedResources());
    }

    /** Small delayed server: overlapping responses make serialized requests observable. */
    private static final class ConcurrentAiServer implements AutoCloseable {
        private final ServerSocket server;
        private final Thread acceptor;
        private final List<Thread> workers = new ArrayList<>();
        private final AtomicInteger active = new AtomicInteger();
        private final AtomicInteger maxActive = new AtomicInteger();
        private final AtomicInteger requestCount = new AtomicInteger();

        private ConcurrentAiServer(int expectedRequests) throws IOException {
            this.server = new ServerSocket(0, expectedRequests, InetAddress.getLoopbackAddress());
            this.acceptor = new Thread(this::accept, "wtem-ai-concurrency-test");
            this.acceptor.setDaemon(true);
            this.acceptor.start();
        }

        String endpoint() {
            return "http://127.0.0.1:" + this.server.getLocalPort() + "/v1/chat/completions";
        }

        private void accept() {
            try {
                while (!server.isClosed()) {
                    Socket socket = server.accept();
                    Thread worker = new Thread(() -> serve(socket), "wtem-ai-request-test");
                    worker.setDaemon(true);
                    synchronized (workers) {
                        workers.add(worker);
                    }
                    worker.start();
                }
            } catch (IOException exception) {
                if (!server.isClosed()) throw new AssertionError(exception);
            }
        }

        private void serve(Socket socket) {
            this.requestCount.incrementAndGet();
            int current = active.incrementAndGet();
            maxActive.accumulateAndGet(current, Math::max);
            try (socket) {
                InputStream input = socket.getInputStream();
                String headers = readHeaders(input);
                if (headers == null) return;
                int length = contentLength(headers);
                input.readNBytes(length);
                Thread.sleep(250);
                String content =
                        "{\"choices\":[{\"message\":{\"content\":\"{\\\"a\\\":\\\"A translated\\\",\\\"b\\\":\\\"B translated\\\",\\\"c\\\":\\\"C translated\\\"}\"}}]}";
                byte[] body = content.getBytes(StandardCharsets.UTF_8);
                OutputStream output = socket.getOutputStream();
                output.write(
                        ("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: "
                                        + body.length
                                        + "\r\nConnection: close\r\n\r\n")
                                .getBytes(StandardCharsets.US_ASCII));
                output.write(body);
                output.flush();
            } catch (Exception exception) {
                throw new AssertionError(exception);
            } finally {
                active.decrementAndGet();
            }
        }

        private static String readHeaders(InputStream input) throws IOException {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            int matched = 0;
            while (matched < 4) {
                int next = input.read();
                if (next < 0) return null;
                bytes.write(next);
                matched =
                        switch (matched) {
                            case 0, 2 -> next == '\r' ? matched + 1 : 0;
                            case 1, 3 -> next == '\n' ? matched + 1 : next == '\r' ? 1 : 0;
                            default -> 0;
                        };
            }
            return bytes.toString(StandardCharsets.US_ASCII);
        }

        private static int contentLength(String headers) throws IOException {
            for (String line : headers.lines().toList()) {
                if (line.toLowerCase(Locale.ROOT).startsWith("content-length:")) {
                    return Integer.parseInt(line.substring(line.indexOf(':') + 1).trim());
                }
            }
            throw new IOException("Missing Content-Length");
        }

        @Override
        public void close() throws Exception {
            server.close();
            acceptor.join(2_000);
            synchronized (workers) {
                for (Thread worker : workers) worker.join(2_000);
            }
        }
    }
}
