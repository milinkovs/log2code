package org.log2code.eval.metrics;

import java.util.List;
import java.util.Map;

/**
 * All numbers of one evaluation ({@code metrics.json}). Ratios are {@code null} (omitted from the JSON)
 * when their denominator is zero.
 *
 * @param breakdowns accuracy per truth-statement attribute (or event attribute for level and service), keyed by
 *                   dimension: {@code code_unit_type}, {@code artifact}, {@code logging_api}, {@code template_kind},
 *                   {@code level}, {@code service}
 */
public record EvalMetrics(
    String datasetId,
    String codeName,
    String codeVersion,
    Counts counts,
    Headline headline,
    List<GroupRow> byConfidenceLevel,
    List<GroupRow> byStatus,
    UniqueStatements uniqueStatements,
    Map<String, List<BreakdownRow>> breakdowns
) {

    /**
     * @param truthNone           events without a ground truth (counted in coverage only)
     * @param truthNotInCatalog   events whose truth points at a class or line the catalog does not have
     * @param evaluable           events whose truth is a catalog statement: the denominator of accuracy
     * @param evaluableUnsupported evaluable events whose correct statement has an unsupported template (0.10 step 0)
     */
    public record Counts(
        int totalEvents,
        int matched,
        int ambiguous,
        int unmatched,
        int truthManual,
        int truthOracle,
        int truthNone,
        int truthNotInCatalog,
        int evaluable,
        int evaluableUnsupported
    ) {
    }

    /**
     * @param coverage                (matched + ambiguous) / all events
     * @param accuracyAt1             correct top-1 / evaluable; an unmatched event is a miss
     * @param accuracyAt3             correct statement among the top 3 candidates / evaluable
     * @param accuracyAt3Covered      the same, over matched and ambiguous evaluable events only
     * @param precisionCovered        correct top-1 / matched and ambiguous evaluable events
     * @param accuracyAt1Supported    accuracy@1 over evaluable events whose statement the matcher can consider at all
     * @param notInCatalogShare       events with truth pointing outside the catalog / events with a truth
     * @param falseMatchOnNotInCatalog share of those events that got a prediction anyway
     */
    public record Headline(
        double coverage,
        Double coverageWithTruth,
        Double accuracyAt1,
        Double accuracyAt3,
        Double accuracyAt3Covered,
        Double precisionCovered,
        Double accuracyAt1Supported,
        double ambiguousShare,
        Double ambiguousShareEvaluable,
        Double notInCatalogShare,
        Double falseMatchOnNotInCatalog
    ) {
    }

    /** A row of the confidence-level or status table; {@code events} counts evaluable events only. */
    public record GroupRow(String key, int events, int correctAt1, int correctAt3, Double accuracyAt1, Double accuracyAt3) {
    }

    public record BreakdownRow(
        String key,
        int events,
        int covered,
        int correctAt1,
        int correctAt3,
        Double coverage,
        Double accuracyAt1,
        Double accuracyAt3,
        int uniqueStatements,
        int uniqueStatementsHit
    ) {
    }

    /**
     * Accuracy per unique statement, so that frequent messages do not dominate (T33 step 3).
     *
     * @param hitAtLeastOnce      statements predicted correctly for at least one of their events
     * @param macroAccuracyAt1    mean over statements of (correct events / events of that statement)
     * @param topStatementShare   share of evaluable events that belong to the single most frequent statement
     */
    public record UniqueStatements(
        int statements,
        int hitAtLeastOnce,
        Double hitRate,
        Double macroAccuracyAt1,
        Double macroAccuracyAt3,
        double topStatementShare
    ) {
    }
}
