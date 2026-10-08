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
import org.log2code.eval.EvalResult;
import org.log2code.eval.EvalRunner;
import org.log2code.eval.data.OpenSearchEvalDataSource;
import org.log2code.eval.metrics.EvalMetrics;
import org.log2code.eval.report.ReportWriter;
import org.opensearch.client.opensearch.OpenSearchClient;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;

/**
 * {@code run --dataset <id>} (T33): reads the ingested events of a dataset and the catalog from OpenSearch,
 * computes the metrics and writes {@code report.md}, {@code metrics.json}, {@code errors.csv} and
 * {@code per_statement.csv} to {@code <out-dir>/<dataset>/}. Nothing is written to OpenSearch.
 */
@Command(name = "run", mixinStandardHelpOptions = true, description = "Evaluate an ingested dataset and write the report to docs/eval/<dataset>/.")
public final class RunCommand implements Callable<Integer> {

    @ParentCommand
    private EvalCli parent;

    @Option(names = "--dataset", required = true, description = "dataset_id of an ingested dataset (a datasets/<id> path also works).")
    private String dataset;

    @Option(names = "--out-dir", defaultValue = "docs/eval", description = "Output root; files go to <out-dir>/<dataset>/ (default: ${DEFAULT-VALUE}).")
    private Path outDir;

    @Option(names = "--seed", defaultValue = "42", description = "Seed of the random error sample (default: ${DEFAULT-VALUE}).")
    private long seed;

    @Option(names = "--error-samples", defaultValue = "25", description = "Number of sampled errors (default: ${DEFAULT-VALUE}).")
    private int errorSamples;

    @Override
    public Integer call() throws IOException {
        String datasetId = datasetId(dataset);
        OpenSearchClient client = OpenSearchClientFactory.create(OpenSearchConfig.of(parent.openSearchUrl()));
        try {
            EvalResult result = EvalRunner.run(new OpenSearchEvalDataSource(client, new IndexNames()), datasetId, errorSamples, seed);
            Path written = ReportWriter.write(outDir, result, LocalDate.now(), "scripts/eval.sh run --dataset " + datasetId);
            printSummary(result.metrics());
            System.out.println("wrote report to " + written);
            return 0;
        } finally {
            OpenSearchClientFactory.close(client);
        }
    }

    /** {@code datasets/tune-02} and {@code tune-02} both mean the dataset {@code tune-02}. */
    static String datasetId(String argument) {
        Path name = Path.of(argument).getFileName();
        return name == null ? argument : name.toString();
    }

    private static void printSummary(EvalMetrics metrics) {
        EvalMetrics.Counts c = metrics.counts();
        EvalMetrics.Headline h = metrics.headline();
        System.out.printf("dataset %s: %d event(s), %d evaluable (%d not in catalog, %d without ground truth)%n",
            metrics.datasetId(), c.totalEvents(), c.evaluable(), c.truthNotInCatalog(), c.truthNone());
        System.out.printf("coverage %s, accuracy@1 %s, accuracy@3 %s, ambiguous %s%n",
            pct(h.coverage()), pct(h.accuracyAt1()), pct(h.accuracyAt3()), pct(h.ambiguousShare()));
    }

    private static String pct(Double value) {
        return value == null ? "n/a" : String.format(Locale.ROOT, "%.1f%%", value * 100);
    }
}
