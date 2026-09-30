package org.log2code.api.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** {@link GeminiClient} against a local {@link HttpServer} that replays SSE fixtures from {@code llm/}. */
class GeminiClientTest {

    private static final String KEY = "test-key-not-a-secret";

    private final JsonMapper mapper = JsonMapper.builder().build();
    private final List<Recorded> requests = new CopyOnWriteArrayList<>();
    private HttpServer server;
    private int status;
    private String responseBody;

    private record Recorded(String method, String path, String query, String apiKeyHeader, String contentType,
            String body) {
    }

    @BeforeEach
    void startServer() throws IOException {
        status = 200;
        responseBody = "";
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String body;
            try (InputStream in = exchange.getRequestBody()) {
                body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            requests.add(new Recorded(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
                exchange.getRequestURI().getQuery(), exchange.getRequestHeaders().getFirst("x-goog-api-key"),
                exchange.getRequestHeaders().getFirst("Content-Type"), body));
            byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", status == 200 ? "text/event-stream" : "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private GeminiClient client(String apiKey) {
        LlmProperties props = new LlmProperties("gemini", "http://127.0.0.1:" + server.getAddress().getPort() + "/v1beta",
            apiKey, "m1", List.of(new LlmProperties.Model("m1", "Model 1")), 0.2, 8192,
            Duration.ofSeconds(2), Duration.ofSeconds(10));
        return new GeminiClient(props, mapper);
    }

    private static LlmRequest request() {
        return new LlmRequest("m1", "Sistemski prompt",
            List.of(new LlmMessage(LlmMessage.Role.USER, "Pitanje"), new LlmMessage(LlmMessage.Role.MODEL, "Odgovor"),
                new LlmMessage(LlmMessage.Role.USER, "Dodatno pitanje")),
            0.2, 8192);
    }

    private static String resource(String name) throws IOException {
        try (InputStream in = GeminiClientTest.class.getResourceAsStream("/llm/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void streamsDeltasInOrderAndReportsTokens() throws Exception {
        responseBody = resource("stream-ok.sse");
        List<String> deltas = new ArrayList<>();

        LlmResult result = client(KEY).stream(request(), deltas::add, () -> false);

        assertThat(deltas).containsExactly("Zdravo", ", svete", "!");
        assertThat(result.finishReason()).isEqualTo("STOP");
        assertThat(result.promptTokens()).isEqualTo(12);
        assertThat(result.outputTokens()).isEqualTo(5);
        assertThat(result.durationMs()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void skipsThoughtParts() throws Exception {
        responseBody = resource("stream-thought.sse");
        List<String> deltas = new ArrayList<>();

        LlmResult result = client(KEY).stream(request(), deltas::add, () -> false);

        assertThat(deltas).containsExactly("OK");
        assertThat(result.finishReason()).isEqualTo("STOP");
        assertThat(result.outputTokens()).isEqualTo(1);
    }

    @Test
    void chunksWithoutContentPartsDoNotBreakTheStream() throws Exception {
        responseBody = resource("stream-final-meta-only.sse");
        List<String> deltas = new ArrayList<>();

        LlmResult result = client(KEY).stream(request(), deltas::add, () -> false);

        assertThat(deltas).containsExactly("OK");
        assertThat(result.finishReason()).isEqualTo("STOP");
        assertThat(result.promptTokens()).isEqualTo(7);
        assertThat(result.outputTokens()).isEqualTo(1);
    }

    @Test
    void mapsHttp429ToRateLimited() throws Exception {
        status = 429;
        responseBody = resource("error-429.json");

        assertThatThrownBy(() -> client(KEY).stream(request(), d -> { }, () -> false))
            .isInstanceOfSatisfying(LlmException.class,
                e -> assertThat(e.kind()).isEqualTo(LlmException.Kind.RATE_LIMITED));
    }

    @Test
    void anErrorEventInsideAnOkStreamIsAnErrorNotADone() throws Exception {
        responseBody = resource("stream-error-midstream-503.sse");
        List<String> deltas = new ArrayList<>();

        assertThatThrownBy(() -> client(KEY).stream(request(), deltas::add, () -> false))
            .isInstanceOfSatisfying(LlmException.class, e -> {
                assertThat(e.kind()).isEqualTo(LlmException.Kind.UPSTREAM);
                assertThat(e.getMessage()).contains("503").contains("high demand");
            });
        assertThat(deltas).containsExactly("Pocetak");
    }

    @Test
    void aQuotaErrorInsideAnOkStreamIsRateLimited() throws Exception {
        responseBody = resource("stream-error-midstream-429.sse");

        assertThatThrownBy(() -> client(KEY).stream(request(), d -> { }, () -> false))
            .isInstanceOfSatisfying(LlmException.class,
                e -> assertThat(e.kind()).isEqualTo(LlmException.Kind.RATE_LIMITED));
    }

    @Test
    void mapsInvalidKeyWithoutLeakingTheKey() throws Exception {
        status = 400;
        responseBody = resource("error-invalid-key.json");

        assertThatThrownBy(() -> client(KEY).stream(request(), d -> { }, () -> false))
            .isInstanceOfSatisfying(LlmException.class, e -> {
                assertThat(e.kind()).isEqualTo(LlmException.Kind.INVALID_KEY);
                assertThat(e.getMessage()).doesNotContain(KEY);
            });
    }

    @Test
    void mapsSafetyFinishReasonToBlocked() throws Exception {
        responseBody = resource("stream-safety.sse");

        assertThatThrownBy(() -> client(KEY).stream(request(), d -> { }, () -> false))
            .isInstanceOfSatisfying(LlmException.class,
                e -> assertThat(e.kind()).isEqualTo(LlmException.Kind.BLOCKED));
    }

    @Test
    void cancelAfterFirstDeltaReturnsCancelled() throws Exception {
        responseBody = resource("stream-ok.sse");
        AtomicInteger deltas = new AtomicInteger();

        LlmResult result = client(KEY).stream(request(), d -> deltas.incrementAndGet(), () -> deltas.get() >= 1);

        assertThat(result.finishReason()).isEqualTo("CANCELLED");
        assertThat(deltas.get()).isEqualTo(1);
    }

    /** A server that answers like a "thinking" model: after {@code headerDelayMs} it sends the headers, then (optionally) one delta, then goes silent. */
    private HttpServer silentServer(long headerDelayMs, boolean sendDelta, long silenceMs) throws IOException {
        HttpServer silent = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        silent.createContext("/", exchange -> {
            try (InputStream in = exchange.getRequestBody()) {
                in.readAllBytes();
            }
            try {
                Thread.sleep(headerDelayMs);
                exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
                exchange.sendResponseHeaders(200, 0);
                if (sendDelta) {
                    exchange.getResponseBody().write(
                        ("data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"Prvi\"}]}}]}\n\n")
                            .getBytes(StandardCharsets.UTF_8));
                }
                exchange.getResponseBody().flush();
                Thread.sleep(silenceMs);
            } catch (InterruptedException | IOException e) {
                // the client hung up: that is what the test wants
            } finally {
                exchange.close();
            }
        });
        silent.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
        silent.start();
        return silent;
    }

    private GeminiClient clientFor(HttpServer target) {
        LlmProperties props = new LlmProperties("gemini", "http://127.0.0.1:" + target.getAddress().getPort() + "/v1beta",
            KEY, "m1", List.of(new LlmProperties.Model("m1", "Model 1")), 0.2, 8192,
            Duration.ofSeconds(2), Duration.ofSeconds(30));
        return new GeminiClient(props, mapper);
    }

    @Test
    void cancelWhileTheModelIsSilentAfterTheHeadersDoesNotWaitForTheNextLine() throws Exception {
        HttpServer silent = silentServer(0, false, 5000);
        try {
            AtomicInteger deltas = new AtomicInteger();
            long started = System.nanoTime();

            LlmResult result = clientFor(silent).stream(request(), d -> deltas.incrementAndGet(),
                () -> (System.nanoTime() - started) / 1_000_000 > 300);

            assertThat(result.finishReason()).isEqualTo("CANCELLED");
            assertThat(deltas.get()).isZero();
            assertThat((System.nanoTime() - started) / 1_000_000).isLessThan(2000);
        } finally {
            silent.stop(0);
        }
    }

    @Test
    void cancelBeforeTheFirstByteDoesNotWaitForTheResponse() throws Exception {
        HttpServer silent = silentServer(5000, false, 0);
        try {
            long started = System.nanoTime();

            LlmResult result = clientFor(silent).stream(request(), d -> { },
                () -> (System.nanoTime() - started) / 1_000_000 > 300);

            assertThat(result.finishReason()).isEqualTo("CANCELLED");
            assertThat((System.nanoTime() - started) / 1_000_000).isLessThan(2000);
        } finally {
            silent.stop(0);
        }
    }

    @Test
    void emptyKeyIsNotConfiguredAndSendsNoRequest() {
        assertThatThrownBy(() -> client("").stream(request(), d -> { }, () -> false))
            .isInstanceOfSatisfying(LlmException.class,
                e -> assertThat(e.kind()).isEqualTo(LlmException.Kind.NOT_CONFIGURED));
        assertThat(requests).isEmpty();
    }

    @Test
    void sendsExpectedPathHeadersAndBody() throws Exception {
        responseBody = resource("stream-ok.sse");

        client(KEY).stream(request(), d -> { }, () -> false);

        assertThat(requests).hasSize(1);
        Recorded sent = requests.get(0);
        assertThat(sent.method()).isEqualTo("POST");
        assertThat(sent.path()).isEqualTo("/v1beta/models/m1:streamGenerateContent");
        assertThat(sent.query()).isEqualTo("alt=sse");
        assertThat(sent.apiKeyHeader()).isEqualTo(KEY);
        assertThat(sent.contentType()).isEqualTo("application/json");

        JsonNode body = mapper.readTree(sent.body());
        assertThat(body.path("systemInstruction").path("parts").path(0).path("text").asString())
            .isEqualTo("Sistemski prompt");
        assertThat(body.path("contents")).hasSize(3);
        assertThat(body.path("contents").path(0).path("role").asString()).isEqualTo("user");
        assertThat(body.path("contents").path(0).path("parts").path(0).path("text").asString()).isEqualTo("Pitanje");
        assertThat(body.path("contents").path(1).path("role").asString()).isEqualTo("model");
        assertThat(body.path("contents").path(2).path("role").asString()).isEqualTo("user");
        assertThat(body.path("generationConfig").path("temperature").asDouble()).isEqualTo(0.2);
        assertThat(body.path("generationConfig").path("maxOutputTokens").asInt()).isEqualTo(8192);
        assertThat(sent.body()).doesNotContain(KEY);
    }
}
