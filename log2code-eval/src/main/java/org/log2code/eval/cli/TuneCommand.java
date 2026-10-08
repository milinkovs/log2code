package org.log2code.eval.cli;

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import org.log2code.core.opensearch.IndexNames;
import org.log2code.core.opensearch.OpenSearchClientFactory;
import org.log2code.core.opensearch.OpenSearchConfig;
import org.log2code.eval.EvalCli;
import org.log2code.eval.metrics.Score;
import org.log2code.eval.replay.ReplayDataset;
import org.log2code.eval.replay.ReplayLoader;
import org.log2code.eval.report.TuningReport;
import org.log2code.eval.report.TuningWriter;
import org.log2code.eval.tune.TestSets;
import org.log2code.eval.tune.Tuner;
import org.log2code.ingester.match.CandidateMode;
import org.log2code.ingester.match.MatchingConfig;
import org.opensearch.client.opensearch.OpenSearchClient;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;

/**
 * {@code tune --dataset <id>} (T34 step 2): random and local grid search over the weights and thresholds,
 * scored by running the matcher in memory. Writes {@code tuning.md} and {@code tuning/} (trials, the baseline and the
 * proposed {@code matching.yml}) to {@code <out-dir>}; {@code config/matching.yml} is never changed.
 */
@Command(name = "tune", mixinStandardHelpOptions = true,
    description = "Search weights and thresholds that maximize accuracy@1 while precision(high) stays above a floor.")
public final class TuneCommand implements Callable<Integer> {

    @ParentCommand
    private EvalCli parent;

    @Option(names = "--dataset", required = true, description = "Tuning dataset: its id or datasets/<id>. A test dataset is refused.")
    private String dataset;

    @Option(names = "--out-dir", defaultValue = "docs/eval", description = "Output folder (default: ${DEFAULT-VALUE}).")
    private Path outDir;

    @Option(names = "--seed", defaultValue = "42", description = "Seed of the random search (default: ${DEFAULT-VALUE}).")
    private long seed;

    @Option(names = "--max-combos", defaultValue = "200", description = "Most combinations to try, including the baseline (default: ${DEFAULT-VALUE}).")
    private int maxCombos;

    @Option(names = "--min-high-precision", defaultValue = "0.95", description = "Required precision of the 'high' confidence level (default: ${DEFAULT-VALUE}).")
    private double minHighPrecision;

    @Override
    public Integer call() throws IOException {
        TestSets.requireTuningSet(ReplayLoader.datasetId(dataset), "tune");
        if (maxCombos < 1 || maxCombos > 1000) {
            throw new org.log2code.eval.EvalUserException("--max-combos must be between 1 and 1000");
        }
        if (minHighPrecision < 0 || minHighPrecision > 1) {
            throw new org.log2code.eval.EvalUserException("--min-high-precision must be between 0 and 1");
        }
        Path datasetDir = ReplayLoader.resolveDatasetDir(dataset);
        MatchingConfig base = CommandSupport.loadConfig(CommandSupport.MATCHING_CONFIG);

        OpenSearchClient client = OpenSearchClientFactory.create(OpenSearchConfig.of(parent.openSearchUrl()));
        try {
            ReplayDataset replay = ReplayLoader.load(client, new IndexNames(), datasetDir, CommandSupport.CONFIG_DIR);
            replay.requireGroundTruth();
            System.out.printf("dataset %s: %d event(s) assembled, searching up to %d combination(s), seed %d%n",
                replay.datasetId(), replay.size(), maxCombos, seed);

            long started = System.nanoTime();
            Tuner.Result result = Tuner.run(base, config -> replay.score(config, CandidateMode.BOTH),
                new Tuner.Options(maxCombos, seed, minHighPrecision));
            long seconds = (System.nanoTime() - started) / 1_000_000_000L;

            List<String> dominant = TuningReport.describeStatements(replay.catalog(), replay.dominantTruthKeys());
            TuningWriter.writeSearch(outDir, result, base, replay.datasetId(), replay.code(), dominant, LocalDate.now(),
                "scripts/eval.sh tune --dataset " + replay.datasetId() + " --seed " + seed + " --max-combos " + maxCombos);

            print("baseline", result.baseline());
            print("best    ", result.best());
            System.out.printf(Locale.ROOT, "%d trial(s) in %d s, %d feasible; proposal: %s%n", result.trials().size(), seconds,
                result.feasibleTrials(), TuningWriter.proposedFile(outDir));
            return 0;
        } finally {
            OpenSearchClientFactory.close(client);
        }
    }

    private static void print(String label, Tuner.Trial trial) {
        Score s = trial.score();
        System.out.printf(Locale.ROOT, "%s trial %3d: accuracy@1 %.2f%% (%d/%d)  coverage %.1f%%  precision(high) %s (n=%d)%n",
            label, trial.index(), s.accuracyAt1() * 100, s.correctAt1(), s.evaluable(), s.coverage() * 100,
            s.precisionHigh() == null ? "n/a" : String.format(Locale.ROOT, "%.1f%%", s.precisionHigh() * 100), s.highCovered());
    }
}
