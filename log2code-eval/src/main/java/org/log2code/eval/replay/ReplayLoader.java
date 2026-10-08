package org.log2code.eval.replay;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.log2code.core.model.Label;
import org.log2code.core.model.LogEvent;
import org.log2code.core.opensearch.IndexNames;
import org.log2code.eval.EvalUserException;
import org.log2code.eval.data.OpenSearchEvalDataSource;
import org.log2code.eval.truth.CatalogView;
import org.log2code.ingester.IngesterUserException;
import org.log2code.ingester.assemble.AssemblyContext;
import org.log2code.ingester.catalog.CatalogLoadException;
import org.log2code.ingester.cli.Wiring;
import org.log2code.ingester.manifest.DatasetManifest;
import org.log2code.ingester.manifest.ManifestException;
import org.log2code.ingester.manifest.ManifestLoader;
import org.log2code.ingester.match.MatchingConfigException;
import org.log2code.ingester.parse.LogFormatException;
import org.opensearch.client.opensearch.OpenSearchClient;

/**
 * Builds a {@link ReplayDataset} from a dataset folder and OpenSearch (read-only): the catalog and labels come
 * from the indexes, the events are re-read from the dataset's log files by the ingester's own
 * {@code EventAssembler}, wired exactly like {@code ingester ingest} wires it ({@link Wiring}).
 */
public final class ReplayLoader {

    private static final Path DATASETS_ROOT = Path.of("datasets");

    private ReplayLoader() {
    }

    /** {@code datasets/tune-02} and the bare id {@code tune-02} both name the dataset folder {@code datasets/tune-02}. */
    public static Path resolveDatasetDir(String datasetArgument) {
        Path direct = Path.of(datasetArgument);
        if (Files.isDirectory(direct)) {
            return direct;
        }
        Path underRoot = DATASETS_ROOT.resolve(datasetArgument);
        if (Files.isDirectory(underRoot)) {
            return underRoot;
        }
        throw new EvalUserException("dataset folder not found: tried " + direct.toAbsolutePath()
            + " and " + underRoot.toAbsolutePath());
    }

    /** The {@code dataset_id} a {@code --dataset} argument stands for. */
    public static String datasetId(String datasetArgument) {
        return manifest(resolveDatasetDir(datasetArgument)).datasetId();
    }

    /** @param configDir the folder holding {@code matching.yml}, {@code log-formats.yml} and {@code code-units.yml} ({@code config/} at the repo root) */
    public static ReplayDataset load(OpenSearchClient client, IndexNames indexNames, Path datasetDir, Path configDir)
            throws IOException {
        try {
            DatasetManifest manifest = manifest(datasetDir);
            Wiring.Components components = Wiring.build(client, indexNames, manifest, configDir);

            OpenSearchEvalDataSource source = new OpenSearchEvalDataSource(client, indexNames);
            CatalogView catalog = source.catalog(manifest.code());
            Map<String, Label> labels = source.labels(manifest.datasetId());

            List<LogEvent> events = new ArrayList<>();
            for (DatasetManifest.FileEntry file : manifest.files()) {
                Path logFile = datasetDir.resolve(file.path());
                if (!Files.isRegularFile(logFile)) {
                    throw new EvalUserException("dataset log file not found: " + logFile.toAbsolutePath());
                }
                AssemblyContext context = new AssemblyContext(manifest.datasetId(), file.path(), file.service(),
                    file.module(), manifest.code(), manifest.logFormat(), manifest.oracle());
                try (Stream<LogEvent> assembled = components.assembler().assemble(logFile, context)) {
                    assembled.forEach(events::add);
                }
            }
            // the same order OpenSearchEvalDataSource.events uses, so a replay lines up with an ingested dataset
            events.sort(Comparator.comparing(LogEvent::sourceFile).thenComparingInt(LogEvent::lineNumber));
            if (events.isEmpty()) {
                throw new EvalUserException("dataset '" + manifest.datasetId() + "' has no events in " + datasetDir.toAbsolutePath());
            }
            return new ReplayDataset(manifest.datasetId(), manifest.code(), components.catalogIndex(), catalog, events, labels);
        } catch (IngesterUserException | ManifestException | MatchingConfigException | LogFormatException
                 | CatalogLoadException e) {
            // all of them are configuration or input problems the ingester itself reports with exit code 1
            throw new EvalUserException(e.getMessage());
        }
    }

    private static DatasetManifest manifest(Path datasetDir) {
        try {
            return ManifestLoader.load(datasetDir.resolve("manifest.yml"));
        } catch (ManifestException e) {
            throw new EvalUserException(e.getMessage());
        }
    }
}
