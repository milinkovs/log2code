package org.log2code.eval.metrics;

import java.util.List;
import org.log2code.eval.truth.TruthInfo;

/**
 * One correct statement (or, rarely, a group of statements sharing a line) with the outcome of all events
 * that belong to it ({@code per_statement.csv}).
 *
 * @param topPredictionId    the most frequent top-1 prediction of those events; {@code null} if that is "no prediction"
 * @param topPredictionCount how many events got that prediction
 */
public record StatementRow(
    String key,
    List<String> statementIds,
    TruthInfo info,
    int events,
    int covered,
    int correctAt1,
    int correctAt3,
    String topPredictionId,
    int topPredictionCount
) {

    public double accuracyAt1() {
        return events == 0 ? 0 : (double) correctAt1 / events;
    }

    public double accuracyAt3() {
        return events == 0 ? 0 : (double) correctAt3 / events;
    }
}
