package org.log2code.eval.metrics;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.log2code.core.model.MatchResult;

/**
 * The counts one ablation, tuning or validation run needs, taken from a list of {@link EventEvaluation} and
 * defined exactly like the T33 metrics (ADR-044 point 4), so a variant's numbers can be compared with an
 * {@code eval run} report. Counts are kept as integers, not ratios: comparing two configurations by the
 * number of correct events is exact.
 *
 * @param events            all events of the dataset
 * @param evaluable         events whose ground truth is a catalog statement: the denominator of accuracy
 * @param covered           events with a prediction ({@code matched} or {@code ambiguous})
 * @param correctAt1        evaluable events whose prediction is a correct statement
 * @param correctAt3        evaluable events with a correct statement among the top 3 candidates
 * @param ambiguous         events with status {@code ambiguous}
 * @param highCovered       evaluable events with a prediction and confidence level {@code high} (likewise medium, low)
 * @param highCorrect       the correct ones among them
 * @param notInCatalog      events whose ground truth points outside the catalog
 * @param falseLinks        the ones among them that got a prediction anyway
 * @param statements        distinct correct statements among the evaluable events
 * @param statementsHit     the ones predicted correctly for at least one event
 * @param macroAccuracyAt1  mean over statements of (correct events / events of that statement)
 * @param controlEvaluable  evaluable events outside the dominant statements (see {@link #of})
 * @param controlCorrectAt1 correct ones among them
 * @param tiedErrors        wrong predictions whose correct statement scored exactly as high as the one chosen, so that
 *                          only the tie-break ({@code statement_id}) decided: no weight can fix these
 * @param unmatchedErrors   evaluable events without a prediction ({@code unmatched})
 * @param controlTiedErrors the tied errors among the events outside the dominant statements
 */
