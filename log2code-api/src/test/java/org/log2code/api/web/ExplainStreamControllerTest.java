package org.log2code.api.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.log2code.api.llm.LlmClient;
import org.log2code.api.llm.LlmException;
import org.log2code.api.llm.LlmMessage;
import org.log2code.api.llm.LlmProperties;
import org.log2code.api.llm.LlmRequest;
import org.log2code.api.llm.LlmResult;
import org.log2code.api.llm.explain.ExplainLevel;
import org.log2code.api.llm.explain.ExplainPrompt;
import org.log2code.api.llm.explain.ExplainPromptService;
import org.log2code.api.llm.explain.ExplainSection;
import org.log2code.api.llm.explain.ExplainStreamService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockAsyncContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code @WebMvcTest} for {@link ExplainStreamController} with the real {@link ExplainStreamService}, a fake
 * {@link LlmClient} and a mocked prompt service (T42 step 7).
 */
@WebMvcTest(ExplainStreamController.class)
@Import(ExplainStreamControllerTest.Config.class)
class ExplainStreamControllerTest {

    private static final String SECRET = "secret-key-value";

    /** Scriptable stand-in for Gemini: records the request and plays a script into {@code onDelta}. */
    static class FakeLlmClient implements LlmClient {
        volatile LlmRequest lastRequest;
        volatile Script script;
        final CountDownLatch started = new CountDownLatch(1);
        final CountDownLatch sawCancel = new CountDownLatch(1);

        @FunctionalInterface
        interface Script {
            LlmResult play(Consumer<String> onDelta, BooleanSupplier cancelled) throws InterruptedException;
        }

