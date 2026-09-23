package org.log2code.analyzer.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import org.log2code.analyzer.AnalyzerCli;
import org.log2code.analyzer.AnalyzerVersion;
import org.log2code.analyzer.CliUserException;
import org.log2code.analyzer.config.AnalyzerConfig;
import org.log2code.analyzer.config.AnalyzerConfigLoader;
import org.log2code.analyzer.deps.DepsManifest;
import org.log2code.core.json.Json;
import org.log2code.core.opensearch.IndexNames;
import org.log2code.core.opensearch.OpenSearchClientFactory;
import org.log2code.core.opensearch.OpenSearchConfig;
import org.opensearch.client.opensearch.OpenSearchClient;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;

/**
 * {@code deps analyze} (T14): builds the log2code catalog for every {@code selected} dependency
 * artifact from {@code data/work/deps/deps-manifest.json} (written by {@code deps resolve}, T12), in
 * syntax-only mode, straight from each {@code -sources.jar}. A thin picocli wrapper - all of the actual
 * work is {@link DepsAnalyzeRunner} (path/CLI-free, directly testable).
 */
@Command(name = "analyze", description = "Analyze every selected dependency's sources (T14): builds the catalog per artifact.")
public final class DepsAnalyzeCommand implements Callable<Integer> {

    private static final Path MANIFEST_FILE = Path.of("data/work/deps/deps-manifest.json");
    private static final Path REPORT_FILE = Path.of("docs/analysis/deps-report.md");

    @ParentCommand
    private DepsCommand depsParent;

    @Option(names = "--force", description = "Re-analyze every selected artifact, even ones with an existing run for this analyzer version.")
    private boolean force;

    @Option(names = "--threads", defaultValue = "1",
        description = "Accepted for the T14 CLI contract, but artifacts are always analyzed sequentially in this "
            + "version (see docs/progress.md T14 Odstupanja): a dependency's whole sources jar is held in memory "
            + "while it is analyzed (ADR-008 needs a whole-code-unit view), so running artifacts concurrently "
            + "would multiply peak heap right where T14's own budget (ANALYZER_XMX=1536m, AC3) is tightest.")
    private int threads;

    @Option(names = "--artifact", description = "Only analyze this g:a:v (must be a selected artifact in the manifest).")
    private String artifact;

    @Override
    public Integer call() throws IOException {
        if (threads > 1) {
            System.err.println("note: --threads " + threads + " accepted but ignored; artifacts are analyzed sequentially (see --help).");
        }
        if (!Files.isRegularFile(MANIFEST_FILE)) {
            throw new CliUserException(MANIFEST_FILE + " not found; run 'analyzer deps resolve' first.");
        }

        AnalyzerCli parent = depsParent.parent();
        AnalyzerConfig config = AnalyzerConfigLoader.load(parent.configPath());
        DepsManifest manifest = Json.mapper().readValue(MANIFEST_FILE.toFile(), DepsManifest.class);
        OutputMode out = parent.out();

        OpenSearchClient client = null;
        try {
            if (out.writesToOpenSearch()) {
                String url = parent.openSearchUrl() != null ? parent.openSearchUrl() : config.opensearch().url();
                client = OpenSearchClientFactory.create(OpenSearchConfig.of(url));
            }

            DepsAnalyzeRunner.Summary summary = DepsAnalyzeRunner.run(client, new IndexNames(), out, parent.jsonDir(),
                REPORT_FILE, manifest, force, artifact, AnalyzerVersion.current(),
                config.context().snippetLines(), config.context().maxPrecedingStatements());

            long analyzed = summary.outcomes().stream().filter(o -> !o.skipped() && !o.sourcesMissing()).count();
            long skipped = summary.outcomes().stream().filter(DepsAnalyzeRunner.ArtifactOutcome::skipped).count();
            System.out.printf("%ntotal: %d artifact(s) analyzed, %d skipped, %d statement(s) in the catalog, "
                    + "max heap %d MB, report at %s%n",
                analyzed, skipped, summary.totalStatements(), summary.maxHeapBytes() / (1024 * 1024), REPORT_FILE);
            return 0;
        } finally {
            if (client != null) {
                OpenSearchClientFactory.close(client);
            }
        }
    }
}
