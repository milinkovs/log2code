package org.log2code.eval.tune;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.log2code.eval.metrics.Score;
import org.log2code.ingester.match.MatchingConfig;
import org.log2code.ingester.match.MatchingConfigLoader;

/** The search itself, on made-up score landscapes, so no matcher or OpenSearch is involved. */
class TunerTest {

    private static final MatchingConfig CONFIG = MatchingConfigLoader.load(Path.of("..", "config", "matching.yml"));
    private static final int LOGGER_EXACT = ParameterSpace.indexOf("logger_exact");
    private static final int LEVEL_EQUAL = ParameterSpace.indexOf("level_equal");

    /** 1000 events, correct = 800 minus the distance of logger_exact from 0.30 and of level_equal from 0.15 (in thousandths). */
    private static final Function<MatchingConfig, Score> PEAKED = config -> {
        double penalty = Math.abs(config.weights().loggerExact() - 0.30) + Math.abs(config.weights().levelEqual() - 0.15);
        return score(800 - (int) Math.round(penalty * 1000), 100, 100);
    };

    @Test
    void theFirstTrialIsTheBaselineItself() {
        Tuner.Result result = Tuner.run(CONFIG, PEAKED, new Tuner.Options(50, 1, 0.95));

        Tuner.Trial first = result.trials().get(0);
        assertThat(first.phase()).isEqualTo(Tuner.Phase.BASELINE);
        assertThat(first.values()).isEqualTo(ParameterSpace.read(CONFIG));
        assertThat(first.distance()).isZero();
        assertThat(result.baseline()).isSameAs(first);
    }

    @Test
    void neverTriesMoreCombinationsThanTheBudget() {
        for (int budget : new int[] {1, 2, 3, 10, 57, 200}) {
            Tuner.Result result = Tuner.run(CONFIG, PEAKED, new Tuner.Options(budget, 3, 0.95));

            assertThat(result.trials()).hasSizeLessThanOrEqualTo(budget);
        }
    }

    @Test
    void aBudgetOfOneEvaluatesOnlyTheBaseline() {
        Tuner.Result result = Tuner.run(CONFIG, PEAKED, new Tuner.Options(1, 3, 0.95));

        assertThat(result.trials()).hasSize(1);
        assertThat(result.best()).isSameAs(result.baseline());
    }

    @Test
    void usesBothPhasesAndSplitsTheBudgetRoughlyInHalf() {
        Tuner.Result result = Tuner.run(CONFIG, PEAKED, new Tuner.Options(200, 42, 0.95));

        assertThat(result.trials(Tuner.Phase.BASELINE)).isEqualTo(1);
        assertThat(result.trials(Tuner.Phase.RANDOM)).isEqualTo(99);
        assertThat(result.trials(Tuner.Phase.GRID)).isPositive();
        assertThat(result.trials().size()).isBetween(100, 200);
    }

    @Test
    void sameSeedSameTrialsDifferentSeedDifferentRandomTrials() {
        Tuner.Result a = Tuner.run(CONFIG, PEAKED, new Tuner.Options(120, 42, 0.95));
        Tuner.Result b = Tuner.run(CONFIG, PEAKED, new Tuner.Options(120, 42, 0.95));
        Tuner.Result c = Tuner.run(CONFIG, PEAKED, new Tuner.Options(120, 43, 0.95));

        assertThat(a.trials()).usingRecursiveComparison().isEqualTo(b.trials());
        assertThat(a.trials().get(1).values()).isNotEqualTo(c.trials().get(1).values());
    }

    @Test
    void movesTowardsABetterConfigurationWhenOneExists() {
        Tuner.Result result = Tuner.run(CONFIG, PEAKED, new Tuner.Options(200, 42, 0.95));

        assertThat(result.best().score().correctAt1()).isGreaterThan(result.baseline().score().correctAt1());
        assertThat(result.best().values()[LOGGER_EXACT]).isGreaterThan(CONFIG.weights().loggerExact());
        assertThat(result.best().feasible()).isTrue();
    }

    @Test
    void aFlatLandscapeKeepsTheBaselineBecauseEqualAccuracyNeverMovesTheWeights() {
        Tuner.Result result = Tuner.run(CONFIG, config -> score(700, 100, 100), new Tuner.Options(200, 42, 0.95));

        assertThat(result.best()).isSameAs(result.baseline());
        assertThat(result.trials().stream().filter(t -> t.score().correctAt1() == 700)).hasSize(result.trials().size());
    }

