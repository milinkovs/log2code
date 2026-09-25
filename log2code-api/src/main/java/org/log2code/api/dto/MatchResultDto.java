package org.log2code.api.dto;

import java.util.List;
import java.util.Map;

/** The {@code match.*} fields of a log document (mirrors {@link org.log2code.core.model.MatchResult}). */
public record MatchResultDto(
    String status,
    String statementId,
    Double confidence,
    String confidenceLevel,
    List<CandidateDto> candidates,
    Map<String, Double> scoreBreakdown,
    List<String> args,
    String codeUnit,
    String module,
    String classFqn,
    String methodName,
    String filePath,
    String loggingApi,
    String templateKind,
    Integer line,
    String template,
    String githubUrl
) {
}
