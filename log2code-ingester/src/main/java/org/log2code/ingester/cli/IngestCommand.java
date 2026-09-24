package org.log2code.ingester.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import org.log2code.core.json.Json;
import org.log2code.core.opensearch.IndexNames;
import org.log2code.core.opensearch.OpenSearchClientFactory;
import org.log2code.core.opensearch.OpenSearchConfig;
import org.log2code.ingester.IngesterCli;
import org.log2code.ingester.ingest.IngestReport;
import org.log2code.ingester.ingest.IngestRunner;
import org.log2code.ingester.manifest.DatasetManifest;
import org.log2code.ingester.manifest.ManifestLoader;
import org.opensearch.client.opensearch.OpenSearchClient;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;

/**
 * {@code ingest --dataset <folder> [--recreate-dataset] [--batch 1000]} (T21): loads the dataset
 * manifest, matches every event against the catalog, enriches and writes it to {@code log2code-logs},
 * then prints and saves a summary report ({@code data/work/ingest/<dataset>/report.json}, step 5).
 */
@Command(name = "ingest", description = "Parse, match and enrich a dataset's log files, writing the result to log2code-logs.")
public final class IngestCommand implements Callable<Integer> {

    @ParentCommand
    private IngesterCli parent;

    @Option(names = "--dataset", required = true, description = "Dataset folder (e.g. datasets/smoke-01) or bare dataset id.")
    private String dataset;

    @Option(names = "--recreate-dataset", description = "Delete existing log2code-logs documents for this dataset_id before ingesting.")
    private boolean recreateDataset;

    @Option(names = "--batch", defaultValue = "1000", description = "Bulk write batch size (default: ${DEFAULT-VALUE}).")
    private int batch;

    @Override
    public Integer call() throws IOException {
        Path datasetDir = DatasetPaths.resolve(dataset);
        DatasetManifest manifest = ManifestLoader.load(datasetDir.resolve("manifest.yml"));

        OpenSearchClient client = OpenSearchClientFactory.create(OpenSearchConfig.of(parent.openSearchUrl()));
        try {
            IndexNames indexNames = new IndexNames();
            Wiring.Components components = Wiring.build(client, indexNames, manifest);

            IngestReport report = IngestRunner.run(client, indexNames, manifest, datasetDir,
                components.assembler(), components.catalogIndex(), components.matcher(), components.frameResolver(),
                recreateDataset, batch);

            writeReport(report);
            printSummary(report);
            return 0;
        } finally {
            OpenSearchClientFactory.close(client);
        }
    }

    private void writeReport(IngestReport report) throws IOException {
        Path dir = Path.of("data/work/ingest", report.datasetId());
        Files.createDirectories(dir);
        Path file = dir.resolve("report.json");
        Json.mapper().writerWithDefaultPrettyPrinter().writeValue(file.toFile(), report);
        System.out.println("wrote report to " + file);
    }

    private void printSummary(IngestReport report) {
        System.out.printf("ingested %d event(s) for dataset %s in %d ms (%.1f events/s)%n",
            report.totalEvents(), report.datasetId(), report.durationMs(), report.eventsPerSecond());
        System.out.println("by status: " + report.byStatus());
        System.out.println("by confidence level: " + report.byConfidenceLevel());
        System.out.println("by service: " + report.byService());
        System.out.println("by level: " + report.byLevel());
        System.out.println("with exception: " + report.withException() + ", with trace id: " + report.withTraceId());
    }
}