    @Test
    void anAccuracyTieIsBrokenByTheDistanceToTheBaseline() {
        Tuner.Trial near = trial(700, 0.1, true, 50);
        Tuner.Trial far = trial(700, 0.4, true, 90);

        assertThat(Tuner.better(near, far)).isTrue();
        assertThat(Tuner.better(far, near)).isFalse();
    }

    @Test
    void moreCorrectEventsWinOverCloserWeights() {
        assertThat(Tuner.better(trial(701, 0.9, true, 0), trial(700, 0.0, true, 0))).isTrue();
    }

    @Test
    void aFeasibleTrialAlwaysBeatsAnInfeasibleOne() {
        Tuner.Trial feasible = trial(100, 0.5, true, 10);
        Tuner.Trial infeasible = trial(900, 0.0, false, 10);

        assertThat(Tuner.better(feasible, infeasible)).isTrue();
        assertThat(Tuner.better(infeasible, feasible)).isFalse();
    }

    @Test
    void whenNothingIsFeasibleTheMorePreciseTrialIsCloser() {
        Tuner.Trial precise = new Tuner.Trial(1, Tuner.Phase.RANDOM, new double[18], score(600, 100, 94), false, 0.5);
        Tuner.Trial sloppy = new Tuner.Trial(2, Tuner.Phase.RANDOM, new double[18], score(900, 100, 80), false, 0.1);

        assertThat(Tuner.better(precise, sloppy)).isTrue();
    }

    @Test
    void findsAFeasibleConfigurationWhenTheBaselineViolatesTheConstraint() {
        // precision(high) is 100% only for high >= 0.80; the baseline has high = 0.75
        Function<MatchingConfig, Score> scorer = config ->
            config.thresholds().high() >= 0.80 ? score(700, 100, 100) : score(700, 100, 90);

        Tuner.Result result = Tuner.run(CONFIG, scorer, new Tuner.Options(200, 42, 0.95));

        assertThat(result.baseline().feasible()).isFalse();
        assertThat(result.best().feasible()).isTrue();
        assertThat(result.best().values()[ParameterSpace.indexOf("high")]).isGreaterThanOrEqualTo(0.80);
    }

    @Test
    void aTrialWithoutAnyHighPredictionIsNotFeasible() {
        Tuner.Result result = Tuner.run(CONFIG, config -> score(700, 0, 0), new Tuner.Options(10, 1, 0.95));

        assertThat(result.feasibleTrials()).isZero();
    }

    @Test
    void everyTrialStaysInsideTheRanges() {
        Tuner.Result result = Tuner.run(CONFIG, PEAKED, new Tuner.Options(200, 9, 0.95));

        for (Tuner.Trial trial : result.trials()) {
            for (int i = 0; i < trial.values().length; i++) {
                ParameterSpace.Parameter p = ParameterSpace.PARAMETERS.get(i);
                assertThat(trial.values()[i]).as(p.name()).isBetween(p.min() - 1e-9, p.max() + 1e-9);
            }
            assertThat(trial.values()[LEVEL_EQUAL]).isGreaterThanOrEqualTo(0.0);
        }
    }

    @Test
    void invalidOptionsAreRejected() {
        assertThatThrownBy(() -> new Tuner.Options(0, 1, 0.95)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Tuner.Options(10, 1, 1.5)).isInstanceOf(IllegalArgumentException.class);
    }

    private static Tuner.Trial trial(int correct, double distance, boolean feasible, int highCorrect) {
        return new Tuner.Trial(1, Tuner.Phase.RANDOM, new double[18], score(correct, 100, highCorrect), feasible, distance);
    }

    /** A score of 1000 evaluable events, {@code correct} of them right, {@code highCovered} predicted at level high, {@code highCorrect} rightly. */
    private static Score score(int correct, int highCovered, int highCorrect) {
        return new Score(1000, 1000, 1000, correct, correct, 0, highCovered, highCorrect, 0, 0, 0, 0, 0, 0, 10, 5, 0.5,
            1000, correct, 0, 0, 0);
    }
}
