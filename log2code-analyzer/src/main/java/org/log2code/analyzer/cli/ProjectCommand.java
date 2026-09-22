package org.log2code.analyzer.cli;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import org.log2code.analyzer.AnalyzerCli;
import org.log2code.analyzer.AnalyzerVersion;
import org.log2code.analyzer.config.AnalyzerConfig;
import org.log2code.analyzer.config.AnalyzerConfigLoader;
import org.log2code.analyzer.git.GitRepo;
import org.log2code.analyzer.modules.ModuleScanner;
import org.log2code.core.ids.StableIds;
import org.log2code.core.model.AnalysisRun;
import org.log2code.core.model.CodeUnit;
import org.log2code.core.model.ModuleInfo;
import org.log2code.core.opensearch.IndexNames;
import org.log2code.core.opensearch.OpenSearchClientFactory;
import org.log2code.core.opensearch.OpenSearchConfig;
import org.opensearch.client.opensearch.OpenSearchClient;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;

/**
 * Analyzes the PetClinic project: git repo state, Maven modules and their services. Log statement
 * detection (T08), message templates (T09) and the rest of the catalog (T10-T13) are added later;
 * for now this only discovers modules and records an {@link AnalysisRun}.
 */
@Command(name = "project", description = "Analyze the project: git repository, Maven modules, services.")
public final class ProjectCommand implements Callable<Integer> {

    @ParentCommand
    private AnalyzerCli parent;

    @Option(names = "--dry-run", description = "Print commit, remote, modules and services without writing anything.")
    private boolean dryRun;

    @Option(names = "--allow-dirty",
        description = "Allow uncommitted .java changes in the project repo; the version gets a '-dirty' suffix.")
    private boolean allowDirty;

    @Override
    public Integer call() throws IOException {
        AnalyzerConfig config = AnalyzerConfigLoader.load(parent.configPath());
        Path projectRoot = Path.of(config.project().path());

        GitRepo repo = new GitRepo(projectRoot);
        String headCommit = repo.headCommit();
        String remoteUrl = repo.remoteUrl();
        List<String> dirtyJavaFiles = repo.dirtyJavaFiles();

        String version = headCommit;
        if (!dirtyJavaFiles.isEmpty()) {
            if (!allowDirty) {
                System.err.println("error: " + dirtyJavaFiles.size() + " uncommitted .java file(s) in "
                    + projectRoot.toAbsolutePath() + "; commit/stash them or pass --allow-dirty:");
                dirtyJavaFiles.forEach(f -> System.err.println("  " + f));
                return 1;
            }
            version = headCommit + "-dirty";
            System.err.println("warning: " + dirtyJavaFiles.size() + " uncommitted .java file(s); analyzing as " + version);
        }

        Instant startedAt = Instant.now();
        List<ModuleInfo> modules = new ModuleScanner().scan(
            projectRoot, config.project().includeModules(), config.project().excludeModules());
        Instant finishedAt = Instant.now();

        if (dryRun) {
            printDryRun(headCommit, remoteUrl, projectRoot, modules);
            return 0;
        }

        AnalysisRun run = new AnalysisRun(
            StableIds.runId(CodeUnit.TYPE_PROJECT, config.project().name(), version, AnalyzerVersion.current()),
            CodeUnit.TYPE_PROJECT,
            new CodeUnit(CodeUnit.TYPE_PROJECT, config.project().name(), version),
            remoteUrl,
            AnalyzerVersion.current(),
            startedAt,
            finishedAt,
            Duration.between(startedAt, finishedAt).toMillis(),
            Map.of("module_count", modules.size()),
            modules
        );

        writeRun(config, run);
        return 0;
    }

    private void writeRun(AnalyzerConfig config, AnalysisRun run) throws IOException {
        OutputMode out = parent.out();
        if (out.writesToOpenSearch()) {
            String url = parent.openSearchUrl() != null ? parent.openSearchUrl() : config.opensearch().url();
            OpenSearchClient client = OpenSearchClientFactory.create(OpenSearchConfig.of(url));
            try {
                RunWriter.writeToOpenSearch(client, new IndexNames(), run);
                System.out.println("wrote run " + run.runId() + " to log2code-runs (" + url + ")");
            } finally {
                OpenSearchClientFactory.close(client);
            }
        }
        if (out.writesToJson()) {
            Path written = RunWriter.writeToJson(parent.jsonDir(), run);
            System.out.println("wrote run " + run.runId() + " to " + written);
        }
    }

    private void printDryRun(String headCommit, String remoteUrl, Path projectRoot, List<ModuleInfo> modules) {
        System.out.println("commit: " + headCommit);
        System.out.println("remote: " + remoteUrl);
        System.out.println("modules: " + modules.size());
        for (ModuleInfo module : modules) {
            long javaFiles = ModuleScanner.countJavaFiles(projectRoot.resolve(module.module()));
            System.out.printf("  %-40s service=%-20s java_files=%d%n", module.module(), module.service(), javaFiles);
        }
    }
}
