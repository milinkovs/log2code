package org.log2code.api.llm;

import java.time.Duration;
import java.util.List;
import tools.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;

/**
 * Manual check against the real Gemini API (T40). The name does not end in {@code Test}/{@code IT}, so it never
 * runs automatically. Needs {@code LOG2CODE_GEMINI_API_KEY} in the environment:
 * <pre>
 * set -a; . infra/.env.local; set +a
 * ./mvnw -q -pl log2code-api test -Dtest=GeminiLiveCheck -Dsurefire.failIfNoSpecifiedTests=false
 * </pre>
 * Prints deltas and the {@link LlmResult}; the key is never printed.
 */
class GeminiLiveCheck {

    @Test
    void streamsOkFromRealGemini() {
        String key = System.getenv("LOG2CODE_GEMINI_API_KEY");
        String model = System.getenv().getOrDefault("LOG2CODE_GEMINI_MODEL", "gemini-3.8-flash");
        LlmProperties props = new LlmProperties("gemini", "https://generativelanguage.googleapis.com/v1beta", key,
            model, List.of(new LlmProperties.Model(model, model)), 0.2, 8192,
            Duration.ofSeconds(10), Duration.ofSeconds(120));
        GeminiClient client = new GeminiClient(props, JsonMapper.builder().build());

        StringBuilder text = new StringBuilder();
        LlmResult result = client.stream(
            new LlmRequest(model, null, List.of(new LlmMessage(LlmMessage.Role.USER, "Odgovori jednom rečju: OK")),
                0.2, 8192),
            delta -> {
                System.out.println("delta: " + delta);
                text.append(delta);
            },
            () -> false);

        System.out.println("model=" + model + " text=" + text + " result=" + result);
        if (text.toString().isBlank()) {
            throw new AssertionError("empty answer, finishReason=" + result.finishReason());
        }
    }
}
