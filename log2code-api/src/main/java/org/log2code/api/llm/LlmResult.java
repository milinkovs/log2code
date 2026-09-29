package org.log2code.api.llm;

/** Outcome of a finished (or cancelled) stream; token counts are {@code null} when the provider sent none. */
public record LlmResult(String finishReason, Integer promptTokens, Integer outputTokens, long durationMs) {
}
