package org.log2code.ingester.catalog;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.log2code.core.model.AnalysisRun;
import org.log2code.core.model.CatalogEntry;
import org.log2code.core.model.CodeUnit;
import org.log2code.core.model.ModuleInfo;
import org.log2code.core.model.TypeInfo;
import org.log2code.core.opensearch.DocumentReader;
import org.log2code.core.opensearch.IndexManager;
import org.log2code.core.opensearch.IndexNames;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch._types.FieldValue;
import org.opensearch.client.opensearch._types.query_dsl.Query;

/**
 * The in-memory catalog index (T19): loaded once per project version from OpenSearch, it answers 0.10's
 * matching primitives - {@link #loggerResolver()} (step 1), {@link #byLogger} and {@link #byTokens}
 * (step 2's two candidate sources) and {@link #ancestors} (the {@code get_class} hierarchy check) -
 * against only the statements applicable to a given service (0.10 step 0: the project's own module plus
 * that service's exact selected dependency versions, per the {@link AnalysisRun} T12/T14 already wrote).
 * {@code template_kind = unsupported} statements are excluded at load time and never enter the index.
 */
public final class CatalogIndex {

    private static final int PAGE_SIZE = 2000;
    private static final String TEMPLATE_KIND_UNSUPPORTED = "unsupported";

    private final int totalStatementCount;
    private final Map<String, List<CatalogKey>> applicableByService;
    private final Map<String, TokenIndex> tokenIndexByService;
    private final TypeHierarchy hierarchy;
    private final LoggerResolver loggerResolver;

    private CatalogIndex(int totalStatementCount, Map<String, List<CatalogKey>> applicableByService,
                          Map<String, TokenIndex> tokenIndexByService, TypeHierarchy hierarchy,
                          LoggerResolver loggerResolver) {
        this.totalStatementCount = totalStatementCount;
        this.applicableByService = applicableByService;
        this.tokenIndexByService = tokenIndexByService;
        this.hierarchy = hierarchy;
        this.loggerResolver = loggerResolver;
    }

    /**
     * Reads the project's {@link AnalysisRun} (most recent {@code started_at}, if more than one
     * {@code analyzer_version} has ever analyzed this exact code version), then loads every applicable
     * project and selected-dependency statement/type from OpenSearch and builds the index (T19 steps 1-6).
     * Logs the statement/type counts, load time and an approximate heap cost (step 7).
     *
     * @throws CatalogLoadException if no {@code AnalysisRun} exists yet for {@code (projectName, projectVersion)}
     */
    public static CatalogIndex load(OpenSearchClient client, String projectName, String projectVersion) throws IOException {
        return loadFrom(client, new IndexNames(), projectName, projectVersion);
    }

    /** As {@link #load}, but against a caller-chosen {@link IndexNames} (tests: an isolated, prefixed set). */
    static CatalogIndex loadFrom(OpenSearchClient client, IndexNames indexNames, String projectName, String projectVersion)
            throws IOException {
        MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();
        Instant startedAt = Instant.now();
        long heapBefore = memoryBean.getHeapMemoryUsage().getUsed();

        new IndexManager(client, indexNames).ensureAll();
        DocumentReader reader = new DocumentReader(client);
        AnalysisRun run = findAnalysisRun(reader, indexNames, projectName, projectVersion);

        Set<CodeUnit> codeUnits = new LinkedHashSet<>();
        codeUnits.add(run.codeUnit());
        for (ModuleInfo module : run.modules()) {
            for (String gav : module.selectedDependencies()) {
                codeUnits.add(parseDependencyCodeUnit(gav));
            }
        }

        List<CatalogEntry> catalogEntries = new ArrayList<>();
        try (Stream<CatalogEntry> stream =
                 reader.streamAll(indexNames.catalog(), catalogQuery(codeUnits), CatalogEntry.class, PAGE_SIZE)) {
            stream.forEach(catalogEntries::add);
        }
        List<TypeInfo> types = new ArrayList<>();
        try (Stream<TypeInfo> stream =
                 reader.streamAll(indexNames.types(), codeUnitsQuery(codeUnits), TypeInfo.class, PAGE_SIZE)) {
            stream.forEach(types::add);
        }

        CatalogIndex index = build(run, catalogEntries, types);

        long heapAfter = memoryBean.getHeapMemoryUsage().getUsed();
        Duration elapsed = Duration.between(startedAt, Instant.now());
        long approxMb = Math.max(0, heapAfter - heapBefore) / (1024 * 1024);
        System.err.printf(
            "CatalogIndex: loaded %d statement(s), %d type(s) for %d service(s) in %d ms (~%d MB heap)%n",
            index.totalStatementCount, types.size(), index.applicableByService.size(), elapsed.toMillis(), approxMb);

        return index;
    }

