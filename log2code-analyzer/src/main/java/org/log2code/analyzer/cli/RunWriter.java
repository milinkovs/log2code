package org.log2code.analyzer.cli;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.log2code.core.json.Json;
import org.log2code.core.model.AnalysisRun;
import org.log2code.core.opensearch.IndexManager;
import org.log2code.core.opensearch.IndexNames;
import org.opensearch.client.opensearch.OpenSearchClient;

/** Writes an {@link AnalysisRun} to {@code log2code-runs} and/or to {@code data/work/analyzer/<name>/<version>/run.json}. */
final class RunWriter {

    private static final String RUN_FILE_NAME = "run.json";

    private RunWriter() {
    }

    static void writeToOpenSearch(OpenSearchClient client, IndexNames indexNames, AnalysisRun run) throws IOException {
        new IndexManager(client, indexNames).ensureAll();
        client.index(i -> i.index(indexNames.runs()).id(run.runId()).document(run));
    }

    static Path writeToJson(Path jsonDir, AnalysisRun run) {
        Path target = jsonDir.resolve(run.codeUnit().name()).resolve(run.codeUnit().version()).resolve(RUN_FILE_NAME);
        try {
            Files.createDirectories(target.getParent());
            Json.mapper().writerWithDefaultPrettyPrinter().writeValue(target.toFile(), run);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to write " + target, e);
        }
        return target;
    }
}
