package org.log2code.api.llm.explain;

import java.util.List;

/**
 * Exactly what is sent to the model for one log and level (T41). {@code promptChars} is the length of
 * {@code userPrompt} (the part bounded by {@link ExplainPromptRenderer#MAX_USER_PROMPT_CHARS}).
 */
public record ExplainPrompt(
    int promptVersion,
    ExplainLevel level,
    String systemPrompt,
    String userPrompt,
    int promptChars,
    List<ExplainSection> sections
) {
    /** Bumped together with a new {@code explain-system-vN.md} file whenever the prompt text changes. */
    public static final int PROMPT_VERSION = 1;
}
