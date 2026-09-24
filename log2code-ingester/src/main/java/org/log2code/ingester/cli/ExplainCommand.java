package org.log2code.ingester.cli;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import org.log2code.core.opensearch.IndexNames;
import org.log2code.core.opensearch.OpenSearchClientFactory;
import org.log2code.core.opensearch.OpenSearchConfig;
import org.log2code.ingester.IngesterCli;
import org.log2code.ingester.IngesterUserException;
import org.log2code.ingester.explain.ExplainRunner;
import org.log2code.ingester.manifest.DatasetManifest;
import org.log2code.ingester.manifest.ManifestLoader;
import org.opensearch.client.opensearch.OpenSearchClient;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;

/** {@code explain --dataset <folder> --at <fajl>:<linija>} (T21): prints {@code Matcher.explain} for one event. */
@Command(name = "explain", description = "Print the matcher's evaluation for the event at a given file:line.")
public final class ExplainCommand implements Callable<Integer> {

    @ParentCommand
    private IngesterCli parent;

    @Option(names = "--dataset", required = true, description = "Dataset folder (e.g. datasets/smoke-01) or bare dataset id.")
    private String dataset;

    @Option(names = "--at", required = true, description = "<file>:<line>, e.g. logs/customers-service.log.gz:42 (or a service name in place of the file path).")
    private String at;

    @Override
    public Integer call() throws IOException {
        int colon = at.lastIndexOf(':');
        if (colon < 0) {
            throw new IngesterUserException("--at must be <file>:<line>, got: \"" + at + "\"");
        }
        String fileArg = at.substring(0, colon);
        int line = parseLine(at.substring(colon + 1));

        Path datasetDir = DatasetPaths.resolve(dataset);
        DatasetManifest manifest = ManifestLoader.load(datasetDir.resolve("manifest.yml"));

        OpenSearchClient client = OpenSearchClientFactory.create(OpenSearchConfig.of(parent.openSearchUrl()));
        try {
            IndexNames indexNames = new IndexNames();
            Wiring.Components components = Wiring.build(client, indexNames, manifest);
            String explanation = ExplainRunner.explain(manifest, datasetDir, components.assembler(),
                components.matcher(), fileArg, line);
            System.out.println(explanation);
            return 0;
        } finally {
            OpenSearchClientFactory.close(client);
        }
    }

    private static int parseLine(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new IngesterUserException("--at line number must be an integer, got: \"" + value + "\"");
        }
    }
}
