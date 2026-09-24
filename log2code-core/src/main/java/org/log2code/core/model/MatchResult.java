package org.log2code.core.model;

import java.util.List;
import java.util.Map;

/** The result of matching a log event against the catalog (0.10), the {@code match.*} fields of {@link EnrichedLog}. */
public record MatchResult(
    String status,
    String statementId,
    Double confidence,
    String confidenceLevel,
    List<Candidate> candidates,
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
    public static final String STATUS_MATCHED = "matched";
    public static final String STATUS_AMBIGUOUS = "ambiguous";
    public static final String STATUS_UNMATCHED = "unmatched";

    public static final String CONFIDENCE_HIGH = "high";
    public static final String CONFIDENCE_MEDIUM = "medium";
    public static final String CONFIDENCE_LOW = "low";

    /**
     * A copy of this result with the denormalized {@code match.*} fields filled in from the winning
     * catalog entry (0.7: "polja match.* se denormalizuju iz naredbe iz kataloga"; T20 deliberately
     * leaves them {@code null}, T21 does this at write time).
     */
    public MatchResult withDenormalizedFrom(CatalogEntry entry) {
        return new MatchResult(status, statementId, confidence, confidenceLevel, candidates, scoreBreakdown, args,
            entry.codeUnit().name(), entry.module(), entry.classFqn(), entry.methodName(), entry.filePath(),
            entry.loggingApi(), entry.templateKind(), entry.line(), entry.template(), entry.githubUrl());
    }
}
