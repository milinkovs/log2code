package org.log2code.eval.tune;

import java.math.BigDecimal;
import java.util.List;
import java.util.Random;
import java.util.function.ToDoubleFunction;
import org.log2code.ingester.match.MatchingConfig;

/**
 * The 18 numbers of {@code config/matching.yml} that T34 may change - the 13 weights of 0.10 step 3 and the
 * 5 thresholds of step 4 - as a vector, with the range each may take in a search. The candidate limits
 * ({@code top_k_tokens}, {@code max_candidates}) and the oracle's caller list are not weights or thresholds
 * and are never touched: the first would change candidate generation, the second the ground truth.
 *
 * <p>{@link #apply} is pure; {@link #repair} is what keeps a searched vector meaningful (inside its range,
 * on the parameter's grid, {@code regex_prefix <= regex_full}, {@code medium < high}).
 */
public final class ParameterSpace {

    /**
     * @param min  lowest value a search tries (the weights that are penalties are {@code <= 0})
     * @param max  highest value a search tries
     * @param unit grid every searched value is rounded to
     * @param step distance of one local-search move
     */
    public record Parameter(String name, double min, double max, double unit, double step,
                            ToDoubleFunction<MatchingConfig> read) {
    }

    public static final List<Parameter> PARAMETERS = List.of(
        new Parameter("regex_full", 0.30, 0.60, 0.01, 0.05, c -> c.weights().regexFull()),
        new Parameter("regex_prefix", 0.05, 0.35, 0.01, 0.05, c -> c.weights().regexPrefix()),
        new Parameter("specificity_max", 0.00, 0.30, 0.01, 0.05, c -> c.weights().specificityMax()),
        new Parameter("specificity_k", 5, 40, 1, 5, c -> c.weights().specificityK()),
        new Parameter("logger_exact", 0.05, 0.35, 0.01, 0.05, c -> c.weights().loggerExact()),
        new Parameter("logger_hierarchy", 0.05, 0.30, 0.01, 0.05, c -> c.weights().loggerHierarchy()),
        new Parameter("logger_abbrev_multi", 0.02, 0.25, 0.01, 0.05, c -> c.weights().loggerAbbrevMulti()),
        new Parameter("logger_conflict", -0.50, 0.00, 0.01, 0.05, c -> c.weights().loggerConflict()),
        new Parameter("level_equal", 0.00, 0.25, 0.01, 0.05, c -> c.weights().levelEqual()),
        new Parameter("level_dynamic", 0.00, 0.10, 0.005, 0.02, c -> c.weights().levelDynamic()),
        new Parameter("level_conflict", -0.50, 0.00, 0.01, 0.05, c -> c.weights().levelConflict()),
        new Parameter("throwable_consistent", 0.00, 0.15, 0.005, 0.025, c -> c.weights().throwableConsistent()),
        new Parameter("throwable_inconsistent", -0.15, 0.00, 0.005, 0.025, c -> c.weights().throwableInconsistent()),
        new Parameter("min_score", 0.15, 0.55, 0.01, 0.05, c -> c.thresholds().minScore()),
        new Parameter("ambiguity_margin", 0.00, 0.15, 0.005, 0.025, c -> c.thresholds().ambiguityMargin()),
        new Parameter("ambiguous_penalty", 0.40, 0.90, 0.01, 0.05, c -> c.thresholds().ambiguousPenalty()),
        new Parameter("high", 0.60, 0.90, 0.01, 0.05, c -> c.thresholds().high()),
        new Parameter("medium", 0.35, 0.70, 0.01, 0.05, c -> c.thresholds().medium()));

    private static final int REGEX_FULL = 0;
    private static final int REGEX_PREFIX = 1;
    private static final int HIGH = 16;
    private static final int MEDIUM = 17;
    /** {@code medium} stays at least this far below {@code high}. */
    private static final double LEVEL_GAP = 0.05;

    private ParameterSpace() {
    }

    public static int size() {
        return PARAMETERS.size();
    }

    public static int indexOf(String name) {
        for (int i = 0; i < PARAMETERS.size(); i++) {
            if (PARAMETERS.get(i).name().equals(name)) {
                return i;
            }
        }
        throw new IllegalArgumentException("unknown parameter: " + name);
    }

    public static double[] read(MatchingConfig config) {
        double[] values = new double[PARAMETERS.size()];
        for (int i = 0; i < values.length; i++) {
            values[i] = PARAMETERS.get(i).read().applyAsDouble(config);
        }
        return values;
    }

    /** {@code base} with its weights and thresholds replaced by {@code values}; candidate limits and oracle list are kept. */
    public static MatchingConfig apply(MatchingConfig base, double[] values) {
        MatchingConfig.Weights weights = new MatchingConfig.Weights(values[0], values[1], values[2], values[3], values[4],
            values[5], values[6], values[7], values[8], values[9], values[10], values[11], values[12]);
        MatchingConfig.Thresholds thresholds = new MatchingConfig.Thresholds(values[13], values[14], values[15],
            values[16], values[17]);
        return new MatchingConfig(weights, thresholds, base.candidates(), base.oracle());
    }

    /** A uniformly random vector on the grid, repaired. */
    public static double[] random(Random random) {
        double[] values = new double[PARAMETERS.size()];
        for (int i = 0; i < values.length; i++) {
            Parameter p = PARAMETERS.get(i);
            long cells = Math.round((p.max() - p.min()) / p.unit());
            values[i] = p.min() + random.nextLong(cells + 1) * p.unit();
        }
        return repair(values);
    }

    /** Inside the range, on the grid, {@code regex_prefix <= regex_full} and {@code medium + 0.05 <= high}. */
    public static double[] repair(double[] values) {
        double[] repaired = new double[values.length];
        for (int i = 0; i < values.length; i++) {
            Parameter p = PARAMETERS.get(i);
            repaired[i] = round(Math.max(p.min(), Math.min(p.max(), values[i])), p.unit());
        }
        if (repaired[REGEX_PREFIX] > repaired[REGEX_FULL]) {
            repaired[REGEX_PREFIX] = repaired[REGEX_FULL];
        }
        if (repaired[MEDIUM] > repaired[HIGH] - LEVEL_GAP) {
            repaired[MEDIUM] = round(Math.max(PARAMETERS.get(MEDIUM).min(), repaired[HIGH] - LEVEL_GAP), PARAMETERS.get(MEDIUM).unit());
        }
        return repaired;
    }

    /** Mean over parameters of |a - b| / range: 0 for equal vectors, 1 when every parameter is at the opposite end. */
    public static double distance(double[] a, double[] b) {
        double sum = 0;
        for (int i = 0; i < a.length; i++) {
            Parameter p = PARAMETERS.get(i);
            sum += Math.abs(a[i] - b[i]) / (p.max() - p.min());
        }
        return sum / a.length;
    }

    /** {@code value} rounded to a multiple of {@code unit}, without binary noise ({@code 0.35}, not {@code 0.35000000000000003}). */
    public static double round(double value, double unit) {
        long cells = Math.round(value / unit);
        return BigDecimal.valueOf(cells).multiply(BigDecimal.valueOf(unit)).doubleValue();
    }
}
