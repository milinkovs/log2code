package org.log2code.api.llm;

import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Google Gemini over plain HTTP ({@code streamGenerateContent?alt=sse}), without an SDK (T40 step 4, ADR-038).
 * The API key is sent only in the {@code x-goog-api-key} header and is scrubbed from every exception message.
 */
public class GeminiClient implements LlmClient {

    private static final Set<String> BLOCKING_FINISH_REASONS =
        Set.of("SAFETY", "PROHIBITED_CONTENT", "BLOCKLIST", "SPII");

    /** How often the cancel watcher looks at the flag; the worst-case delay of a cancel. */
    private static final long CANCEL_POLL_MS = 50;

    private final LlmProperties properties;
    private final JsonMapper mapper;
    private final HttpClient http;

    public GeminiClient(LlmProperties properties, JsonMapper mapper) {
        this.properties = properties;
        this.mapper = mapper;
        this.http = HttpClient.newBuilder().connectTimeout(properties.connectTimeout()).build();
    }

    @Override
    public LlmResult stream(LlmRequest request, Consumer<String> onDelta, BooleanSupplier cancelled)
            throws LlmException {
        if (!properties.configured()) {
            throw new LlmException(LlmException.Kind.NOT_CONFIGURED, "Gemini API key is not configured");
        }
        long started = System.nanoTime();
        HttpRequest httpRequest = HttpRequest.newBuilder(uri(request.model()))
            .timeout(properties.requestTimeout())
            .header("x-goog-api-key", properties.apiKey())
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body(request), StandardCharsets.UTF_8))
            .build();
        CompletableFuture<HttpResponse<Stream<String>>> pending =
            http.sendAsync(httpRequest, HttpResponse.BodyHandlers.ofLines());
        AtomicReference<Stream<String>> open = new AtomicReference<>();
        AtomicBoolean finished = new AtomicBoolean();
        Thread watcher = Thread.ofVirtual().name("gemini-cancel-watcher").start(
            () -> watchForCancel(cancelled, pending, open, finished));
        try {
            HttpResponse<Stream<String>> response = pending.get();
            try (Stream<String> lines = response.body()) {
                open.set(lines);
                if (response.statusCode() != 200) {
                    throw httpError(response.statusCode(), lines.collect(Collectors.joining("\n")));
                }
                return read(lines.iterator(), onDelta, cancelled, started);
            }
        } catch (CancellationException e) {
            return result("CANCELLED", null, null, started);
        } catch (ExecutionException e) {
            if (cancelled.getAsBoolean()) {
                return result("CANCELLED", null, null, started);
            }
            throw ioFailure(e.getCause());
        } catch (UncheckedIOException e) {
            if (cancelled.getAsBoolean()) {
                return result("CANCELLED", null, null, started);
            }
            throw ioFailure(e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmException(LlmException.Kind.UPSTREAM, "Gemini request was interrupted", e);
        } finally {
            finished.set(true);
            watcher.interrupt();
        }
    }

    /**
     * A blocked read cannot see {@code cancelled}, and a "thinking" model can stay silent for seconds, so a
     * watcher thread polls the flag and, once it is set, cancels the pending request or closes the open
     * stream, which makes the blocked read return (T42, step 4).
     */
    private static void watchForCancel(BooleanSupplier cancelled, CompletableFuture<?> pending,
            AtomicReference<Stream<String>> open, AtomicBoolean finished) {
        while (!finished.get()) {
            if (cancelled.getAsBoolean()) {
                pending.cancel(true);
                Stream<String> stream = open.get();
                if (stream != null) {
                    stream.close();
                }
            }
            try {
                Thread.sleep(CANCEL_POLL_MS);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    private LlmResult read(Iterator<String> lines, Consumer<String> onDelta, BooleanSupplier cancelled, long started) {
        String finishReason = null;
        Integer promptTokens = null;
        Integer outputTokens = null;
        while (lines.hasNext()) {
            String line = lines.next();
            if (cancelled.getAsBoolean()) {
                return result("CANCELLED", promptTokens, outputTokens, started);
            }
            if (!line.startsWith("data:")) {
                continue;
            }
            String json = line.substring("data:".length()).strip();
            JsonNode chunk = parse(json);
            if (chunk.has("error")) {
                // Gemini reports overload and quota problems inside a stream that already answered HTTP 200
                throw httpError(chunk.path("error").path("code").asInt(500), json);
            }
            String blockReason = chunk.path("promptFeedback").path("blockReason").asString(null);
            if (blockReason != null) {
                throw new LlmException(LlmException.Kind.BLOCKED, "Gemini blocked the prompt: " + blockReason);
            }
            JsonNode candidate = chunk.path("candidates").path(0);
            for (JsonNode part : candidate.path("content").path("parts")) {
                if (part.path("thought").asBoolean(false)) {
                    continue;
                }
                String text = part.path("text").asString(null);
                if (text != null && !text.isEmpty()) {
                    onDelta.accept(text);
                }
            }
            String reason = candidate.path("finishReason").asString(null);
            if (reason != null) {
                if (BLOCKING_FINISH_REASONS.contains(reason)) {
                    throw new LlmException(LlmException.Kind.BLOCKED, "Gemini stopped the answer: " + reason);
                }
                finishReason = reason;
            }
            JsonNode usage = chunk.path("usageMetadata");
            if (usage.has("promptTokenCount")) {
                promptTokens = usage.path("promptTokenCount").asInt();
            }
            if (usage.has("candidatesTokenCount")) {
                outputTokens = usage.path("candidatesTokenCount").asInt();
            }
        }
        // the watcher closing the stream also ends the loop, so a cancel is reported as such
        if (cancelled.getAsBoolean()) {
            return result("CANCELLED", promptTokens, outputTokens, started);
        }
        return result(finishReason == null ? "UNKNOWN" : finishReason, promptTokens, outputTokens, started);
    }

    private LlmException httpError(int status, String body) {
        String apiStatus = null;
        String message = null;
        try {
            JsonNode error = mapper.readTree(body).path("error");
            apiStatus = error.path("status").asString(null);
            message = error.path("message").asString(null);
        } catch (JacksonException e) {
            // not JSON: fall through to the generic message
        }
        String detail = scrub(message == null ? "no error message" : message);
        if (status == 401 || status == 403 || (status == 400 && isInvalidKey(apiStatus, body))) {
            return new LlmException(LlmException.Kind.INVALID_KEY, "Gemini rejected the API key (HTTP " + status + ")");
        }
        if (status == 429) {
            return new LlmException(LlmException.Kind.RATE_LIMITED, "Gemini rate limit or quota exceeded: " + detail);
        }
        return new LlmException(LlmException.Kind.UPSTREAM, "Gemini returned HTTP " + status + ": " + detail);
    }

    private static boolean isInvalidKey(String apiStatus, String body) {
        return "API_KEY_INVALID".equals(apiStatus) || body.contains("API_KEY_INVALID");
    }

    private LlmException ioFailure(Throwable cause) {
        if (cause instanceof HttpTimeoutException) {
            return new LlmException(LlmException.Kind.TIMEOUT, "Gemini request timed out", cause);
        }
        return new LlmException(LlmException.Kind.UPSTREAM,
            "Gemini request failed: " + scrub(String.valueOf(cause.getMessage())), cause);
    }

    private JsonNode parse(String json) {
        try {
            return mapper.readTree(json);
        } catch (JacksonException e) {
            throw new LlmException(LlmException.Kind.UPSTREAM, "Gemini sent an unreadable stream event", e);
        }
    }

    private String scrub(String text) {
        return text.replace(properties.apiKey(), "***");
    }

    private URI uri(String model) {
        String encoded = URLEncoder.encode(model, StandardCharsets.UTF_8);
        return URI.create(properties.baseUrl() + "/models/" + encoded + ":streamGenerateContent?alt=sse");
    }

    private String body(LlmRequest request) {
        ObjectNode root = mapper.createObjectNode();
        if (request.systemPrompt() != null && !request.systemPrompt().isEmpty()) {
            root.putObject("systemInstruction").putArray("parts").addObject().put("text", request.systemPrompt());
        }
        ArrayNode contents = root.putArray("contents");
        for (LlmMessage message : request.messages()) {
            ObjectNode content = contents.addObject();
            content.put("role", message.role() == LlmMessage.Role.USER ? "user" : "model");
            content.putArray("parts").addObject().put("text", message.text());
        }
        ObjectNode config = root.putObject("generationConfig");
        config.put("temperature", request.temperature());
        config.put("maxOutputTokens", request.maxOutputTokens());
        return mapper.writeValueAsString(root);
    }

    private static LlmResult result(String finishReason, Integer promptTokens, Integer outputTokens, long started) {
        return new LlmResult(finishReason, promptTokens, outputTokens, (System.nanoTime() - started) / 1_000_000);
    }
}
