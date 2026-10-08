package org.log2code.eval.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.log2code.core.model.Candidate;
import org.log2code.core.model.MatchResult;
import org.log2code.eval.metrics.EvalMetrics.GroupRow;
import org.log2code.eval.truth.TruthSource;

/**
 * Eleven events, every count worked out by hand:
 *
 * <pre>
 * event  truth  status/level       predicted  candidates (score)                   @1   @3   note
 * E1     a      matched/high       a          a .9, b .8                           yes  yes
 * E2     a      matched/high       a          a .9                                 yes  yes
 * E3     b      matched/high       c          c .9, b .8                           no   yes  rank 2, not tied
 * E4     c      matched/medium     c          c .7                                 yes  yes
 * E5     d      ambiguous/low      e          e .5, f .5, d .5                     no   yes  rank 3, TIED with the prediction
 * E6     e      ambiguous/low      e          e .5, d .45                          yes  yes
 * E7     f      unmatched          -          d .3, f .2                           no   yes  rank 2, no prediction
 * E8     a      unmatched          -          b .3                                 no   no   not among the candidates
 * E9     b      matched/low        a          a .9, c .8, d .7, e .6, b .5         no   no   rank 5
 * E10    none   matched/high       a          a .9                                 -    -    no ground truth: coverage only
 * E11    gone   matched/medium     a          a .7                                 -    -    truth outside the catalog: a false link
 * </pre>
 */
class ScoreTest {

    @Test
    void countsMatchTheHandComputedValues() {
        Score score = Score.of(events(), Set.of());

        assertThat(score.events()).isEqualTo(11);
        assertThat(score.evaluable()).isEqualTo(9);
        assertThat(score.covered()).isEqualTo(9);
        assertThat(score.correctAt1()).isEqualTo(4);
        assertThat(score.correctAt3()).isEqualTo(7);
        assertThat(score.ambiguous()).isEqualTo(2);
        assertThat(score.errors()).isEqualTo(5);
    }

    @Test
    void precisionIsCountedPerConfidenceLevelOverEvaluablePredictions() {
        Score score = Score.of(events(), Set.of());

        // E10 and E11 have a prediction but no catalog truth, so they stay out of the precision of their levels
        assertThat(score.highCovered()).isEqualTo(3);
        assertThat(score.highCorrect()).isEqualTo(2);
        assertThat(score.mediumCovered()).isEqualTo(1);
        assertThat(score.mediumCorrect()).isEqualTo(1);
        assertThat(score.lowCovered()).isEqualTo(3);
        assertThat(score.lowCorrect()).isEqualTo(1);
        assertThat(score.precisionHigh()).isCloseTo(2.0 / 3, within(1e-12));
    }

    @Test
    void falseLinksAndUniqueStatementsAreCounted() {
        Score score = Score.of(events(), Set.of());

        assertThat(score.notInCatalog()).isEqualTo(1);
        assertThat(score.falseLinks()).isEqualTo(1);
        assertThat(score.falseLinkShare()).isEqualTo(1.0);
        // statements a (E1, E2, E8), b (E3, E9), c, d, e, f: a, c and e are hit
        assertThat(score.statements()).isEqualTo(6);
        assertThat(score.statementsHit()).isEqualTo(3);
        // macro: a 2/3, b 0, c 1, d 0, e 1, f 0
        assertThat(score.macroAccuracyAt1()).isCloseTo((2.0 / 3 + 1 + 1) / 6, within(1e-12));
    }

    @Test
    void errorsSplitIntoTiedUnmatchedAndTheRest() {
        Score score = Score.of(events(), Set.of());

        assertThat(score.tiedErrors()).isEqualTo(1);       // E5 only: E3 and E9 scored below the prediction
        assertThat(score.unmatchedErrors()).isEqualTo(2);  // E7, E8
    }

    @Test
    void theControlAccuracyLeavesOutTheExcludedStatements() {
        Score score = Score.of(events(), Set.of("a"));

        // without E1, E2, E8 (truth a): 6 events, of which E4 and E6 are right
        assertThat(score.controlEvaluable()).isEqualTo(6);
        assertThat(score.controlCorrectAt1()).isEqualTo(2);
        assertThat(score.controlAccuracyAt1()).isCloseTo(2.0 / 6, within(1e-12));
        assertThat(score.controlErrors()).isEqualTo(4);
        assertThat(score.controlTiedErrors()).isEqualTo(1);
    }

