package org.log2code.eval.tune;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.log2code.core.model.CodeVersion;
import org.log2code.eval.EvalUserException;
import org.log2code.eval.metrics.Score;
import org.log2code.eval.replay.SyntheticDatasetAccess;
import org.log2code.ingester.match.MatchingConfig;
import org.log2code.ingester.match.MatchingConfigLoader;

class ValidationTest {

    private static final CodeVersion CODE = new CodeVersion("p", "v1");

    @Test
    void moreCorrectEventsAreBetter() {
        Validation.Result result = Validation.of("test-x", CODE, score(700, 100, 99), score(710, 100, 98), 0.95);

        assertThat(result.verdict()).isEqualTo(Validation.Verdict.BETTER);
        assertThat(result.constraintMet()).isTrue();
        assertThat(result.notWorse()).isTrue();
    }

    @Test
    void theSameNumberOfCorrectEventsIsEqualAndStillNotWorse() {
        Validation.Result result = Validation.of("test-x", CODE, score(700, 100, 99), score(700, 100, 99), 0.95);

        assertThat(result.verdict()).isEqualTo(Validation.Verdict.EQUAL);
        assertThat(result.notWorse()).isTrue();
    }

    @Test
    void fewerCorrectEventsAreWorse() {
        Validation.Result result = Validation.of("test-x", CODE, score(700, 100, 99), score(699, 100, 99), 0.95);

        assertThat(result.verdict()).isEqualTo(Validation.Verdict.WORSE);
        assertThat(result.notWorse()).isFalse();
    }

    @Test
    void aBetterButImpreciseProposalMayNotReplaceTheConfiguration() {
        Validation.Result result = Validation.of("test-x", CODE, score(700, 100, 99), score(760, 100, 90), 0.95);

        assertThat(result.verdict()).isEqualTo(Validation.Verdict.BETTER);
        assertThat(result.constraintMet()).isFalse();
        assertThat(result.notWorse()).isFalse();
    }

    @Test
    void aProposalWithoutAnyHighPredictionDoesNotMeetTheConstraint() {
        Validation.Result result = Validation.of("test-x", CODE, score(700, 100, 99), score(700, 0, 0), 0.95);

        assertThat(result.constraintMet()).isFalse();
    }

    @Test
    void runScoresBothConfigurationsOnTheDataset() {
        MatchingConfig config = MatchingConfigLoader.load(Path.of("..", "config", "matching.yml"));
        MatchingConfig strict = new MatchingConfig(config.weights(),
            new MatchingConfig.Thresholds(0.99, 0.05, 0.6, 0.75, 0.5), config.candidates(), config.oracle());

        Validation.Result result = Validation.run(SyntheticDatasetAccess.dataset(), config, strict, 0.95);

        assertThat(result.baseline().correctAt1()).isEqualTo(3);
        assertThat(result.proposed().correctAt1()).isZero();
        assertThat(result.verdict()).isEqualTo(Validation.Verdict.WORSE);
        assertThat(result.datasetId()).isEqualTo("syn-01");
    }

    @Test
    void aProposalEqualToTheBaselineIsMarkedIdentical() {
        MatchingConfig config = MatchingConfigLoader.load(Path.of("..", "config", "matching.yml"));

        Validation.Result same = Validation.run(SyntheticDatasetAccess.dataset(), config, config, 0.95);

        assertThat(same.identical()).isTrue();
        assertThat(same.verdict()).isEqualTo(Validation.Verdict.EQUAL);
        assertThat(same.baseline()).isEqualTo(same.proposed());
        assertThat(Validation.of("test-x", CODE, score(700, 100, 99), score(700, 100, 99), 0.95).identical()).isFalse();
    }

    @Test
    void aTestDatasetIsRefusedByEveryCommandThatTunes() {
        assertThatThrownBy(() -> TestSets.requireTuningSet("test-02", "tune"))
            .isInstanceOf(EvalUserException.class)
            .hasMessageContaining("test-02").hasMessageContaining("exactly once");
        assertThatThrownBy(() -> TestSets.requireTuningSet("TEST-9", "ablate")).isInstanceOf(EvalUserException.class);
    }

    @Test
    void otherDatasetsMayBeTunedOn() {
        TestSets.requireTuningSet("tune-02", "tune");
        TestSets.requireTuningSet("demo-02", "ablate");
        TestSets.requireTuningSet("latest-test", "ablate");

        assertThat(TestSets.isTestSet("test-01")).isTrue();
        assertThat(TestSets.isTestSet("tune-01")).isFalse();
        assertThat(TestSets.isTestSet(null)).isFalse();
    }

    private static Score score(int correct, int highCovered, int highCorrect) {
        return new Score(1000, 1000, 1000, correct, correct, 0, highCovered, highCorrect, 0, 0, 0, 0, 0, 0, 10, 5, 0.5,
            1000, correct, 0, 0, 0);
    }
}
