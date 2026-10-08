package org.log2code.eval.tune;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.log2code.ingester.match.MatchingConfig;
import org.log2code.ingester.match.MatchingConfigLoader;

class ParameterSpaceTest {

    private static final MatchingConfig CONFIG = MatchingConfigLoader.load(Path.of("..", "config", "matching.yml"));

    @Test
    void thereAreEighteenParametersWithUniqueNames() {
        Set<String> names = new HashSet<>();
        ParameterSpace.PARAMETERS.forEach(p -> names.add(p.name()));

        assertThat(ParameterSpace.size()).isEqualTo(18);
        assertThat(names).hasSize(18);
    }

    @Test
    void theShippedConfigurationLiesInsideEveryRange() {
        double[] values = ParameterSpace.read(CONFIG);

        for (int i = 0; i < values.length; i++) {
            ParameterSpace.Parameter p = ParameterSpace.PARAMETERS.get(i);
            assertThat(values[i]).as(p.name()).isBetween(p.min(), p.max());
        }
    }

    @Test
    void readThenApplyReproducesTheConfiguration() {
        MatchingConfig again = ParameterSpace.apply(CONFIG, ParameterSpace.read(CONFIG));

        assertThat(again).isEqualTo(CONFIG);
    }

    @Test
    void applyKeepsCandidateLimitsAndTheOracleList() {
        double[] values = ParameterSpace.read(CONFIG);
        values[ParameterSpace.indexOf("regex_full")] = 0.5;

        MatchingConfig changed = ParameterSpace.apply(CONFIG, values);

        assertThat(changed.weights().regexFull()).isEqualTo(0.5);
        assertThat(changed.candidates()).isEqualTo(CONFIG.candidates());
        assertThat(changed.oracle()).isEqualTo(CONFIG.oracle());
    }

    @Test
    void repairClampsToTheRangeAndRoundsToTheGrid() {
        double[] values = ParameterSpace.read(CONFIG);
        values[ParameterSpace.indexOf("logger_exact")] = 0.9;        // above 0.35
        values[ParameterSpace.indexOf("level_conflict")] = 0.123;    // a penalty cannot be positive
        values[ParameterSpace.indexOf("min_score")] = 0.3500000001;  // binary noise

        double[] repaired = ParameterSpace.repair(values);

        assertThat(repaired[ParameterSpace.indexOf("logger_exact")]).isEqualTo(0.35);
        assertThat(repaired[ParameterSpace.indexOf("level_conflict")]).isEqualTo(0.0);
        assertThat(repaired[ParameterSpace.indexOf("min_score")]).isEqualTo(0.35);
    }

    @Test
    void repairKeepsPrefixBelowFullAndMediumBelowHigh() {
        double[] values = ParameterSpace.read(CONFIG);
        values[ParameterSpace.indexOf("regex_full")] = 0.30;
        values[ParameterSpace.indexOf("regex_prefix")] = 0.35;
        values[ParameterSpace.indexOf("high")] = 0.60;
        values[ParameterSpace.indexOf("medium")] = 0.70;

        double[] repaired = ParameterSpace.repair(values);

        assertThat(repaired[ParameterSpace.indexOf("regex_prefix")]).isEqualTo(0.30);
        assertThat(repaired[ParameterSpace.indexOf("medium")]).isEqualTo(0.55);
    }

    @Test
    void randomVectorsAreValidAndReproducible() {
        Random first = new Random(7);
        Random second = new Random(7);

        for (int n = 0; n < 200; n++) {
            double[] a = ParameterSpace.random(first);
            double[] b = ParameterSpace.random(second);
            assertThat(a).isEqualTo(b);
            assertThat(ParameterSpace.repair(a)).isEqualTo(a);
            assertThat(a[ParameterSpace.indexOf("regex_prefix")]).isLessThanOrEqualTo(a[ParameterSpace.indexOf("regex_full")]);
            assertThat(a[ParameterSpace.indexOf("medium")] + 0.05).isLessThanOrEqualTo(a[ParameterSpace.indexOf("high")] + 1e-9);
        }
    }

    @Test
    void distanceIsZeroForEqualVectorsAndGrowsWithTheChange() {
        double[] base = ParameterSpace.read(CONFIG);
        double[] moved = base.clone();
        moved[ParameterSpace.indexOf("logger_exact")] += 0.15;   // half the 0.30 range of that parameter

        assertThat(ParameterSpace.distance(base, base)).isZero();
        assertThat(ParameterSpace.distance(base, moved)).isEqualTo(0.5 / 18, org.assertj.core.api.Assertions.within(1e-9));
    }

    @Test
    void roundDropsBinaryNoise() {
        assertThat(ParameterSpace.round(0.35000000000000003, 0.01)).isEqualTo(0.35);
        assertThat(ParameterSpace.round(0.0249, 0.005)).isEqualTo(0.025);
        assertThat(ParameterSpace.round(14.6, 1)).isEqualTo(15.0);
    }

    @Test
    void anUnknownNameIsRejected() {
        assertThatThrownBy(() -> ParameterSpace.indexOf("nope")).isInstanceOf(IllegalArgumentException.class);
    }
}