public record Score(
    int events,
    int evaluable,
    int covered,
    int correctAt1,
    int correctAt3,
    int ambiguous,
    int highCovered,
    int highCorrect,
    int mediumCovered,
    int mediumCorrect,
    int lowCovered,
    int lowCorrect,
    int notInCatalog,
    int falseLinks,
    int statements,
    int statementsHit,
    double macroAccuracyAt1,
    int controlEvaluable,
    int controlCorrectAt1,
    int tiedErrors,
    int unmatchedErrors,
    int controlTiedErrors
) {

    /**
     * @param excludedTruthKeys {@link EventEvaluation#truthKey()} values left out of the control accuracy
     *                          ({@link #controlCorrectAt1}): the dominant statements, whose events a weight cannot move
     */
    public static Score of(List<EventEvaluation> events, Set<String> excludedTruthKeys) {
        int evaluable = 0;
        int covered = 0;
        int correct1 = 0;
        int correct3 = 0;
        int ambiguous = 0;
        int[] byLevel = new int[6]; // high, high correct, medium, medium correct, low, low correct
        int notInCatalog = 0;
        int falseLinks = 0;
        int controlEvaluable = 0;
        int controlCorrect = 0;
        int tied = 0;
        int unmatchedErrors = 0;
        int controlTied = 0;
        // per truth statement: {events, correct}
        Map<String, int[]> perStatement = new LinkedHashMap<>();

        for (EventEvaluation e : events) {
            if (e.covered()) {
                covered++;
            }
            if (MatchResult.STATUS_AMBIGUOUS.equals(e.status())) {
                ambiguous++;
            }
            if (e.truthKnown() && e.truthNotInCatalog()) {
                notInCatalog++;
                if (e.covered()) {
                    falseLinks++;
                }
            }
            if (!e.evaluable()) {
                continue;
            }
            evaluable++;
            boolean right = e.correctAt1();
            boolean control = !excludedTruthKeys.contains(e.truthKey());
            if (!right) {
                if (!e.covered()) {
                    unmatchedErrors++;
                } else if (tiedWithPrediction(e)) {
                    tied++;
                    if (control) {
                        controlTied++;
                    }
                }
            }
            if (right) {
                correct1++;
            }
            if (e.correctAt3()) {
                correct3++;
            }
            int[] group = perStatement.computeIfAbsent(e.truthKey(), k -> new int[2]);
            group[0]++;
            if (right) {
                group[1]++;
            }
            if (control) {
                controlEvaluable++;
                if (right) {
                    controlCorrect++;
                }
            }
            if (e.covered()) {
                int slot = levelSlot(e.confidenceLevel());
                if (slot >= 0) {
                    byLevel[slot]++;
                    if (right) {
                        byLevel[slot + 1]++;
                    }
                }
            }
        }

        int hit = 0;
        double macro = 0;
        for (int[] group : perStatement.values()) {
            if (group[1] > 0) {
                hit++;
            }
            macro += (double) group[1] / group[0];
        }
        return new Score(events.size(), evaluable, covered, correct1, correct3, ambiguous,
            byLevel[0], byLevel[1], byLevel[2], byLevel[3], byLevel[4], byLevel[5],
            notInCatalog, falseLinks, perStatement.size(), hit,
            perStatement.isEmpty() ? 0 : macro / perStatement.size(), controlEvaluable, controlCorrect,
            tied, unmatchedErrors, controlTied);
    }

    /** The correct statement is among the stored candidates with the same score as the first one. */
    private static boolean tiedWithPrediction(EventEvaluation e) {
        if (e.truthRank() < 1 || e.truthRank() > e.candidates().size() || e.candidates().isEmpty()) {
            return false;
        }
        double top = e.candidates().get(0).score();
        return Math.abs(e.candidates().get(e.truthRank() - 1).score() - top) < 1e-12;
    }

    private static int levelSlot(String confidenceLevel) {
        if (MatchResult.CONFIDENCE_HIGH.equals(confidenceLevel)) {
            return 0;
        }
        if (MatchResult.CONFIDENCE_MEDIUM.equals(confidenceLevel)) {
            return 2;
        }
        if (MatchResult.CONFIDENCE_LOW.equals(confidenceLevel)) {
            return 4;
        }
        return -1;
    }

    /** (matched + ambiguous) / all events. */
    public double coverage() {
        return events == 0 ? 0 : (double) covered / events;
    }

    /** Correct top-1 / evaluable; {@code null} without evaluable events. */
    public Double accuracyAt1() {
        return ratio(correctAt1, evaluable);
    }

    public Double accuracyAt3() {
        return ratio(correctAt3, evaluable);
    }

    /** Precision of the {@code high} confidence level: correct / predicted at that level. */
    public Double precisionHigh() {
        return ratio(highCorrect, highCovered);
    }

    public Double precisionMedium() {
        return ratio(mediumCorrect, mediumCovered);
    }

    public Double precisionLow() {
        return ratio(lowCorrect, lowCovered);
    }

    public double ambiguousShare() {
        return events == 0 ? 0 : (double) ambiguous / events;
    }

    /** Evaluable events whose prediction is wrong or missing. */
    public int errors() {
        return evaluable - correctAt1;
    }

    /** The same, outside the dominant statements. */
    public int controlErrors() {
        return controlEvaluable - controlCorrectAt1;
    }

    /** Accuracy@1 without the dominant statements. */
    public Double controlAccuracyAt1() {
        return ratio(controlCorrectAt1, controlEvaluable);
    }

    /** Share of {@code gt_not_in_catalog} events that got a prediction anyway. */
    public Double falseLinkShare() {
        return ratio(falseLinks, notInCatalog);
    }

    /** Share of the distinct correct statements predicted correctly at least once. */
    public Double statementHitRate() {
        return ratio(statementsHit, statements);
    }

    private static Double ratio(int numerator, int denominator) {
        return denominator == 0 ? null : (double) numerator / denominator;
    }
}
