package org.log2code.analyzer.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.Callable;
import org.log2code.analyzer.AnalyzerCli;
import org.log2code.analyzer.AnalyzerVersion;
import org.log2code.analyzer.CliUserException;
import org.log2code.analyzer.config.AnalyzerConfig;
import org.log2code.analyzer.config.AnalyzerConfigLoader;
import org.log2code.analyzer.deps.Artifact;
import org.log2code.analyzer.deps.DependencySelector;
import org.log2code.analyzer.deps.DependencySelector.ModuleSelection;
import org.log2code.analyzer.deps.DependencySelector.SelectedArtifact;
import org.log2code.analyzer.deps.DepsManifest;
import org.log2code.analyzer.deps.LoggerHeaderScanner;
import org.log2code.analyzer.deps.MavenCli;
import org.log2code.analyzer.deps.ModuleDeps;
import org.log2code.analyzer.deps.ProjectClassIndex;
import org.log2code.analyzer.deps.SourcesFetcher;
import org.log2code.analyzer.git.GitRepo;
import org.log2code.analyzer.modules.ModuleScanner;
import org.log2code.core.ids.StableIds;
import org.log2code.core.json.Json;
import org.log2code.core.model.AnalysisRun;
import org.log2code.core.model.CodeUnit;
import org.log2code.core.model.ModuleInfo;
import org.log2code.core.opensearch.DocumentReader;
import org.log2code.core.opensearch.IndexNames;
import org.log2code.core.opensearch.OpenSearchClientFactory;
import org.log2code.core.opensearch.OpenSearchConfig;
import org.opensearch.client.opensearch.OpenSearchClient;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;

/**
 * {@code deps resolve} (T12): resolves each module's exact runtime dependency list via
 * {@code mvn dependency:list}, automatically selects which dependencies to analyze based on which loggers
 * actually appear in the fixture/live logs, downloads {@code -sources.jar} for the selected ones, writes
 * {@code data/work/deps/deps-manifest.json}, and updates {@code modules[].dependencies}/
 * {@code selected_dependencies} of the project's {@link AnalysisRun} (written earlier by {@code project}).
 */
@Command(name = "resolve", description = "Resolve runtime dependencies per module, auto-select from logs, fetch sources.")
public final class DepsResolveCommand implements Callable<Integer> {

    private static final Path DEPS_WORK_DIR = Path.of("data/work/deps");
    private static final Path MANIFEST_FILE = DEPS_WORK_DIR.resolve("deps-manifest.json");

    @ParentCommand
    private DepsCommand depsParent;

    @Option(names = "--allow-dirty",
        description = "Allow uncommitted .java changes in the project repo; the version gets a '-dirty' suffix.")
    private boolean allowDirty;

