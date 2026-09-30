package org.log2code.api.dto;

import java.util.List;

/** Response of {@code GET /api/logs/{logId}/explain/prompt} (T41): exactly what would be sent to the model. */
public record ExplainPromptDto(
    int promptVersion,
    String level,
    String systemPrompt,
    String userPrompt,
    int promptChars,
    List<ExplainSectionDto> sections
) {
}
