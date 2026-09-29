package org.log2code.api.llm;

import java.util.List;

public record LlmRequest(
        String model,
        String systemPrompt,
        List<LlmMessage> messages,
        double temperature,
        int maxOutputTokens) {
}