    @Override
    public Integer call() throws IOException {
        AnalyzerCli parent = depsParent.parent();
        AnalyzerConfig config = AnalyzerConfigLoader.load(parent.configPath());
        Path projectRoot = Path.of(config.project().path());

        GitRepo repo = new GitRepo(projectRoot);
        String headCommit = repo.headCommit();
        String version = resolveVersion(repo, headCommit, projectRoot);

        List<ModuleInfo> modules = new ModuleScanner().scan(
            projectRoot, config.project().includeModules(), config.project().excludeModules());

        Files.createDirectories(DEPS_WORK_DIR);
        MavenCli maven = new MavenCli(projectRoot);

        List<ModuleDeps> allDeps = new ArrayList<>();
        for (ModuleInfo module : modules) {
            Path outputFile = DEPS_WORK_DIR.resolve(module.module() + ".txt");
            List<Artifact> artifacts = maven.listRuntimeDependencies(module.module(), outputFile);
            allDeps.add(new ModuleDeps(module.module(), artifacts));
        }

        List<Path> logDirs = config.dependencies().autoSelectFromLogs().stream().map(Path::of).toList();
        LoggerHeaderScanner.Stats loggerStats = LoggerHeaderScanner.scan(logDirs);
        Set<String> projectFqns = ProjectClassIndex.classFqns(projectRoot, modules);

        List<ModuleSelection> selections = new ArrayList<>();
        for (ModuleDeps deps : allDeps) {
            ModuleInfo module = byName(modules, deps.module());
            Map<String, Integer> loggerCounts = module.service() == null
                ? Map.of() : loggerStats.byService().getOrDefault(module.service(), Map.of());
            selections.add(DependencySelector.select(deps, loggerCounts, projectFqns,
                config.dependencies().include(), config.dependencies().exclude()));
        }

        Map<String, SourcesFetcher.Result> sourcesCache = new HashMap<>();
        List<DepsManifest.Module> manifestModules = new ArrayList<>();
        List<ModuleInfo> updatedModules = new ArrayList<>();
        for (int i = 0; i < modules.size(); i++) {
            ModuleInfo module = modules.get(i);
            ModuleSelection selection = selections.get(i);
            List<String> allGavs = new ArrayList<>();
            List<String> selectedGavs = new ArrayList<>();
            List<DepsManifest.ManifestArtifact> manifestArtifacts = new ArrayList<>();
            for (SelectedArtifact sa : selection.artifacts()) {
                Artifact artifact = sa.artifact();
                allGavs.add(artifact.gav());
                String sourcesJar = null;
                boolean sourcesMissing = false;
                if (sa.selected()) {
                    selectedGavs.add(artifact.gav());
                    SourcesFetcher.Result result = sourcesCache.computeIfAbsent(
                        artifact.gav(), gav -> SourcesFetcher.fetch(maven, artifact));
                    sourcesMissing = result.missing();
                    sourcesJar = result.sourcesJar() == null ? null : result.sourcesJar().toString();
                }
                manifestArtifacts.add(new DepsManifest.ManifestArtifact(artifact.gav(),
                    artifact.jarPath().toString(), sourcesJar, sa.selected(), sa.reason(), sa.loggerHits(), sourcesMissing));
            }
            manifestModules.add(new DepsManifest.Module(module.module(), module.service(), manifestArtifacts));
            updatedModules.add(new ModuleInfo(module.module(), module.service(), module.sourceRoots(), allGavs, selectedGavs));
        }

        DepsManifest manifest = new DepsManifest(headCommit, manifestModules);
        Json.mapper().writerWithDefaultPrettyPrinter().writeValue(MANIFEST_FILE.toFile(), manifest);

        CodeUnit codeUnit = new CodeUnit(CodeUnit.TYPE_PROJECT, config.project().name(), version);
        String runId = StableIds.runId(CodeUnit.TYPE_PROJECT, config.project().name(), version, AnalyzerVersion.current());
        updateAnalysisRun(parent, config, runId, codeUnit, updatedModules);

        printReport(allDeps, selections, loggerStats);
        System.out.println("wrote " + MANIFEST_FILE);
        return 0;
    }

    private String resolveVersion(GitRepo repo, String headCommit, Path projectRoot) {
        List<String> dirtyJavaFiles = repo.dirtyJavaFiles();
        if (dirtyJavaFiles.isEmpty()) {
            return headCommit;
        }
        if (!allowDirty) {
            throw new CliUserException(dirtyJavaFiles.size() + " uncommitted .java file(s) in "
                + projectRoot.toAbsolutePath() + "; commit/stash them or pass --allow-dirty");
        }
        String version = headCommit + "-dirty";
        System.err.println("warning: " + dirtyJavaFiles.size() + " uncommitted .java file(s); resolving as " + version);
        return version;
    }

