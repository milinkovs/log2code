package org.log2code.eval.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Locale;
import java.util.concurrent.Callable;
import org.log2code.core.opensearch.IndexNames;
import org.log2code.core.opensearch.OpenSearchClientFactory;
import org.log2code.core.opensearch.OpenSearchConfig;
import org.log2code.eval.EvalCli;
import org.log2code.eval.EvalUserException;
import org.log2code.eval.metrics.Score;
import org.log2code.eval.replay.ReplayDataset;
import org.log2code.eval.replay.ReplayLoader;
import org.log2code.eval.report.TuningWriter;
import org.log2code.eval.tune.Validation;
import org.log2code.ingester.match.MatchingConfig;
import org.opensearch.client.opensearch.OpenSearchClient;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;

/**
 * {@code validate --dataset <test-id>} (T34 step 3): scores the baseline and the proposed configuration of the last
 * {@code tune} on the held-out dataset and completes {@code tuning.md}. It writes {@code <out-dir>/<dataset>/validation.json}, and
 * refuses to run again while that file exists: the test dataset is evaluated exactly once.
 */
@Command(name = "validate", mixinStandardHelpOptions = true,
    description = "Compare the baseline and the tuned weights on the test dataset. Runs once per dataset.")
public final class ValidateCommand implements Callable<Integer> {

    @ParentCommand
    private EvalCli parent;

    @Option(names = "--dataset", required = true, description = "Test dataset: its id or datasets/<id>.")
    private String dataset;

    @Option(names = "--out-dir", defaultValue = "docs/eval", description = "Where 'tune' wrote its files (default: ${DEFAULT-VALUE}).")
    private Path outDir;

    @Option(names = "--baseline", description = "Baseline matching.yml (default: <out-dir>/tuning/matching.baseline.yml, the file 'tune' started from).")
    private Path baseline;

    @Option(names = "--proposal", description = "Proposed matching.yml (default: <out-dir>/tuning/matching.proposed.yml).")
    private Path proposal;

    @Option(names = "--min-high-precision", defaultValue = "0.95", description = "Required precision of the 'high' confidence level (default: ${DEFAULT-VALUE}).")
    private double minHighPrecision;

    @Override
    public Integer call() throws IOException {
        String datasetId = ReplayLoader.datasetId(dataset);
        TuningWriter.requireNotValidated(outDir, datasetId);
        Path baselineFile = baseline != null ? baseline : TuningWriter.baselineFile(outDir);
        Path proposalFile = proposal != null ? proposal : TuningWriter.proposedFile(outDir);
        for (Path file : new Path[] {baselineFile, proposalFile}) {
            if (!Files.isRegularFile(file)) {
                throw new EvalUserException("file not found: " + file.toAbsolutePath() + "; run 'scripts/eval.sh tune --dataset <tuning-set>' first.");
            }
        }
        MatchingConfig baselineConfig = CommandSupport.loadConfig(baselineFile);
        MatchingConfig proposedConfig = CommandSupport.loadConfig(proposalFile);
        Path datasetDir = ReplayLoader.resolveDatasetDir(dataset);

        OpenSearchClient client = OpenSearchClientFactory.create(OpenSearchConfig.of(parent.openSearchUrl()));
        try {
            ReplayDataset replay = ReplayLoader.load(client, new IndexNames(), datasetDir, CommandSupport.CONFIG_DIR);
            replay.requireGroundTruth();
            System.out.printf("dataset %s: %d event(s) assembled%n", replay.datasetId(), replay.size());

            Validation.Result result = Validation.run(replay, baselineConfig, proposedConfig, minHighPrecision);
            TuningWriter.writeValidation(outDir, result, LocalDate.now(), "scripts/eval.sh validate --dataset " + replay.datasetId());

            print("baseline", result.baseline());
            print("proposed", result.proposed());
            String effect = result.identical() ? "equals the baseline, config/matching.yml stays"
                : result.notWorse() ? "may replace config/matching.yml" : "must NOT replace config/matching.yml";
            System.out.printf(Locale.ROOT, "verdict: %s, high-precision constraint %s -> proposal %s%n",
                result.verdict(), result.constraintMet() ? "met" : "NOT met", effect);
            System.out.println("wrote " + outDir.resolve("tuning.md") + " and " + TuningWriter.validationFile(outDir, datasetId));
            return 0;
        } finally {
            OpenSearchClientFactory.close(client);
        }
    }

    private static void print(String label, Score s) {
        System.out.printf(Locale.ROOT, "%s: accuracy@1 %.2f%% (%d/%d)  coverage %.1f%%  precision(high) %s (n=%d)%n",
            label, s.accuracyAt1() * 100, s.correctAt1(), s.evaluable(), s.coverage() * 100,
            s.precisionHigh() == null ? "n/a" : String.format(Locale.ROOT, "%.1f%%", s.precisionHigh() * 100), s.highCovered());
    }
}