    /** The pure builder behind {@link #load}, usable directly against an already-fetched, synthetic catalog (tests). */
    static CatalogIndex build(AnalysisRun run, List<CatalogEntry> catalogEntries, List<TypeInfo> types) {
        List<CatalogKey> allKeys = catalogEntries.stream()
            .filter(e -> !TEMPLATE_KIND_UNSUPPORTED.equals(e.templateKind()))
            .map(CatalogKey::from)
            .toList();

        Map<String, String> projectModuleByService = new LinkedHashMap<>();
        Map<String, Set<CodeUnit>> selectedDependenciesByService = new LinkedHashMap<>();
        for (ModuleInfo module : run.modules()) {
            if (module.service() == null) {
                continue;
            }
            projectModuleByService.put(module.service(), module.module());
            Set<CodeUnit> deps = new LinkedHashSet<>();
            for (String gav : module.selectedDependencies()) {
                deps.add(parseDependencyCodeUnit(gav));
            }
            selectedDependenciesByService.put(module.service(), deps);
        }

        Map<String, List<CatalogKey>> applicableByService = new LinkedHashMap<>();
        Map<String, TokenIndex> tokenIndexByService = new LinkedHashMap<>();
        Map<String, Set<String>> namesByService = new LinkedHashMap<>();
        for (String service : projectModuleByService.keySet()) {
            String projectModule = projectModuleByService.get(service);
            Set<CodeUnit> selectedDeps = selectedDependenciesByService.getOrDefault(service, Set.of());

            List<CatalogKey> applicable = allKeys.stream()
                .filter(key -> isCatalogKeyApplicable(key, service, selectedDeps))
                .toList();
            applicableByService.put(service, applicable);
            tokenIndexByService.put(service, TokenIndex.build(applicable));

            Set<String> names = new LinkedHashSet<>();
            for (CatalogKey key : applicable) {
                if (key.loggerName() != null) {
                    names.add(key.loggerName());
                }
            }
            for (TypeInfo type : types) {
                if (!isTypeApplicable(type, projectModule, selectedDeps)) {
                    continue;
                }
                if (type.classFqn() != null) {
                    names.add(type.classFqn());
                }
                if (type.classBinary() != null) {
                    names.add(type.classBinary());
                }
            }
            namesByService.put(service, names);
        }

        return new CatalogIndex(allKeys.size(), applicableByService, tokenIndexByService,
            TypeHierarchy.build(types), new LoggerResolver(namesByService));
    }

    /** Total statements loaded across every service (T19 step 7). */
    public int statementCount() {
        return totalStatementCount;
    }

    /** Statements applicable to {@code service} (0.10 step 0), for tests and diagnostics. */
    public int applicableStatementCount(String service) {
        return applicableByService.getOrDefault(service, List.of()).size();
    }

    public LoggerResolver loggerResolver() {
        return loggerResolver;
    }

    /**
     * The proper superclass chain of {@code classFqn}, nearest first, NOT including {@code classFqn}
     * itself (T19 step 4).
     */
    public List<String> ancestors(String classFqn) {
        return hierarchy.ancestors(classFqn);
    }

    /**
     * Statements in {@code service}'s applicable set whose logger plausibly matches one of the resolved
     * {@code names} (0.10 step 2): {@code logger_name ∈ names}, {@code class_fqn ∈ names}, or
     * ({@code logger_name_kind = get_class} and {@code class_fqn ∈ ancestors(l) ∪ {l}} for some
     * {@code l ∈ names}).
     */
    public List<CatalogKey> byLogger(Set<String> names, String service) {
        if (names == null || names.isEmpty()) {
            return List.of();
        }
        List<CatalogKey> result = new ArrayList<>();
        for (CatalogKey key : applicableByService.getOrDefault(service, List.of())) {
            if (matchesByLogger(key, names)) {
                result.add(key);
            }
        }
        return List.copyOf(result);
    }