    private void updateAnalysisRun(AnalyzerCli parent, AnalyzerConfig config, String runId, CodeUnit codeUnit,
                                    List<ModuleInfo> updatedModules) throws IOException {
        OutputMode out = parent.out();
        IndexNames indexNames = new IndexNames();
        if (out.writesToOpenSearch()) {
            String url = parent.openSearchUrl() != null ? parent.openSearchUrl() : config.opensearch().url();
            OpenSearchClient client = OpenSearchClientFactory.create(OpenSearchConfig.of(url));
            try {
                AnalysisRun existing = new DocumentReader(client).get(indexNames.runs(), runId, AnalysisRun.class);
                if (existing == null) {
                    System.err.println("warning: no AnalysisRun found in " + indexNames.runs() + " for " + runId
                        + "; run 'analyzer project' first to also update the run document there.");
                } else {
                    RunWriter.writeToOpenSearch(client, indexNames, withModules(existing, updatedModules));
                    System.out.println("updated modules[] in " + indexNames.runs() + " (" + runId + ")");
                }
            } finally {
                OpenSearchClientFactory.close(client);
            }
        }
        if (out.writesToJson()) {
            Path runFile = parent.jsonDir().resolve(codeUnit.name()).resolve(codeUnit.version()).resolve("run.json");
            if (Files.isRegularFile(runFile)) {
                AnalysisRun existing = Json.mapper().readValue(runFile.toFile(), AnalysisRun.class);
                RunWriter.writeToJson(parent.jsonDir(), withModules(existing, updatedModules));
                System.out.println("updated modules[] in " + runFile);
            } else {
                System.err.println("warning: no run.json found at " + runFile + "; run 'analyzer project --out json' first.");
            }
        }
    }

    private static AnalysisRun withModules(AnalysisRun run, List<ModuleInfo> modules) {
        return new AnalysisRun(run.runId(), run.kind(), run.codeUnit(), run.repoUrl(), run.analyzerVersion(),
            run.startedAt(), run.finishedAt(), run.durationMs(), run.stats(), modules);
    }

    private void printReport(List<ModuleDeps> allDeps, List<ModuleSelection> selections, LoggerHeaderScanner.Stats loggerStats) {
        Set<String> allMapped = new TreeSet<>();
        Set<String> allUnmapped = new TreeSet<>();
        Set<String> allExcluded = new TreeSet<>();
        for (int i = 0; i < allDeps.size(); i++) {
            ModuleDeps deps = allDeps.get(i);
            ModuleSelection selection = selections.get(i);
            long selectedCount = selection.artifacts().stream().filter(SelectedArtifact::selected).count();
            System.out.println(deps.module() + ": " + deps.artifacts().size() + " artifact(s), " + selectedCount + " selected");
            for (SelectedArtifact sa : selection.artifacts()) {
                if (sa.selected()) {
                    System.out.printf(Locale.ROOT, "  + %-65s reason=%-14s logger_hits=%d%n",
                        sa.artifact().gav(), sa.reason(), sa.loggerHits());
                }
            }
            allMapped.addAll(selection.mappedLoggers());
            allUnmapped.addAll(selection.unmappedLoggers());
            allExcluded.addAll(selection.excludedLoggers());
        }
        allUnmapped.removeAll(allMapped);
        allExcluded.removeAll(allMapped); // mapped (e.g. to the project) in a different module's scope wins

        // AC4 denominator excludes loggers that are not shaped like a class name at all (e.g. Tomcat's
        // container hierarchy names): they can never match under 0.10 step 1, so counting them as "unique
        // loggers" would understate a heuristic that is, by construction, only about class-based loggers.
        Set<String> eligibleLoggers = new TreeSet<>(loggerStats.uniqueLoggers());
        eligibleLoggers.removeAll(allExcluded);
        double percentage = eligibleLoggers.isEmpty() ? 100.0 : 100.0 * allMapped.size() / eligibleLoggers.size();
        System.out.println();
        System.out.printf(Locale.ROOT, "loggers: %d unique (%d not class-shaped, excluded), %d eligible, %d mapped (%.1f%%), %d unmapped%n",
            loggerStats.uniqueLoggers().size(), allExcluded.size(), eligibleLoggers.size(), allMapped.size(), percentage, allUnmapped.size());
        if (!allUnmapped.isEmpty()) {
            System.out.println("unmapped loggers (e.g. string loggers):");
            allUnmapped.forEach(logger -> System.out.println("  " + logger));
        }
        if (!allExcluded.isEmpty()) {
            System.out.println("excluded loggers (not class-shaped, e.g. Tomcat container hierarchy):");
            allExcluded.forEach(logger -> System.out.println("  " + logger));
        }
    }

    private static ModuleInfo byName(List<ModuleInfo> modules, String name) {
        return modules.stream().filter(m -> m.module().equals(name)).findFirst()
            .orElseThrow(() -> new IllegalStateException("module not found: " + name));
    }
}
