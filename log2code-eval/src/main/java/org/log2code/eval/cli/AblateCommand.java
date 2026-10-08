package org.log2code.eval.cli;

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Locale;
import java.util.concurrent.Callable;
import org.log2code.core.opensearch.IndexNames;
import org.log2code.core.opensearch.OpenSearchClientFactory;
import org.log2code.core.opensearch.OpenSearchConfig;
import org.log2code.eval.EvalCli;
import org.log2code.eval.ablate.AblationResult;
import org.log2code.eval.ablate.AblationRunner;
import org.log2code.eval.ablate.Variants;
import org.log2code.eval.metrics.Score;
import org.log2code.eval.replay.ReplayDataset;
import org.log2code.eval.replay.ReplayLoader;
import org.log2code.eval.report.AblationReport;
import org.log2code.eval.tune.TestSets;
import org.log2code.ingester.match.MatchingConfig;
import org.opensearch.client.opensearch.OpenSearchClient;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;

/**
 * {@code ablate --dataset <id>} (T34 step 1): runs the matcher in memory over the dataset's events in seven
 * variants and writes {@code ablation.md} and {@code ablation.csv} to {@code <out-dir>}. OpenSearch is only read.
 */
@Command(name = "ablate", mixinStandardHelpOptions = true,
    description = "Measure what each matcher component contributes (matcher runs in memory, nothing is written to OpenSearch).")
public final class AblateCommand implements Callable<Integer> {

    @ParentCommand
    private EvalCli parent;

    @Option(names = "--dataset", required = true, description = "Tuning dataset: its id or datasets/<id>. A test dataset is refused.")
    private String dataset;

    @Option(names = "--out-dir", defaultValue = "docs/eval", description = "Output folder (default: ${DEFAULT-VALUE}).")
    private Path outDir;

    @Override
    public Integer call() throws IOException {
        TestSets.requireTuningSet(ReplayLoader.datasetId(dataset), "ablate");
        Path datasetDir = ReplayLoader.resolveDatasetDir(dataset);
        MatchingConfig base = CommandSupport.loadConfig(CommandSupport.MATCHING_CONFIG);

        OpenSearchClient client = OpenSearchClientFactory.create(OpenSearchConfig.of(parent.openSearchUrl()));
        try {
            ReplayDataset replay = ReplayLoader.load(client, new IndexNames(), datasetDir, CommandSupport.CONFIG_DIR);
            replay.requireGroundTruth();
            System.out.printf("dataset %s: %d event(s) assembled%n", replay.datasetId(), replay.size());

            AblationResult result = AblationRunner.run(replay, base, Variants.standard());
            AblationReport.write(outDir, result, replay.code(), replay.catalog(), LocalDate.now(),
                "scripts/eval.sh ablate --dataset " + replay.datasetId());

            for (AblationResult.VariantResult v : result.variants()) {
                Score s = v.score();
                System.out.printf(Locale.ROOT, "%-26s accuracy@1 %.2f%%  coverage %.1f%%  precision(high) %s (n=%d)%n",
                    v.variant().id(), s.accuracyAt1() * 100, s.coverage() * 100,
                    s.precisionHigh() == null ? "n/a" : String.format(Locale.ROOT, "%.1f%%", s.precisionHigh() * 100), s.highCovered());
            }
            System.out.println("wrote " + outDir.resolve("ablation.md"));
            return 0;
        } finally {
            OpenSearchClientFactory.close(client);
        }
    }
}
