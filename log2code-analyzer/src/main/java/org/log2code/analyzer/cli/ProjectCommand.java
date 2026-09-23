package org.log2code.analyzer.cli;

import com.github.javaparser.ast.CompilationUnit;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import org.log2code.analyzer.AnalyzerCli;
import org.log2code.analyzer.AnalyzerVersion;
import org.log2code.analyzer.ast.JavaSources;
import org.log2code.analyzer.config.AnalyzerConfig;
import org.log2code.analyzer.config.AnalyzerConfigLoader;
import org.log2code.analyzer.git.GitRepo;
import org.log2code.analyzer.logging.LogCall;
import org.log2code.analyzer.logging.LogCallDetector;
import org.log2code.analyzer.modules.ModuleScanner;
import org.log2code.analyzer.template.ConstantIndex;
import org.log2code.analyzer.template.ExtractedTemplate;
import org.log2code.analyzer.template.MessageTemplateExtractor;
import org.log2code.analyzer.template.TemplateKind;
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

    @Option(names = "--list-calls",
        description = "Print every detected log call (file:line, API, logger, level) instead of analyzing/writing anything.")
    private boolean listCalls;

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

        if (listCalls) {
            printListCalls(projectRoot, modules);
            return 0;
        }

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

    /**
     * T08 step 8: parses every module's {@code src/main/java} (one code unit, so inheritance across
     * modules is resolved just like within one), detects log calls and prints one line per call.
     * Read-only: writes nothing, regardless of {@code --dry-run}/{@code --out}.
     */
    private void printListCalls(Path projectRoot, List<ModuleInfo> modules) {
        List<CompilationUnit> units = new ArrayList<>();
        List<String> fileLabels = new ArrayList<>();
        List<JavaSources.ParseFailure> failures = new ArrayList<>();

        for (ModuleInfo module : modules) {
            String sourceRoot = module.sourceRoots().get(0);
            JavaSources.Result parsed = JavaSources.parseAll(projectRoot.resolve(module.module()).resolve(sourceRoot));
            for (JavaSources.ParsedFile file : parsed.files()) {
                units.add(file.unit());
                fileLabels.add(module.module() + "/" + sourceRoot + "/" + file.relativePath().toString().replace('\\', '/'));
            }
            failures.addAll(parsed.failures());
        }

        List<List<LogCall>> perFile = LogCallDetector.detectAll(units);
        ConstantIndex constants = ConstantIndex.build(units);
        int total = 0;
        int unsupported = 0;
        for (int i = 0; i < perFile.size(); i++) {
            for (LogCall call : perFile.get(i)) {
                int line = call.node().getRange().map(r -> r.begin.line).orElse(-1);
                String throwableMarker = call.throwableArg() != null ? " +throwable" : "";
                ExtractedTemplate extracted = MessageTemplateExtractor.extract(call, constants);
                String templateInfo = TemplateKind.UNSUPPORTED.equals(extracted.templateKind())
                    ? "kind=unsupported reason=" + extracted.unsupportedReason()
                    : "kind=%-14s template=%s".formatted(extracted.templateKind(), extracted.template().toNormalized());
                System.out.printf("%s:%d  api=%-20s logger=%-10s level=%-6s detection=%-10s logger_name_kind=%-14s logger_name=%s%s  %s%n",
                    fileLabels.get(i), line, call.api(), call.loggerExpr(), call.level(), call.detection(),
                    call.loggerNameKind(), call.loggerName(), throwableMarker, templateInfo);
                total++;
                if (TemplateKind.UNSUPPORTED.equals(extracted.templateKind())) {
                    unsupported++;
                }
            }
        }
        System.out.println("total: " + total + " log call(s), " + unsupported + " unsupported");

        if (!failures.isEmpty()) {
            System.err.println(failures.size() + " file(s) failed to parse:");
            for (JavaSources.ParseFailure failure : failures) {
                System.err.println("  " + failure.relativePath() + ": " + failure.message());
            }
        }
    }
}
