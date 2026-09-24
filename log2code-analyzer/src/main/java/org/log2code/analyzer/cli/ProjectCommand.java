package org.log2code.analyzer.cli;

import com.github.javaparser.ast.CompilationUnit;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import org.log2code.analyzer.AnalyzerCli;
import org.log2code.analyzer.AnalyzerVersion;
import org.log2code.analyzer.ast.JavaSources;
import org.log2code.analyzer.catalog.ProjectCatalogBuilder;
import org.log2code.analyzer.config.AnalyzerConfig;
import org.log2code.analyzer.config.AnalyzerConfigLoader;
import org.log2code.analyzer.config.CodeUnitsConfigLoader;
import org.log2code.analyzer.git.GitRepo;
import org.log2code.analyzer.graph.CatalogGraphEnricher;
import org.log2code.analyzer.graph.ModuleJars;
import org.log2code.analyzer.graph.ProjectMethodGraphBuilder;
import org.log2code.analyzer.logging.LogCall;
import org.log2code.analyzer.logging.LogCallDetector;
import org.log2code.analyzer.modules.ModuleScanner;
import org.log2code.analyzer.template.ConstantIndex;
import org.log2code.analyzer.template.ExtractedTemplate;
import org.log2code.analyzer.template.MessageTemplateExtractor;
import org.log2code.analyzer.template.TemplateKind;
import org.log2code.core.github.CodeUnitsConfig;
import org.log2code.core.github.GithubLinker;
import org.log2code.core.ids.StableIds;
import org.log2code.core.json.Json;
import org.log2code.core.model.AnalysisRun;
import org.log2code.core.model.CatalogEntry;
import org.log2code.core.model.CodeUnit;
import org.log2code.core.model.MethodInfo;
import org.log2code.core.model.ModuleInfo;
import org.log2code.core.opensearch.DocumentReader;
import org.log2code.core.opensearch.IndexManager;
import org.log2code.core.opensearch.IndexNames;
import org.log2code.core.opensearch.OpenSearchClientFactory;
import org.log2code.core.opensearch.OpenSearchConfig;
import org.opensearch.client.opensearch.OpenSearchClient;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;

