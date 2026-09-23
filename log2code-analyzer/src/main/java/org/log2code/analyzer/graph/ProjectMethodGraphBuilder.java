package org.log2code.analyzer.graph;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.log2code.analyzer.ast.JavaSources;
import org.log2code.analyzer.catalog.AnonymousClassNumbering;
import org.log2code.analyzer.catalog.AstLines;
import org.log2code.analyzer.catalog.ClassContext;
import org.log2code.analyzer.catalog.ClassContextResolver;
import org.log2code.analyzer.catalog.FileInfo;
import org.log2code.analyzer.catalog.MethodContext;
import org.log2code.analyzer.catalog.MethodContextResolver;
import org.log2code.analyzer.catalog.MethodSignatures;
import org.log2code.analyzer.logging.ClassFqns;
import org.log2code.analyzer.logging.CodeUnitIndexer;
import org.log2code.analyzer.logging.LogCall;
import org.log2code.analyzer.logging.LogCallDetector;
import org.log2code.analyzer.logging.TypeIndex;
import org.log2code.core.ids.StableIds;
import org.log2code.core.model.CallEdge;
import org.log2code.core.model.CallerRef;
import org.log2code.core.model.CodeUnit;
import org.log2code.core.model.MethodInfo;
import org.log2code.core.model.ModuleInfo;

/**
 * Builds {@code log2code-methods} for the whole project (T13): every method and constructor, with its
 * outgoing calls resolved (project method, external method, or unresolved) and, afterwards, the reverse
 * {@code called_by}/{@code caller_count} edges. Modules are processed one at a time, each with its own
 * {@link CombinedTypeSolver} scoped to that module's own dependency jars (step 1), so the resolver never
 * has to hold every module's dependencies in memory at once.
 */
public final class ProjectMethodGraphBuilder {

    private ProjectMethodGraphBuilder() {
    }

    public record Result(List<MethodInfo> methods, Map<String, Object> stats) {
    }

    private record FileUnit(FileInfo file, CompilationUnit unit) {
    }

    private static final class CallStats {
        long callCount;
        long resolvedCallCount;
        long projectTargetCallCount;
    }

    public static Result build(Path projectRoot, List<ModuleInfo> modules, CodeUnit codeUnit,
                                Map<String, List<Path>> jarsByModule) {
        Instant start = Instant.now();
        MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();
        long maxHeapUsed = memoryBean.getHeapMemoryUsage().getUsed();

        Map<String, List<FileUnit>> unitsByModule = new LinkedHashMap<>();
        List<CompilationUnit> allUnits = new ArrayList<>();
        for (ModuleInfo module : modules) {
            List<FileUnit> moduleUnits = new ArrayList<>();
            for (String sourceRoot : module.sourceRoots()) {
                Path moduleSourceRoot = projectRoot.resolve(module.module()).resolve(sourceRoot);
                JavaSources.Result parsed = JavaSources.parseAll(moduleSourceRoot);
                for (JavaSources.ParsedFile parsedFile : parsed.files()) {
                    FileInfo file = new FileInfo(module.module(), module.service(),
                        module.module() + "/" + sourceRoot + "/" + parsedFile.relativePath().toString().replace('\\', '/'),
                        parsedFile.unit().getPackageDeclaration().map(pd -> pd.getNameAsString()).orElse(""));
                    moduleUnits.add(new FileUnit(file, parsedFile.unit()));
                    allUnits.add(parsedFile.unit());
                }
            }
            unitsByModule.put(module.module(), moduleUnits);
        }

        TypeIndex typeIndex = CodeUnitIndexer.index(allUnits);
        ProjectInterfaceIndex interfaceIndex = ProjectInterfaceIndex.build(typeIndex);
        Map<String, TypeDeclaration<?>> declarationsByFqn = new LinkedHashMap<>();
        for (CompilationUnit unit : allUnits) {
            for (TypeDeclaration<?> type : unit.findAll(TypeDeclaration.class)) {
                declarationsByFqn.put(ClassFqns.of(type), type);
            }
        }
        Set<String> methodsWithLogStatements = methodsWithLogStatements(allUnits, codeUnit);

        ProjectMethodIds methodIds = new ProjectMethodIds(codeUnit);
        ModuleTypeSolvers typeSolvers = new ModuleTypeSolvers(projectRoot, modules);

        Map<String, MethodInfo> draftById = new LinkedHashMap<>();
        Map<String, List<CallerRef>> callersByTargetId = new LinkedHashMap<>();
        CallStats stats = new CallStats();

        for (ModuleInfo module : modules) {
            List<Path> jars = jarsByModule.getOrDefault(module.module(), List.of());
            CombinedTypeSolver solver = typeSolvers.forModule(jars);
            JavaSymbolSolver symbolSolver = new JavaSymbolSolver(solver);
            List<FileUnit> moduleUnits = unitsByModule.get(module.module());
            for (FileUnit fileUnit : moduleUnits) {
                symbolSolver.inject(fileUnit.unit());
            }

            for (FileUnit fileUnit : moduleUnits) {
                Map<Node, Integer> anonymousNumbers = AnonymousClassNumbering.compute(fileUnit.unit());
                for (MethodDeclaration method : fileUnit.unit().findAll(MethodDeclaration.class)) {
                    MethodInfo info = buildMethodInfo(method, method.getNameAsString(), MethodSignatures.of(method),
                        method.getBody().orElse(null), fileUnit, anonymousNumbers, codeUnit, methodIds, interfaceIndex,
                        declarationsByFqn, methodsWithLogStatements, callersByTargetId, stats);
                    draftById.put(info.methodId(), info);
                }
                for (ConstructorDeclaration constructor : fileUnit.unit().findAll(ConstructorDeclaration.class)) {
                    MethodInfo info = buildMethodInfo(constructor, "<init>", MethodSignatures.of(constructor),
                        constructor.getBody(), fileUnit, anonymousNumbers, codeUnit, methodIds, interfaceIndex,
                        declarationsByFqn, methodsWithLogStatements, callersByTargetId, stats);
                    draftById.put(info.methodId(), info);
                }
            }
            maxHeapUsed = Math.max(maxHeapUsed, memoryBean.getHeapMemoryUsage().getUsed());
        }

        List<MethodInfo> methods = new ArrayList<>(draftById.size());
        for (MethodInfo draft : draftById.values()) {
            List<CallerRef> callers = callersByTargetId.getOrDefault(draft.methodId(), List.of());
            methods.add(new MethodInfo(draft.methodId(), draft.codeUnit(), draft.module(), draft.service(),
                draft.fileId(), draft.filePath(), draft.classFqn(), draft.classBinary(), draft.methodName(),
                draft.methodSignature(), draft.startLine(), draft.endLine(), draft.annotations(),
                draft.hasLogStatements(), draft.calls(), callers, callers.size()));
        }

        Map<String, Object> resultStats = new LinkedHashMap<>();
        resultStats.put("method_count", methods.size());
        resultStats.put("call_count", stats.callCount);
        resultStats.put("resolved_call_count", stats.resolvedCallCount);
        resultStats.put("project_target_call_count", stats.projectTargetCallCount);
        resultStats.put("resolved_to_project_pct", percentage(stats.projectTargetCallCount, stats.callCount));
        resultStats.put("resolved_pct", percentage(stats.resolvedCallCount, stats.callCount));
        resultStats.put("jar_load_failures", typeSolvers.jarLoadFailures());
        resultStats.put("max_heap_bytes", maxHeapUsed);
        resultStats.put("graph_duration_ms", Duration.between(start, Instant.now()).toMillis());

        return new Result(List.copyOf(methods), Map.copyOf(resultStats));
    }

