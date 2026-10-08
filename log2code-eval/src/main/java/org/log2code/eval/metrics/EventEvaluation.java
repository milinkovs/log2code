package org.log2code.eval.metrics;

import java.util.List;
import java.util.Map;
import org.log2code.core.model.Candidate;
import org.log2code.core.model.MatchResult;
import org.log2code.eval.truth.TruthInfo;
import org.log2code.eval.truth.TruthSource;

/**
 * One event with its prediction (from {@code log2code-logs}) and its ground truth, reduced to what the
 * metrics, the error samples and the per-statement table need.
 *
 * @param candidates     the stored top-5 candidates, best first
 * @param truthRank      1-based position of the first correct statement among {@code candidates}; 0 if absent
 * @param truthInfo      catalog attributes of the primary correct statement; {@code null} unless the event is evaluable
 */
public record EventEvaluation(
    String logId,
    String sourceFile,
    int lineNumber,
    String service,
    String level,
    String message,
    String status,
    String predictedStatementId,
    Double confidence,
    String confidenceLevel,
    List<Candidate> candidates,
    Map<String, Double> scoreBreakdown,
    TruthSource truthSource,
    List<String> truthStatementIds,
    boolean truthNotInCatalog,
    TruthInfo truthInfo,
    int truthRank
) {

    public EventEvaluation {
        candidates = candidates == null ? List.of() : List.copyOf(candidates);
        scoreBreakdown = scoreBreakdown == null ? Map.of() : Map.copyOf(scoreBreakdown);
        truthStatementIds = truthStatementIds == null ? List.of() : List.copyOf(truthStatementIds);
    }

    /** The matcher made a prediction: {@code matched} or {@code ambiguous}. */
    public boolean covered() {
        return MatchResult.STATUS_MATCHED.equals(status) || MatchResult.STATUS_AMBIGUOUS.equals(status);
    }

    /** A ground truth exists (manual label or reliable oracle), possibly pointing outside the catalog. */
    public boolean truthKnown() {
        return truthSource != TruthSource.NONE && (truthNotInCatalog || !truthStatementIds.isEmpty());
    }

    /** The truth points at catalog statements, so the event counts towards accuracy. */
    public boolean evaluable() {
        return !truthStatementIds.isEmpty();
    }

    /** The predicted statement is one of the correct ones. An {@code unmatched} event predicts nothing. */
    public boolean correctAt1() {
        return evaluable() && covered() && predictedStatementId != null && truthStatementIds.contains(predictedStatementId);
    }

    /** A correct statement is among the top 3 candidates; candidates are kept for {@code unmatched} events too (0.10). */
    public boolean correctAt3() {
        return correctAt1() || (evaluable() && truthRank >= 1 && truthRank <= 3);
    }

    /** Key of the correct statement(s), used to group events per unique statement. */
    public String truthKey() {
        return String.join("+", truthStatementIds);
    }
}
