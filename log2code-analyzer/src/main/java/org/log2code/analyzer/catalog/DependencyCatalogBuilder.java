package org.log2code.analyzer.catalog;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.TypeDeclaration;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.log2code.analyzer.ast.JarSources;
import org.log2code.analyzer.logging.CodeUnitIndexer;
import org.log2code.analyzer.logging.LogCall;
import org.log2code.analyzer.logging.LogCallDetector;
import org.log2code.analyzer.logging.TypeIndex;
import org.log2code.analyzer.template.ConstantIndex;
import org.log2code.analyzer.template.ExtractedTemplate;
import org.log2code.analyzer.template.MessageTemplateExtractor;
import org.log2code.analyzer.template.TemplateKind;
import org.log2code.core.model.CatalogEntry;
import org.log2code.core.model.CodeUnit;
import org.log2code.core.model.SourceFile;
import org.log2code.core.model.TypeInfo;

/**
 * Assembles the catalog for one dependency artifact (T14 step 3): reads every {@code .java} entry
 * directly from its {@code -sources.jar} ({@link JarSources}, ZipFile, no unpacking - step 2), then
 * runs the exact same syntax-only pipeline as {@link ProjectCatalogBuilder} (T08 detection, T09
 * templates, T10 level 1, T11 level 2 control context - all unmodified, ADR-008) over the whole jar as
 * a single code unit. Two differences from the project builder (T14 step 6, 0.7): a {@link SourceFile}
 * is only built for a file with at least one detected log statement (not every file), and there is no
 * module loop - one jar is one flat file set, {@code module = code_unit.name} ({@code groupId:artifactId}),
 * {@code service = null}. No level 3 (call graph): dependencies never get a {@code method_id} - already
 * handled inside {@link CatalogEntryBuilder} for any non-{@code project} code unit.
 *
 * <p>Memory (T14 step 4): one artifact's files are parsed and held in memory together - like
 * {@link ProjectCatalogBuilder} already does for the whole project - because T08's inherited-logger
 * resolution and T09's cross-file constant resolution both need a whole-code-unit view (ADR-008/ADR-009).
 * A dependency artifact is its own code unit (unlike the project, where every module is one code unit
 * together), so this bounds peak memory to the single largest selected artifact, not the sum of all of
 * them; {@code deps analyze} (T14) discards one artifact's results before parsing the next.
 */
public final class DependencyCatalogBuilder {

    private DependencyCatalogBuilder() {
    }

    /** Everything T14 step 6 writes, plus {@link org.log2code.core.model.AnalysisRun#stats()} counters. */
    public record Result(List<CatalogEntry> catalog, List<SourceFile> sources, List<TypeInfo> types,
                          List<JarSources.ParseFailure> parseFailures, Map<String, Object> stats) {
    }

    private record FileUnit(FileInfo file, CompilationUnit unit, byte[] bytes, List<String> lines) {
    }

    private record OrdinalKey(String classFqn, String methodSignature, String template) {
    }

    public static Result build(Path sourcesJar, CodeUnit codeUnit, int snippetLines, int maxPrecedingStatements,
                                String analyzerVersion, Instant analyzedAt) {
        Instant start = Instant.now();
        String module = codeUnit.name();

        JarSources.Result parsed = JarSources.parseAll(sourcesJar);
        List<FileUnit> fileUnits = new ArrayList<>();
        for (JarSources.ParsedFile file : parsed.files()) {
            String packageName = file.unit().getPackageDeclaration().map(pd -> pd.getNameAsString()).orElse("");
            FileInfo fileInfo = new FileInfo(module, null, file.relativePath(), packageName);
            List<String> lines = new String(file.bytes(), StandardCharsets.UTF_8).lines().toList();
            fileUnits.add(new FileUnit(fileInfo, file.unit(), file.bytes(), lines));
        }

        List<CompilationUnit> units = fileUnits.stream().map(FileUnit::unit).toList();
        List<List<LogCall>> perFileCalls = LogCallDetector.detectAll(units);
        ConstantIndex constants = ConstantIndex.build(units);
        TypeIndex typeIndex = CodeUnitIndexer.index(units);

        List<TypeInfo> types = new ArrayList<>();
        for (FileUnit fileUnit : fileUnits) {
            for (TypeDeclaration<?> type : fileUnit.unit().findAll(TypeDeclaration.class)) {
                types.add(TypeInfoBuilder.build(type, typeIndex, fileUnit.file(), codeUnit));
            }
        }

        List<CatalogEntry> catalog = new ArrayList<>();
        List<SourceFile> sources = new ArrayList<>();
        Map<String, Long> callsByLoggingApi = new LinkedHashMap<>();
        Map<String, Long> callsByTemplateKind = new LinkedHashMap<>();
        Map<String, Long> callsByDetection = new LinkedHashMap<>();
        Map<String, Long> unsupportedByReason = new LinkedHashMap<>();

        for (int i = 0; i < fileUnits.size(); i++) {
            List<LogCall> calls = perFileCalls.get(i);
            if (calls.isEmpty()) {
                continue;
            }
            FileUnit fileUnit = fileUnits.get(i);
            sources.add(SourceFileBuilder.build(fileUnit.bytes(), fileUnit.file(), codeUnit));

            Map<Node, Integer> anonymousClassNumbers = AnonymousClassNumbering.compute(fileUnit.unit());
            Map<OrdinalKey, Integer> ordinals = new LinkedHashMap<>();

            for (LogCall call : calls) {
                ExtractedTemplate extracted = MessageTemplateExtractor.extract(call, constants);
                ClassContext classContext = ClassContextResolver.resolve(call.node(), anonymousClassNumbers);
                MethodContext methodContext = MethodContextResolver.resolve(call.node());
                String templateForOrdinal = extracted.template() != null ? extracted.template().toNormalized() : null;
                OrdinalKey key = new OrdinalKey(classContext.classFqn(), methodContext.methodSignature(), templateForOrdinal);
                int ordinal = ordinals.merge(key, 0, (oldValue, ignored) -> oldValue + 1);

                catalog.add(CatalogEntryBuilder.build(call, extracted, anonymousClassNumbers, fileUnit.file(),
                    codeUnit, ordinal, fileUnit.lines(), snippetLines, maxPrecedingStatements, analyzerVersion, analyzedAt));

                callsByLoggingApi.merge(call.api(), 1L, Long::sum);
                callsByTemplateKind.merge(extracted.templateKind(), 1L, Long::sum);
                callsByDetection.merge(call.detection(), 1L, Long::sum);
                if (TemplateKind.UNSUPPORTED.equals(extracted.templateKind())) {
                    unsupportedByReason.merge(extracted.unsupportedReason(), 1L, Long::sum);
                }
            }
        }

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("file_count", fileUnits.size());
        stats.put("source_file_count", sources.size());
        stats.put("type_count", types.size());
        stats.put("parse_error_count", parsed.failures().size());
        stats.put("statement_count", catalog.size());
        stats.put("calls_by_logging_api", callsByLoggingApi);
        stats.put("calls_by_template_kind", callsByTemplateKind);
        stats.put("calls_by_detection", callsByDetection);
        stats.put("unsupported_by_reason", unsupportedByReason);
        stats.put("duration_ms", Duration.between(start, Instant.now()).toMillis());

        return new Result(List.copyOf(catalog), List.copyOf(sources), List.copyOf(types),
            parsed.failures(), Map.copyOf(stats));
    }
}