    private static MethodInfo buildMethodInfo(
        CallableDeclaration<?> declaration, String methodName, String methodSignature,
        BlockStmt body, FileUnit fileUnit, Map<Node, Integer> anonymousNumbers,
        CodeUnit codeUnit, ProjectMethodIds methodIds, ProjectInterfaceIndex interfaceIndex,
        Map<String, TypeDeclaration<?>> declarationsByFqn, Set<String> methodsWithLogStatements,
        Map<String, List<CallerRef>> callersByTargetId, CallStats stats
    ) {
        ClassContext classContext = ClassContextResolver.resolve(declaration, anonymousNumbers);
        String methodId = StableIds.methodId(codeUnit.name(), codeUnit.version(), classContext.classFqn(), methodSignature);
        String fileId = StableIds.fileId(codeUnit.name(), codeUnit.version(), fileUnit.file().filePath());
        List<String> annotations = declaration.getAnnotations().stream().map(a -> a.getNameAsString()).toList();
        boolean hasLogStatements = methodsWithLogStatements.contains(methodId);

        List<CallEdge> calls = new ArrayList<>();
        if (body != null) {
            for (Node callNode : GraphCallCollector.collect(body)) {
                List<CallEdge> edges = CallResolver.resolve(callNode, methodIds, interfaceIndex, declarationsByFqn);
                calls.addAll(edges);

                stats.callCount++;
                CallEdge primary = edges.get(0);
                if (primary.resolved()) {
                    stats.resolvedCallCount++;
                }
                if (primary.targetMethodId() != null) {
                    stats.projectTargetCallCount++;
                }
                for (CallEdge edge : edges) {
                    if (edge.targetMethodId() != null) {
                        callersByTargetId.computeIfAbsent(edge.targetMethodId(), k -> new ArrayList<>())
                            .add(new CallerRef(methodId, classContext.classFqn(), methodName, fileId, edge.line()));
                    }
                }
            }
        }

        return new MethodInfo(methodId, codeUnit, fileUnit.file().module(), fileUnit.file().service(), fileId,
            fileUnit.file().filePath(), classContext.classFqn(), classContext.classBinary(), methodName, methodSignature,
            AstLines.startLine(declaration), AstLines.endLine(declaration), annotations, hasLogStatements,
            List.copyOf(calls), List.of(), 0);
    }

    /** Method IDs (0.8 formula) of every method/constructor that directly contains at least one log statement. */
    private static Set<String> methodsWithLogStatements(List<CompilationUnit> units, CodeUnit codeUnit) {
        List<List<LogCall>> perFileCalls = LogCallDetector.detectAll(units);
        Set<String> result = new HashSet<>();
        for (int i = 0; i < units.size(); i++) {
            List<LogCall> calls = perFileCalls.get(i);
            if (calls.isEmpty()) {
                continue;
            }
            Map<Node, Integer> anonymousNumbers = AnonymousClassNumbering.compute(units.get(i));
            for (LogCall call : calls) {
                ClassContext classContext = ClassContextResolver.resolve(call.node(), anonymousNumbers);
                MethodContext methodContext = MethodContextResolver.resolve(call.node());
                result.add(StableIds.methodId(codeUnit.name(), codeUnit.version(), classContext.classFqn(), methodContext.methodSignature()));
            }
        }
        return result;
    }

    private static double percentage(long numerator, long denominator) {
        return denominator == 0 ? 0.0 : 100.0 * numerator / denominator;
    }
}