        @Override
        public LlmResult stream(LlmRequest request, Consumer<String> onDelta, BooleanSupplier cancelled) {
            lastRequest = request;
            started.countDown();
            try {
                return script.play(onDelta, cancelled);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
    }

    /** Runs stream tasks inline (deterministic) unless a test switches to a real thread. */
    static class SwitchableExecutor implements Executor {
        volatile Executor delegate = Runnable::run;

        @Override
        public void execute(Runnable command) {
            delegate.execute(command);
        }
    }

    @TestConfiguration
    static class Config {
        @Bean
        FakeLlmClient fakeLlmClient() {
            return new FakeLlmClient();
        }

        @Bean
        SwitchableExecutor switchableExecutor() {
            return new SwitchableExecutor();
        }

        @Bean
        LlmProperties llmProperties() {
            return props(SECRET);
        }

        @Bean
        ExplainStreamService explainStreamService(ExplainPromptService promptService, FakeLlmClient client,
                LlmProperties properties, JsonMapper mapper, SwitchableExecutor executor) {
            return new ExplainStreamService(promptService, client, properties, mapper, executor);
        }
    }

    static LlmProperties props(String apiKey) {
        return new LlmProperties("gemini", "http://unused", apiKey, "flash",
            List.of(new LlmProperties.Model("flash", "Flash"), new LlmProperties.Model("lite", "Lite")),
            0.2, 8192, Duration.ofSeconds(120), Duration.ofSeconds(120));
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JsonMapper mapper;

    @Autowired
    private FakeLlmClient client;

    @Autowired
    private SwitchableExecutor executor;

    @MockitoBean
    private ExplainPromptService promptService;

    private record Event(String name, String data) {
    }

    @BeforeEach
    void reset() {
        executor.delegate = Runnable::run;
        client.lastRequest = null;
        client.script = (onDelta, cancelled) -> {
            onDelta.accept("Prvi ");
            onDelta.accept("drugi");
            return new LlmResult("STOP", 120, 7, 25);
        };
        when(promptService.build("log-1", ExplainLevel.L2)).thenReturn(new ExplainPrompt(1, ExplainLevel.L2,
            "system text", "user text", 9, List.of(ExplainSection.included("log"),
                ExplainSection.omitted("callers", "level"), new ExplainSection("neighbors", true, "truncated"))));
    }

    private static String body(String level, String model, String turnsJson) {
        return "{\"level\":" + (level == null ? "null" : "\"" + level + "\"") + ",\"model\":"
            + (model == null ? "null" : "\"" + model + "\"") + ",\"turns\":" + turnsJson + "}";
    }

    private static String turn(String role, String text) {
        return "{\"role\":\"" + role + "\",\"text\":\"" + text + "\"}";
    }

    /** Sends the request and, when a stream started, returns its events; else the (error) result. */
    private MvcResult explain(String logId, String json) throws Exception {
        MvcResult started = mockMvc.perform(post("/api/logs/" + logId + "/explain")
            .contentType(MediaType.APPLICATION_JSON).accept(MediaType.TEXT_EVENT_STREAM).content(json)).andReturn();
        if (started.getRequest().isAsyncStarted()) {
            return mockMvc.perform(asyncDispatch(started)).andReturn();
        }
        return started;
    }

    private static List<Event> events(MvcResult result) throws Exception {
        List<Event> events = new ArrayList<>();
        for (String block : result.getResponse().getContentAsString().split("\n\n")) {
            String name = null;
            StringBuilder data = new StringBuilder();
            for (String line : block.split("\n")) {
                if (line.startsWith("event:")) {
                    name = line.substring("event:".length()).strip();
                } else if (line.startsWith("data:")) {
                    data.append(line.substring("data:".length()));
                }
            }
            if (name != null) {
                events.add(new Event(name, data.toString()));
            }
        }
        return events;
    }

    @Test
    void streamsMetaThenDeltasThenDone() throws Exception {
        MvcResult result = explain("log-1", body("L2", "flash", "[]"));

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(result.getResponse().getContentType()).startsWith("text/event-stream");
        List<Event> events = events(result);
        assertThat(events).extracting(Event::name).containsExactly("meta", "delta", "delta", "done");

        JsonNode meta = mapper.readTree(events.get(0).data());
        assertThat(meta.path("level").asString()).isEqualTo("L2");
        assertThat(meta.path("model").asString()).isEqualTo("flash");
        assertThat(meta.path("promptVersion").asInt()).isEqualTo(1);
        assertThat(meta.path("promptChars").asInt()).isEqualTo(9);
        assertThat(meta.path("sections").size()).isEqualTo(3);
        assertThat(meta.path("sections").path(0).path("id").asString()).isEqualTo("log");
        assertThat(meta.path("sections").path(0).path("included").asBoolean()).isTrue();
        assertThat(meta.path("sections").path(1).path("reason").asString()).isEqualTo("level");
        assertThat(meta.path("sections").path(2).path("reason").asString()).isEqualTo("truncated");

        assertThat(mapper.readTree(events.get(1).data()).path("text").asString()).isEqualTo("Prvi ");
        assertThat(mapper.readTree(events.get(2).data()).path("text").asString()).isEqualTo("drugi");

        JsonNode done = mapper.readTree(events.get(3).data());
        assertThat(done.path("finishReason").asString()).isEqualTo("STOP");
        assertThat(done.path("promptTokens").asInt()).isEqualTo(120);
        assertThat(done.path("outputTokens").asInt()).isEqualTo(7);
        assertThat(done.path("durationMs").asLong()).isEqualTo(25);
    }

    @Test
    void deltaTextWithNewlinesAndQuotesStaysOnOneDataLine() throws Exception {
        client.script = (onDelta, cancelled) -> {
            onDelta.accept("red 1\n\"red\" 2\n");
            return new LlmResult("STOP", null, null, 1);
        };

        List<Event> events = events(explain("log-1", body("L2", "flash", "[]")));

        assertThat(events).extracting(Event::name).containsExactly("meta", "delta", "done");
        assertThat(mapper.readTree(events.get(1).data()).path("text").asString()).isEqualTo("red 1\n\"red\" 2\n");
        assertThat(mapper.readTree(events.get(2).data()).path("promptTokens").isNull()).isTrue();
    }

    @Test
    void anUnknownLogIsNotFound() throws Exception {
        when(promptService.build("missing", ExplainLevel.L2)).thenThrow(new LogNotFoundException("missing"));

        mockMvc.perform(post("/api/logs/missing/explain").contentType(MediaType.APPLICATION_JSON)
                .content(body("L2", "flash", "[]")))
            .andExpect(status().isNotFound())
            .andExpect(content().contentType("application/problem+json"))
            .andExpect(jsonPath("$.detail").value("log not found: missing"));
        assertThat(client.lastRequest).isNull();
    }

    @Test
    void anInvalidLevelIsABadRequest() throws Exception {
        mockMvc.perform(post("/api/logs/log-1/explain").contentType(MediaType.APPLICATION_JSON)
                .content(body("L9", "flash", "[]")))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.detail").value("level must be one of L0, L1, L2, L3, L4"));
        mockMvc.perform(post("/api/logs/log-1/explain").contentType(MediaType.APPLICATION_JSON)
                .content(body(null, "flash", "[]")))
            .andExpect(status().isBadRequest());
        assertThat(client.lastRequest).isNull();
    }

    @Test
    void aModelOutsideTheConfiguredListIsABadRequest() throws Exception {
        mockMvc.perform(post("/api/logs/log-1/explain").contentType(MediaType.APPLICATION_JSON)
                .content(body("L2", "gemini-9000", "[]")))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.detail").value("model must be one of: flash, lite"));
        mockMvc.perform(post("/api/logs/log-1/explain").contentType(MediaType.APPLICATION_JSON)
                .content(body("L2", null, "[]")))
            .andExpect(status().isBadRequest());
        assertThat(client.lastRequest).isNull();
    }

    @Test
    void invalidTurnsAreABadRequest() throws Exception {
        String longUser = "x".repeat(2001);
        String longModel = "x".repeat(20_001);
        List<String> invalid = new ArrayList<>(List.of(
            "[" + turn("user", "pitanje") + "]", // does not start with model
            "[" + turn("model", "odgovor") + "]", // does not end with user
            "[" + turn("model", "a") + "," + turn("model", "b") + "]", // not alternating
            "[" + turn("model", "a") + "," + turn("user", "") + "]", // empty user text
            "[{\"role\":\"model\"}" + "," + turn("user", "q") + "]", // model text missing
            "[" + turn("model", "a") + "," + turn("user", longUser) + "]",
            "[" + turn("model", longModel) + "," + turn("user", "q") + "]",
            "[" + turn("model", "a") + ",{\"role\":\"user\"}]", // no text
            "[" + turn("robot", "a") + "," + turn("user", "q") + "]"));
        // 22 items alternate correctly but are more than the 20 allowed
        invalid.add("[" + String.join(",", IntStream.range(0, 22)
            .mapToObj(i -> turn(i % 2 == 0 ? "model" : "user", "t")).toList()) + "]");

        for (String turns : invalid) {
            mockMvc.perform(post("/api/logs/log-1/explain").contentType(MediaType.APPLICATION_JSON)
                    .content(body("L2", "flash", turns)))
                .andExpect(status().isBadRequest());
        }
        assertThat(client.lastRequest).isNull();
    }

    @Test
    void anEmptyModelAnswerIsAcceptedAndPassedOn() throws Exception {
        MvcResult result = explain("log-1", body("L2", "flash", "[" + turn("model", "") + "," + turn("user", "Pokusaj ponovo") + "]"));

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(client.lastRequest.messages()).containsExactly(
            new LlmMessage(LlmMessage.Role.USER, "user text"),
            new LlmMessage(LlmMessage.Role.MODEL, ""),
            new LlmMessage(LlmMessage.Role.USER, "Pokusaj ponovo"));
    }

    @Test
    void theLimitsThemselvesAreAccepted() throws Exception {
        String turns = "[" + String.join(",", IntStream.range(0, 20)
            .mapToObj(i -> i % 2 == 0 ? turn("model", "m".repeat(20_000)) : turn("user", "u".repeat(2000))).toList()) + "]";

        MvcResult result = explain("log-1", body("L2", "flash", turns));

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(client.lastRequest.messages()).hasSize(21);
    }

    @Test
    void aMissingKeyIsServiceUnavailableWithACodeBeforeAnyStream() throws Exception {
        MockMvc unconfigured = org.springframework.test.web.servlet.setup.MockMvcBuilders
            .standaloneSetup(new ExplainStreamController(new ExplainStreamService(promptService, client, props(""),
                mapper, Runnable::run)))
            .setControllerAdvice(new ApiExceptionHandler())
            .build();

        unconfigured.perform(post("/api/logs/log-1/explain").contentType(MediaType.APPLICATION_JSON)
                .content(body("L2", "flash", "[]")))
            .andExpect(status().isServiceUnavailable())
            .andExpect(content().contentType("application/problem+json"))
            .andExpect(jsonPath("$.code").value("llm_not_configured"));
        assertThat(client.lastRequest).isNull();
    }

    @ParameterizedTest
    @EnumSource(LlmException.Kind.class)
    void everyLlmErrorKindBecomesAnErrorEvent(LlmException.Kind kind) throws Exception {
        client.script = (onDelta, cancelled) -> {
            throw new LlmException(kind, "boom " + kind);
        };

        List<Event> events = events(explain("log-1", body("L2", "flash", "[]")));

        assertThat(events).extracting(Event::name).containsExactly("meta", "error");
        JsonNode error = mapper.readTree(events.get(1).data());
        String expected = switch (kind) {
            case INVALID_KEY, NOT_CONFIGURED -> "invalid_key";
            case RATE_LIMITED -> "rate_limited";
            case BLOCKED -> "blocked";
            case TIMEOUT -> "timeout";
            case UPSTREAM -> "upstream";
        };
        assertThat(error.path("code").asString()).isEqualTo(expected);
        assertThat(error.path("message").asString()).isEqualTo("boom " + kind);
    }

    @Test
    void anErrorInTheMiddleKeepsTheDeltasAndReplacesDone() throws Exception {
        client.script = (onDelta, cancelled) -> {
            onDelta.accept("pocetak");
            throw new LlmException(LlmException.Kind.TIMEOUT, "late");
        };

        List<Event> events = events(explain("log-1", body("L2", "flash", "[]")));

        assertThat(events).extracting(Event::name).containsExactly("meta", "delta", "error");
    }

    @Test
    void anUnexpectedFailureIsAnUpstreamErrorEventWithoutDetails() throws Exception {
        client.script = (onDelta, cancelled) -> {
            throw new IllegalStateException("internal detail " + SECRET);
        };

        List<Event> events = events(explain("log-1", body("L2", "flash", "[]")));

        assertThat(events).extracting(Event::name).containsExactly("meta", "error");
        JsonNode error = mapper.readTree(events.get(1).data());
        assertThat(error.path("code").asString()).isEqualTo("upstream");
        assertThat(events.get(1).data()).doesNotContain("internal detail").doesNotContain(SECRET);
    }

    @Test
    void theModelGetsTheContextPromptAndTheConversationInOrder() throws Exception {
        String turns = "[" + turn("model", "prvi odgovor") + "," + turn("user", "Kako da popravim?") + "]";

        explain("log-1", body("L2", "lite", turns));

        LlmRequest sent = client.lastRequest;
        assertThat(sent.model()).isEqualTo("lite");
        assertThat(sent.systemPrompt()).isEqualTo("system text");
        assertThat(sent.temperature()).isEqualTo(0.2);
        assertThat(sent.maxOutputTokens()).isEqualTo(8192);
        assertThat(sent.messages()).containsExactly(
            new LlmMessage(LlmMessage.Role.USER, "user text"),
            new LlmMessage(LlmMessage.Role.MODEL, "prvi odgovor"),
            new LlmMessage(LlmMessage.Role.USER, "Kako da popravim?"));
    }

    @Test
    void theContextIsBuiltAgainForEveryRequest() throws Exception {
        explain("log-1", body("L2", "flash", "[]"));
        explain("log-1", body("L2", "flash", "[" + turn("model", "a") + "," + turn("user", "q") + "]"));

        verify(promptService, org.mockito.Mockito.times(2)).build("log-1", ExplainLevel.L2);
    }

    @Test
    void aBrokenBodyIsABadRequestAndNothingIsSent() throws Exception {
        mockMvc.perform(post("/api/logs/log-1/explain").contentType(MediaType.APPLICATION_JSON).content("{nope"))
            .andExpect(status().isBadRequest());
        verify(promptService, never()).build(any(), any());
    }

    @Test
    void aDisconnectedClientSetsCancelledAndStopsTheModelCall() throws Exception {
        executor.delegate = Executors.newVirtualThreadPerTaskExecutor();
        AtomicReference<Boolean> cancelledAfterWait = new AtomicReference<>();
        client.script = (onDelta, cancelled) -> {
            onDelta.accept("prvi deo");
            for (int i = 0; i < 100 && !cancelled.getAsBoolean(); i++) {
                Thread.sleep(50);
            }
            cancelledAfterWait.set(cancelled.getAsBoolean());
            client.sawCancel.countDown();
            return new LlmResult("CANCELLED", null, null, 1);
        };

        MvcResult started = mockMvc.perform(post("/api/logs/log-1/explain").contentType(MediaType.APPLICATION_JSON)
                .content(body("L2", "flash", "[]")))
            .andExpect(request().asyncStarted()).andReturn();
        assertThat(client.started.await(2, TimeUnit.SECONDS)).isTrue();

        // what the servlet container does when the browser closes the connection
        MockAsyncContext context = (MockAsyncContext) started.getRequest().getAsyncContext();
        for (AsyncListener listener : context.getListeners()) {
            listener.onError(new AsyncEvent(context, new IOException("Broken pipe")));
        }

        assertThat(client.sawCancel.await(3, TimeUnit.SECONDS)).isTrue();
        assertThat(cancelledAfterWait.get()).isTrue();
    }

    @Test
    void errorsBeforeTheStreamStayProblemDetailsWhenTheClientAsksForAnEventStream() throws Exception {
        when(promptService.build("missing", ExplainLevel.L2)).thenThrow(new LogNotFoundException("missing"));

        mockMvc.perform(post("/api/logs/missing/explain").contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM).content(body("L2", "flash", "[]")))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.detail").value("log not found: missing"));
        mockMvc.perform(post("/api/logs/log-1/explain").contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM).content(body("L9", "flash", "[]")))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.detail").value("level must be one of L0, L1, L2, L3, L4"));
    }
}
