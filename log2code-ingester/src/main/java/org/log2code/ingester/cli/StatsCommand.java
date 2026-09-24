package org.log2code.ingester.cli;

import java.io.IOException;
import java.util.concurrent.Callable;
import org.log2code.core.opensearch.IndexNames;
import org.log2code.core.opensearch.OpenSearchClientFactory;
import org.log2code.core.opensearch.OpenSearchConfig;
import org.log2code.ingester.IngesterCli;
import org.log2code.ingester.stats.DatasetStats;
import org.log2code.ingester.stats.StatsRunner;
import org.opensearch.client.opensearch.OpenSearchClient;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;

/** {@code stats --dataset <id>} (T21): OpenSearch aggregations over an already-ingested dataset. */
@Command(name = "stats", description = "Print aggregate counts for an already-ingested dataset.")
public final class StatsCommand implements Callable<Integer> {

    @ParentCommand
    private IngesterCli parent;

    @Option(names = "--dataset", required = true, description = "dataset_id.")
    private String dataset;

    @Override
    public Integer call() throws IOException {
        OpenSearchClient client = OpenSearchClientFactory.create(OpenSearchConfig.of(parent.openSearchUrl()));
        try {
            DatasetStats stats = StatsRunner.compute(client, new IndexNames(), dataset);
            print(stats);
            return 0;
        } finally {
            OpenSearchClientFactory.close(client);
        }
    }

    private void print(DatasetStats stats) {
        System.out.println("dataset: " + stats.datasetId());
        System.out.println("total: " + stats.total());
        System.out.println("by status: " + stats.byStatus());
        System.out.println("by confidence level: " + stats.byConfidenceLevel());
        System.out.println("by service: " + stats.byService());
        System.out.println("by level: " + stats.byLevel());
        System.out.println("with exception: " + stats.withException());
        System.out.println("with trace id: " + stats.withTraceId());
    }
}
