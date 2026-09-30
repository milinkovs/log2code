package org.log2code.api.llm.explain;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import org.log2code.api.dto.ExplainRequest;
import org.log2code.api.dto.ExplainSectionDto;
import org.log2code.api.llm.LlmClient;
import org.log2code.api.llm.LlmException;
import org.log2code.api.llm.LlmMessage;
import org.log2code.api.llm.LlmProperties;
import org.log2code.api.llm.LlmRequest;
import org.log2code.api.llm.LlmResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.json.JsonMapper;

/**
 * "Explain" as a Server-Sent Events stream (T42): validates the request, builds the prompt again for every
 * request and streams the model's answer as {@code meta}, {@code delta}..., {@code done} (or {@code error}).
 * Nothing is kept between requests; the browser sends the whole conversation in {@code turns}.
 */
public final class ExplainStreamService {

    static final int MAX_TURNS = 20;
    static final int MAX_USER_TEXT = 2000;
    static final int MAX_MODEL_TEXT = 20_000;
    private static final long TIMEOUT_MARGIN_SECONDS = 30;

    private static final Logger LOG = LoggerFactory.getLogger(ExplainStreamService.class);

    private final ExplainPromptService promptService;
    private final LlmClient llmClient;
    private final LlmProperties properties;
    private final JsonMapper mapper;
    private final Executor executor;

    public ExplainStreamService(ExplainPromptService promptService, LlmClient llmClient, LlmProperties properties,
            JsonMapper mapper, Executor executor) {
        this.promptService = promptService;
        this.llmClient = llmClient;
        this.properties = properties;
        this.mapper = mapper;
        this.executor = executor;
    }

    record Meta(String level, String model, int promptVersion, int promptChars, List<ExplainSectionDto> sections) {
    }

    record Delta(String text) {
    }

    record Done(String finishReason, Integer promptTokens, Integer outputTokens, long durationMs) {
    }

    record ErrorEvent(String code, String message) {
    }

    /**
     * Checks everything that can be checked before the stream starts (each failure is an ordinary HTTP error,
     * not an SSE event) and then starts streaming on {@link #executor}.
     *
     * @throws LogNotFoundException (via the prompt service) when the log does not exist: 404
     * @throws IllegalArgumentException for an invalid level, model or turns: 400
     * @throws ExplainNotConfiguredException when no API key is set: 503
     */
    public SseEmitter start(String logId, ExplainRequest request) {
        ExplainLevel level = ExplainLevel.parse(request.level());
        String model = checkModel(request.model());
        List<LlmMessage> turns = checkTurns(request.turns());
        ExplainPrompt prompt = promptService.build(logId, level);
        if (!properties.configured()) {
            throw new ExplainNotConfiguredException();
        }

        List<LlmMessage> messages = new ArrayList<>();
        messages.add(new LlmMessage(LlmMessage.Role.USER, prompt.userPrompt()));
        messages.addAll(turns);
        LlmRequest llmRequest = new LlmRequest(model, prompt.systemPrompt(), List.copyOf(messages),
            properties.temperature(), properties.maxOutputTokens());

        SseEmitter emitter = new SseEmitter(properties.requestTimeout().plusSeconds(TIMEOUT_MARGIN_SECONDS).toMillis());
        AtomicBoolean cancelled = new AtomicBoolean();
        emitter.onCompletion(() -> cancelled.set(true));
        emitter.onTimeout(() -> cancelled.set(true));
        emitter.onError(e -> cancelled.set(true));
        executor.execute(() -> run(logId, prompt, model, llmRequest, turns.size(), emitter, cancelled));
        return emitter;
    }

    private String checkModel(String model) {
        if (model == null || properties.models().stream().noneMatch(m -> m.id().equals(model))) {
            throw new IllegalArgumentException("model must be one of: "
                + String.join(", ", properties.models().stream().map(LlmProperties.Model::id).toList()));
        }
        return model;
    }