    /**
     * The top {@code k} statements in {@code service}'s applicable set, ranked by the summed IDF of
     * tokens shared with {@code messageTokens} (0.10 step 2's {@code topK_tokens}).
     */
    public List<CatalogKey> byTokens(List<String> messageTokens, String service, int k) {
        TokenIndex index = tokenIndexByService.get(service);
        if (index == null || messageTokens == null || messageTokens.isEmpty()) {
            return List.of();
        }
        return index.topK(messageTokens, k);
    }

    private boolean matchesByLogger(CatalogKey key, Set<String> names) {
        if (key.loggerName() != null && names.contains(key.loggerName())) {
            return true;
        }
        if (key.classFqn() != null && names.contains(key.classFqn())) {
            return true;
        }
        if ("get_class".equals(key.loggerNameKind()) && key.classFqn() != null) {
            for (String l : names) {
                if (key.classFqn().equals(l) || hierarchy.ancestors(l).contains(key.classFqn())) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean isCatalogKeyApplicable(CatalogKey key, String service, Set<CodeUnit> selectedDependencies) {
        if (CodeUnit.TYPE_PROJECT.equals(key.codeUnit().type())) {
            return service.equals(key.service());
        }
        return selectedDependencies.contains(key.codeUnit());
    }

    private static boolean isTypeApplicable(TypeInfo type, String projectModule, Set<CodeUnit> selectedDependencies) {
        if (CodeUnit.TYPE_PROJECT.equals(type.codeUnit().type())) {
            return type.module().equals(projectModule);
        }
        return selectedDependencies.contains(type.codeUnit());
    }

    /** {@code "groupId:artifactId:version"} (0.7's {@code ModuleInfo.selectedDependencies} format) to a dependency {@link CodeUnit}. */
    private static CodeUnit parseDependencyCodeUnit(String gav) {
        int firstColon = gav.indexOf(':');
        int secondColon = gav.indexOf(':', firstColon + 1);
        String name = gav.substring(0, secondColon);
        String version = gav.substring(secondColon + 1);
        return new CodeUnit(CodeUnit.TYPE_DEPENDENCY, name, version);
    }

    private static AnalysisRun findAnalysisRun(DocumentReader reader, IndexNames indexNames,
                                                String projectName, String projectVersion) throws IOException {
        Query query = Query.of(q -> q.bool(b -> b.filter(List.of(
            Query.of(f -> f.term(t -> t.field("kind").value(FieldValue.of(CodeUnit.TYPE_PROJECT)))),
            Query.of(f -> f.term(t -> t.field("code_unit.name").value(FieldValue.of(projectName)))),
            Query.of(f -> f.term(t -> t.field("code_unit.version").value(FieldValue.of(projectVersion))))))));
        List<AnalysisRun> runs;
        try (Stream<AnalysisRun> stream = reader.streamAll(indexNames.runs(), query, AnalysisRun.class, 10)) {
            runs = stream.toList();
        }
        if (runs.isEmpty()) {
            throw new CatalogLoadException("no AnalysisRun found in " + indexNames.runs() + " for project '"
                + projectName + "' at version '" + projectVersion + "'; run 'analyzer project' first.");
        }
        return runs.stream().max(Comparator.comparing(AnalysisRun::startedAt)).orElseThrow();
    }

    private static Query codeUnitQuery(CodeUnit codeUnit) {
        return Query.of(q -> q.bool(b -> b.filter(List.of(
            Query.of(f -> f.term(t -> t.field("code_unit.name").value(FieldValue.of(codeUnit.name())))),
            Query.of(f -> f.term(t -> t.field("code_unit.version").value(FieldValue.of(codeUnit.version()))))))));
    }

    private static Query codeUnitsQuery(Set<CodeUnit> codeUnits) {
        List<Query> should = codeUnits.stream().map(CatalogIndex::codeUnitQuery).toList();
        return should.size() == 1 ? should.get(0) : Query.of(q -> q.bool(b -> b.should(should).minimumShouldMatch("1")));
    }

    private static Query catalogQuery(Set<CodeUnit> codeUnits) {
        Query notUnsupported = Query.of(q -> q.term(t -> t.field("template_kind").value(FieldValue.of(TEMPLATE_KIND_UNSUPPORTED))));
        return Query.of(q -> q.bool(b -> b.filter(codeUnitsQuery(codeUnits)).mustNot(notUnsupported)));
    }
}