/**
 * Analyzes the PetClinic project: git repo state, Maven modules and their services, the full catalog
 * (T10: {@code log2code-catalog}/{@code -sources}/{@code -types}, level 1 {@code enclosing} and level 2
 * {@code control} context) and an {@link AnalysisRun}. If {@code deps resolve} (T12) has already
 * produced {@code data/work/deps/deps-manifest.json}, this also builds the project call graph (T13:
 * {@code log2code-methods}, {@code catalog.method_id} and {@code control.calls_before} resolution);
 * otherwise it prints a warning and writes the catalog alone, exactly as before T13 - re-running
 * {@code project} after {@code deps resolve} is the normal way to add the graph afterwards.
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

    private static final Path DEPS_MANIFEST_FILE = Path.of("data/work/deps/deps-manifest.json");
    private static final Path CODE_UNITS_CONFIG_FILE = Path.of("config/code-units.yml");

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

        List<ModuleInfo> modules = new ModuleScanner().scan(
            projectRoot, config.project().includeModules(), config.project().excludeModules());

        if (listCalls) {
            printListCalls(projectRoot, modules);
            return 0;
        }

        if (dryRun) {
            printDryRun(headCommit, remoteUrl, projectRoot, modules);
            return 0;
        }

        CodeUnit codeUnit = new CodeUnit(CodeUnit.TYPE_PROJECT, config.project().name(), version);
        String runId = StableIds.runId(CodeUnit.TYPE_PROJECT, config.project().name(), version, AnalyzerVersion.current());
        modules = ModuleDependencyMerger.merge(modules, readExistingRun(config, codeUnit, runId));

        Instant startedAt = Instant.now();
        ProjectCatalogBuilder.Result catalogResult = ProjectCatalogBuilder.build(
            projectRoot, modules, codeUnit, config.context().snippetLines(), config.context().maxPrecedingStatements(),
            AnalyzerVersion.current(), startedAt);

        List<CatalogEntry> catalog = catalogResult.catalog();
        List<MethodInfo> methods = List.of();
        Map<String, Object> stats = new LinkedHashMap<>(catalogResult.stats());

        Optional<Map<String, List<Path>>> jarsByModule = ModuleJars.load(DEPS_MANIFEST_FILE);
        if (jarsByModule.isPresent()) {
            ProjectMethodGraphBuilder.Result graphResult = ProjectMethodGraphBuilder.build(
                projectRoot, modules, codeUnit, jarsByModule.get());
            methods = graphResult.methods();
            catalog = CatalogGraphEnricher.enrich(catalog, methods);
            stats.putAll(graphResult.stats());
        } else {
            System.err.println("warning: " + DEPS_MANIFEST_FILE + " not found; skipping the call graph (T13)."
                + " Run 'analyzer deps resolve' first, then 'analyzer project' again to add it.");
        }
        Instant finishedAt = Instant.now();

        CodeUnitsConfig codeUnitsConfig = CodeUnitsConfigLoader.load(CODE_UNITS_CONFIG_FILE);
        GithubLinker linker = new GithubLinker(codeUnitsConfig, remoteUrl);
        catalog = catalog.stream()
            .map(e -> e.withGithubUrl(linker.link(e.codeUnit(), e.filePath(), e.line(), e.endLine())))
            .toList();

        AnalysisRun run = new AnalysisRun(
            runId,
            CodeUnit.TYPE_PROJECT,
            codeUnit,
            remoteUrl,
            AnalyzerVersion.current(),
            startedAt,
            finishedAt,
            Duration.between(startedAt, finishedAt).toMillis(),
            Map.copyOf(stats),
            modules
        );

        writeAll(config, run, catalogResult, catalog, methods);
        return 0;
    }

    private void writeAll(AnalyzerConfig config, AnalysisRun run, ProjectCatalogBuilder.Result catalogResult,
                           List<CatalogEntry> catalog, List<MethodInfo> methods) throws IOException {
        OutputMode out = parent.out();
        if (out.writesToOpenSearch()) {
            String url = parent.openSearchUrl() != null ? parent.openSearchUrl() : config.opensearch().url();
            OpenSearchClient client = OpenSearchClientFactory.create(OpenSearchConfig.of(url));
            try {
                CatalogWriter.writeToOpenSearch(client, new IndexNames(), run.codeUnit(), catalog,
                    catalogResult.sources(), catalogResult.types(), methods);
                RunWriter.writeToOpenSearch(client, new IndexNames(), run);
                System.out.println("wrote " + catalog.size() + " catalog entries, "
                    + catalogResult.sources().size() + " source files, " + catalogResult.types().size()
                    + " types, " + methods.size() + " methods, and run " + run.runId() + " (" + url + ")");
            } finally {
                OpenSearchClientFactory.close(client);
            }
        }
        if (out.writesToJson()) {
            Path catalogDir = CatalogWriter.writeToJson(parent.jsonDir(), run.codeUnit(), catalog,
                catalogResult.sources(), catalogResult.types(), methods);
            Path runFile = RunWriter.writeToJson(parent.jsonDir(), run);
            System.out.println("wrote catalog/sources/types/methods to " + catalogDir + " and run to " + runFile);
        }
    }

    /**
     * Reads the {@link AnalysisRun} a prior {@code project}/{@code deps resolve} run left behind for
     * this exact {@code runId}, so {@link ModuleDependencyMerger} can carry its
     * {@code dependencies}/{@code selectedDependencies} into this run's freshly scanned modules (ADR-020).
     * Prefers OpenSearch (the canonical destination); falls back to {@code run.json} only for a pure
     * {@code --out json} run. Returns {@code null} if neither has one yet (first run for this version).
     */
    private AnalysisRun readExistingRun(AnalyzerConfig config, CodeUnit codeUnit, String runId) throws IOException {
        OutputMode out = parent.out();
        if (out.writesToOpenSearch()) {
            String url = parent.openSearchUrl() != null ? parent.openSearchUrl() : config.opensearch().url();
            OpenSearchClient client = OpenSearchClientFactory.create(OpenSearchConfig.of(url));
            try {
                IndexNames indexNames = new IndexNames();
                new IndexManager(client, indexNames).ensureAll();
                return new DocumentReader(client).get(indexNames.runs(), runId, AnalysisRun.class);
            } finally {
                OpenSearchClientFactory.close(client);
            }
        }
        if (out.writesToJson()) {
            Path runFile = JsonPaths.forCodeUnit(parent.jsonDir(), codeUnit).resolve("run.json");
            if (Files.isRegularFile(runFile)) {
                return Json.mapper().readValue(runFile.toFile(), AnalysisRun.class);
            }
        }
        return null;
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
