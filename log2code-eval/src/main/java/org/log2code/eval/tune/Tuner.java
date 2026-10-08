package org.log2code.eval.tune;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.function.Function;
import org.log2code.eval.metrics.Score;
import org.log2code.ingester.match.MatchingConfig;

/**
 * T34 step 2: a random search followed by a local grid search over the 18 weights and thresholds of
 * {@link ParameterSpace}, at most {@code maxCombos} combinations in total, with a fixed seed.
 *
 * <ol>
 *   <li>trial 0 is the starting configuration itself, so every other trial is compared with the real baseline;</li>
 *   <li>about half of the remaining budget goes to uniformly random vectors over the ranges;</li>
 *   <li>the rest goes to a local grid search around the best trial so far: each parameter is moved one step
 *       down and one step up, a move that improves the best trial is kept, and when a full sweep finds nothing
 *       the step is halved once.</li>
 * </ol>
 *
 * <p>A trial is <em>feasible</em> when the precision of the {@code high} confidence level is at least
 * {@link Options#minHighPrecision()}. Trials are ordered by {@link #better}: feasible first, then more correct
 * events at rank 1 (the goal), then closer to the baseline (so equal accuracy never moves the weights), and last
 * more correct events labelled {@code high}.
 * The scoring is injected so the search can be tested without OpenSearch.
 */
public final class Tuner {

    public record Options(int maxCombos, long seed, double minHighPrecision) {
        public Options {
            if (maxCombos < 1) {
                throw new IllegalArgumentException("maxCombos must be at least 1");
            }
            if (minHighPrecision < 0 || minHighPrecision > 1) {
                throw new IllegalArgumentException("minHighPrecision must be within [0, 1]");
            }
        }
    }

    public enum Phase { BASELINE, RANDOM, GRID }

    /**
     * @param values   one value per {@link ParameterSpace#PARAMETERS} entry
     * @param distance {@link ParameterSpace#distance} to the baseline vector
     */
    public record Trial(int index, Phase phase, double[] values, Score score, boolean feasible, double distance) {
    }

    /** @param best the best trial by {@link #better}; the baseline itself when nothing beat it */
    public record Result(Options options, List<Trial> trials, Trial baseline, Trial best) {

        public long trials(Phase phase) {
            return trials.stream().filter(t -> t.phase() == phase).count();
        }

        public long feasibleTrials() {
            return trials.stream().filter(Trial::feasible).count();
        }
    }

    private final MatchingConfig base;
    private final Function<MatchingConfig, Score> scorer;
    private final Options options;
    private final double[] baseValues;
    private final List<Trial> trials = new ArrayList<>();
    private final Set<String> seen = new HashSet<>();

    private Tuner(MatchingConfig base, Function<MatchingConfig, Score> scorer, Options options) {
        this.base = base;
        this.scorer = scorer;
        this.options = options;
        this.baseValues = ParameterSpace.read(base);
    }

    public static Result run(MatchingConfig base, Function<MatchingConfig, Score> scorer, Options options) {
        return new Tuner(base, scorer, options).search();
    }

    private Result search() {
        Trial baseline = evaluate(baseValues, Phase.BASELINE);
        Trial best = baseline;

        Random random = new Random(options.seed());
        int randomBudget = (options.maxCombos() - 1) / 2;
        for (int attempts = 0, done = 0; done < randomBudget && attempts < 20 * randomBudget; attempts++) {
            double[] values = ParameterSpace.random(random);
            if (!seen.contains(key(values))) {
                Trial trial = evaluate(values, Phase.RANDOM);
                done++;
                if (better(trial, best)) {
                    best = trial;
                }
            }
        }

        for (double factor : new double[] {1.0, 0.5}) {
            boolean improved = true;
            while (improved && trials.size() < options.maxCombos()) {
                improved = false;
                for (int p = 0; p < ParameterSpace.size() && trials.size() < options.maxCombos(); p++) {
                    ParameterSpace.Parameter parameter = ParameterSpace.PARAMETERS.get(p);
                    double step = Math.max(parameter.unit(), ParameterSpace.round(parameter.step() * factor, parameter.unit()));
                    for (int direction : new int[] {-1, 1}) {
                        if (trials.size() >= options.maxCombos()) {
                            break;
                        }
                        double[] moved = best.values().clone();
                        moved[p] += direction * step;
                        moved = ParameterSpace.repair(moved);
                        if (seen.contains(key(moved))) {
                            continue;
                        }
                        Trial trial = evaluate(moved, Phase.GRID);
                        if (better(trial, best)) {
                            best = trial;
                            improved = true;
                        }
                    }
                }
            }
        }
        return new Result(options, List.copyOf(trials), baseline, best);
    }

    private Trial evaluate(double[] values, Phase phase) {
        seen.add(key(values));
        Score score = scorer.apply(ParameterSpace.apply(base, values));
        Double precision = score.precisionHigh();
        boolean feasible = precision != null && precision >= options.minHighPrecision();
        Trial trial = new Trial(trials.size(), phase, values.clone(), score, feasible, ParameterSpace.distance(values, baseValues));
        trials.add(trial);
        return trial;
    }

    /** Whether {@code a} is a better configuration than {@code b}; see the class comment for the order. */
    public static boolean better(Trial a, Trial b) {
        if (a.feasible() != b.feasible()) {
            return a.feasible();
        }
        if (!a.feasible()) {
            // neither meets the precision constraint: the more precise one is closer to meeting it
            double pa = a.score().precisionHigh() == null ? -1 : a.score().precisionHigh();
            double pb = b.score().precisionHigh() == null ? -1 : b.score().precisionHigh();
            if (pa != pb) {
                return pa > pb;
            }
        }
        if (a.score().correctAt1() != b.score().correctAt1()) {
            return a.score().correctAt1() > b.score().correctAt1();
        }
        if (a.distance() != b.distance()) {
            return a.distance() < b.distance();
        }
        return a.score().highCorrect() > b.score().highCorrect();
    }

    private static String key(double[] values) {
        return Arrays.toString(values);
    }
}