    private static List<LlmMessage> checkTurns(List<ExplainRequest.Turn> turns) {
        if (turns == null || turns.isEmpty()) {
            return List.of();
        }
        if (turns.size() > MAX_TURNS) {
            throw new IllegalArgumentException("turns must have at most " + MAX_TURNS + " items");
        }
        List<LlmMessage> checked = new ArrayList<>();
        for (int i = 0; i < turns.size(); i++) {
            ExplainRequest.Turn turn = turns.get(i);
            // the conversation starts with the model's first answer, so even items are "model", odd are "user"
            boolean model = i % 2 == 0;
            String expected = model ? "model" : "user";
            if (turn == null || !expected.equals(turn.role())) {
                throw new IllegalArgumentException("turns[" + i + "].role must be \"" + expected
                    + "\": turns alternate model/user, start with model and end with user");
            }
            String text = turn.text();
            // an empty model answer is legitimate (e.g. all output tokens spent on "thinking"), and Gemini accepts it
            int min = model ? 0 : 1;
            int max = model ? MAX_MODEL_TEXT : MAX_USER_TEXT;
            if (text == null || text.length() < min || text.length() > max) {
                throw new IllegalArgumentException("turns[" + i + "].text must have " + min + " to " + max + " characters");
            }
            checked.add(new LlmMessage(model ? LlmMessage.Role.MODEL : LlmMessage.Role.USER, text));
        }
        if (turns.size() % 2 != 0) {
            throw new IllegalArgumentException("turns must end with a \"user\" item");
        }
        return checked;
    }

    private void run(String logId, ExplainPrompt prompt, String model, LlmRequest llmRequest, int turnCount,
            SseEmitter emitter, AtomicBoolean cancelled) {
        String finishReason;
        Integer promptTokens = null;
        Integer outputTokens = null;
        long started = System.nanoTime();
        try {
            send(emitter, cancelled, "meta", new Meta(prompt.level().name(), model, prompt.promptVersion(),
                prompt.promptChars(),
                prompt.sections().stream().map(s -> new ExplainSectionDto(s.id(), s.included(), s.reason())).toList()));
            LlmResult result = llmClient.stream(llmRequest, text -> send(emitter, cancelled, "delta", new Delta(text)),
                cancelled::get);
            finishReason = result.finishReason();
            promptTokens = result.promptTokens();
            outputTokens = result.outputTokens();
            send(emitter, cancelled, "done",
                new Done(finishReason, promptTokens, outputTokens, result.durationMs()));
        } catch (LlmException e) {
            String code = errorCode(e.kind());
            finishReason = "ERROR:" + code;
            send(emitter, cancelled, "error", new ErrorEvent(code, e.getMessage()));
        } catch (RuntimeException e) {
            finishReason = "ERROR:unexpected";
            LOG.error("explain failed unexpectedly: {}", e.getClass().getName());
            send(emitter, cancelled, "error", new ErrorEvent("upstream", "Unexpected error while explaining the log"));
        }
        // ids, level, model, sizes and outcome only; never the prompt, the answer or the key
        LOG.info("explain logId={} level={} model={} promptChars={} finishReason={} promptTokens={} outputTokens={}"
                + " durationMs={} turns={}", logId, prompt.level(), model, prompt.promptChars(), finishReason,
            promptTokens, outputTokens, (System.nanoTime() - started) / 1_000_000, turnCount);
        try {
            emitter.complete();
        } catch (RuntimeException e) {
            // already completed by a timeout or a disconnect
        }
    }

    /** {@code NOT_CONFIGURED} cannot happen here (checked before the stream); it is reported as a key problem. */
    static String errorCode(LlmException.Kind kind) {
        return switch (kind) {
            case INVALID_KEY, NOT_CONFIGURED -> "invalid_key";
            case RATE_LIMITED -> "rate_limited";
            case BLOCKED -> "blocked";
            case TIMEOUT -> "timeout";
            case UPSTREAM -> "upstream";
        };
    }

    /** A failed send means the client is gone: flag it so the model call is abandoned. */
    private void send(SseEmitter emitter, AtomicBoolean cancelled, String event, Object payload) {
        if (cancelled.get()) {
            return;
        }
        try {
            emitter.send(SseEmitter.event().name(event).data(mapper.writeValueAsString(payload)));
        } catch (IOException | IllegalStateException e) {
            cancelled.set(true);
        }
    }
}