    @Test
    void agreesWithTheMetricsCalculatorOfTheEvalRun() {
        List<EventEvaluation> events = events();
        Score score = Score.of(events, Set.of());
        EvalMetrics metrics = MetricsCalculator.compute("ds", null, events);

        assertThat(score.coverage()).isEqualTo(metrics.headline().coverage());
        assertThat(score.accuracyAt1()).isEqualTo(metrics.headline().accuracyAt1());
        assertThat(score.accuracyAt3()).isEqualTo(metrics.headline().accuracyAt3());
        assertThat(score.ambiguousShare()).isEqualTo(metrics.headline().ambiguousShare());
        assertThat(score.falseLinkShare()).isEqualTo(metrics.headline().falseMatchOnNotInCatalog());
        assertThat(score.statements()).isEqualTo(metrics.uniqueStatements().statements());
        assertThat(score.statementsHit()).isEqualTo(metrics.uniqueStatements().hitAtLeastOnce());
        assertThat(score.macroAccuracyAt1()).isCloseTo(metrics.uniqueStatements().macroAccuracyAt1(), within(1e-12));
        for (GroupRow row : metrics.byConfidenceLevel()) {
            switch (row.key()) {
                case MatchResult.CONFIDENCE_HIGH -> {
                    assertThat(score.highCovered()).isEqualTo(row.events());
                    assertThat(score.highCorrect()).isEqualTo(row.correctAt1());
                }
                case MatchResult.CONFIDENCE_MEDIUM -> {
                    assertThat(score.mediumCovered()).isEqualTo(row.events());
                    assertThat(score.mediumCorrect()).isEqualTo(row.correctAt1());
                }
                default -> {
                    assertThat(score.lowCovered()).isEqualTo(row.events());
                    assertThat(score.lowCorrect()).isEqualTo(row.correctAt1());
                }
            }
        }
    }

    @Test
    void ratiosWithoutADenominatorAreNull() {
        Score empty = Score.of(List.of(), Set.of());

        assertThat(empty.events()).isZero();
        assertThat(empty.coverage()).isZero();
        assertThat(empty.accuracyAt1()).isNull();
        assertThat(empty.precisionHigh()).isNull();
        assertThat(empty.controlAccuracyAt1()).isNull();
        assertThat(empty.falseLinkShare()).isNull();
        assertThat(empty.macroAccuracyAt1()).isZero();
    }

    private static List<EventEvaluation> events() {
        List<EventEvaluation> events = new ArrayList<>();
        events.add(event("E1", MatchResult.STATUS_MATCHED, "a", "high", List.of("a"), 1, cand("a", .9), cand("b", .8)));
        events.add(event("E2", MatchResult.STATUS_MATCHED, "a", "high", List.of("a"), 1, cand("a", .9)));
        events.add(event("E3", MatchResult.STATUS_MATCHED, "c", "high", List.of("b"), 2, cand("c", .9), cand("b", .8)));
        events.add(event("E4", MatchResult.STATUS_MATCHED, "c", "medium", List.of("c"), 1, cand("c", .7)));
        events.add(event("E5", MatchResult.STATUS_AMBIGUOUS, "e", "low", List.of("d"), 3, cand("e", .5), cand("f", .5), cand("d", .5)));
        events.add(event("E6", MatchResult.STATUS_AMBIGUOUS, "e", "low", List.of("e"), 1, cand("e", .5), cand("d", .45)));
        events.add(event("E7", MatchResult.STATUS_UNMATCHED, null, null, List.of("f"), 2, cand("d", .3), cand("f", .2)));
        events.add(event("E8", MatchResult.STATUS_UNMATCHED, null, null, List.of("a"), 0, cand("b", .3)));
        events.add(event("E9", MatchResult.STATUS_MATCHED, "a", "low", List.of("b"), 5,
            cand("a", .9), cand("c", .8), cand("d", .7), cand("e", .6), cand("b", .5)));
        events.add(new EventEvaluation("E10", "f.log", 10, "svc", "INFO", "m", MatchResult.STATUS_MATCHED, "a", .9, "high",
            List.of(cand("a", .9)), null, TruthSource.NONE, List.of(), false, null, 0));
        events.add(new EventEvaluation("E11", "f.log", 11, "svc", "INFO", "m", MatchResult.STATUS_MATCHED, "a", .7, "medium",
            List.of(cand("a", .7)), null, TruthSource.ORACLE, List.of(), true, null, 0));
        return events;
    }

    private static Candidate cand(String id, double score) {
        return new Candidate(id, score);
    }

    private static EventEvaluation event(String id, String status, String predicted, String level, List<String> truth, int rank,
                                         Candidate... candidates) {
        return new EventEvaluation(id, "f.log", 1, "svc", "INFO", "m", status, predicted, predicted == null ? null : .8, level,
            List.of(candidates), null, TruthSource.ORACLE, truth, false, null, rank);
    }
}
