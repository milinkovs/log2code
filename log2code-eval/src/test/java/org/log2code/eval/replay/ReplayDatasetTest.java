package org.log2code.eval.replay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.log2code.core.ids.StableIds;
import org.log2code.core.model.GroundTruth;
import org.log2code.core.model.Label;
import org.log2code.core.model.LogEvent;
import org.log2code.core.model.MatchResult;
import org.log2code.eval.EvalUserException;
import org.log2code.eval.metrics.EventEvaluation;
import org.log2code.eval.truth.TruthSource;
import org.log2code.ingester.match.CandidateMode;
import org.log2code.ingester.match.MatchingConfig;
import org.log2code.ingester.match.MatchingConfigLoader;

class ReplayDatasetTest {

    private static final MatchingConfig CONFIG = MatchingConfigLoader.load(Path.of("..", "config", "matching.yml"));

    @Test
    void replaysTheMatcherOverEveryEventAgainstItsOracleTruth() {
        ReplayDataset dataset = SyntheticDataset.dataset(Map.of());

        List<EventEvaluation> evaluations = dataset.evaluate(CONFIG, CandidateMode.BOTH);

        assertThat(evaluations).hasSize(3);
        assertThat(evaluations).extracting(EventEvaluation::predictedStatementId).containsExactly("s1", "s2", "s3");
        assertThat(evaluations).extracting(EventEvaluation::truthStatementIds)
            .containsExactly(List.of("s1"), List.of("s2"), List.of("s3"));
        assertThat(evaluations).allSatisfy(e -> {
            assertThat(e.truthSource()).isEqualTo(TruthSource.ORACLE);
            assertThat(e.correctAt1()).isTrue();
        });
    }

    @Test
    void scoreCountsTheSameThingsEvalRunDoes() {
        ReplayDataset dataset = SyntheticDataset.dataset(Map.of());

        var score = dataset.score(CONFIG, CandidateMode.BOTH);

        assertThat(score.events()).isEqualTo(3);
        assertThat(score.evaluable()).isEqualTo(3);
        assertThat(score.correctAt1()).isEqualTo(3);
        assertThat(score.coverage()).isEqualTo(1.0);
        assertThat(score.statements()).isEqualTo(3);
    }

    @Test
    void aManualLabelOverridesTheOracle() {
        LogEvent first = SyntheticDataset.events().get(0);
        String logId = StableIds.logId(first.datasetId(), first.sourceFile(), first.lineNumber());
        Label label = new Label(logId, "syn-01", Label.VERDICT_INCORRECT, "s2", "s1", null, java.time.Instant.EPOCH);
        ReplayDataset dataset = SyntheticDataset.dataset(Map.of(logId, label));

        EventEvaluation evaluation = dataset.evaluate(CONFIG, CandidateMode.BOTH).get(0);

        assertThat(evaluation.truthSource()).isEqualTo(TruthSource.MANUAL);
        assertThat(evaluation.truthStatementIds()).containsExactly("s2");
        assertThat(evaluation.correctAt1()).isFalse();
        assertThat(evaluation.truthRank()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void theDominantStatementsAreTheMostFrequentCorrectOnes() {
        // s2 twice, s1 once, s3 once: the two most frequent are s2 and then the first of the tied ones by key
        List<LogEvent> events = List.of(
            SyntheticDataset.event(1, "com.acme.A", "Saving a", new GroundTruth("com.acme.A", "m", 10, true)),
            SyntheticDataset.event(2, "com.acme.B", "Saving b", new GroundTruth("com.acme.B", "m", 20, true)),
            SyntheticDataset.event(3, "com.acme.B", "Saving c", new GroundTruth("com.acme.B", "m", 20, true)),
            SyntheticDataset.event(4, "com.acme.C", "other", new GroundTruth("com.acme.C", "m", 30, true)));

        ReplayDataset dataset = new ReplayDataset("syn-01", SyntheticDataset.CODE, SyntheticDataset.index(),
            SyntheticDataset.view(), events, Map.of());

        assertThat(dataset.dominantTruthKeys()).containsExactly("s2", "s1");
    }

    @Test
    void anEventWithAnUnreliableOracleHasNoTruthAndEntersCoverageOnly() {
        List<LogEvent> events = List.of(
            SyntheticDataset.event(1, "com.acme.A", "Saving a", new GroundTruth("com.acme.A", "m", 10, false)),
            SyntheticDataset.event(2, "com.acme.B", "Saving b", new GroundTruth("com.acme.B", "m", 20, true)));
        ReplayDataset dataset = new ReplayDataset("syn-01", SyntheticDataset.CODE, SyntheticDataset.index(),
            SyntheticDataset.view(), events, Map.of());

        var score = dataset.score(CONFIG, CandidateMode.BOTH);

        assertThat(score.events()).isEqualTo(2);
        assertThat(score.evaluable()).isEqualTo(1);
        assertThat(score.covered()).isEqualTo(2);
    }

    @Test
    void requireGroundTruthRejectsADatasetWithoutOne() {
        List<LogEvent> events = List.of(SyntheticDataset.event(1, "com.acme.A", "Saving a", null));
        ReplayDataset dataset = new ReplayDataset("syn-01", SyntheticDataset.CODE, SyntheticDataset.index(),
            SyntheticDataset.view(), events, Map.of());

        assertThatThrownBy(dataset::requireGroundTruth)
            .isInstanceOf(EvalUserException.class)
            .hasMessageContaining("no event with a ground truth");
    }

    @Test
    void theMatcherIsFreshForEveryEvaluation() {
        ReplayDataset dataset = SyntheticDataset.dataset(Map.of());
        MatchingConfig strict = new MatchingConfig(CONFIG.weights(),
            new MatchingConfig.Thresholds(0.99, 0.05, 0.6, 0.75, 0.5), CONFIG.candidates(), CONFIG.oracle());

        List<EventEvaluation> lenient = dataset.evaluate(CONFIG, CandidateMode.BOTH);
        List<EventEvaluation> unmatched = dataset.evaluate(strict, CandidateMode.BOTH);
        List<EventEvaluation> again = dataset.evaluate(CONFIG, CandidateMode.BOTH);

        assertThat(unmatched).extracting(EventEvaluation::status).containsOnly(MatchResult.STATUS_UNMATCHED);
        assertThat(again).isEqualTo(lenient);
    }
}
