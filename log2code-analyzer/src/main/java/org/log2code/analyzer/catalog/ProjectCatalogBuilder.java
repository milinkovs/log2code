package org.log2code.analyzer.catalog;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.TypeDeclaration;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.log2code.analyzer.ast.JavaSources;
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
import org.log2code.core.model.ModuleInfo;
import org.log2code.core.model.SourceFile;
import org.log2code.core.model.TypeInfo;

/**
 * Assembles the whole project catalog (T10): every {@link CatalogEntry}, {@link SourceFile} and
 * {@link TypeInfo} for one project code version. All modules' sources are parsed and detected as a
 * single code unit (T08/T09's own convention, so inheritance and constants resolve across module
 * boundaries), then split back out per file/module when building documents. Pure: the only I/O is
 * reading the project's {@code .java} files.
 */
public final class ProjectCatalogBuilder {

    private ProjectCatalogBuilder() {
    }

    /** Everything T10 step 4 writes, plus the {@link org.log2code.core.model.AnalysisRun#stats()} counters. */
    public record Result(List<CatalogEntry> catalog, List<SourceFile> sources, List<TypeInfo> types, Map<String, Object> stats) {
    }

    private record FileUnit(FileInfo file, CompilationUnit unit, List<String> lines) {
    }

    private record OrdinalKey(String classFqn, String methodSignature, String template) {
    }

    public static Result build(Path projectRoot, List<ModuleInfo> modules, CodeUnit codeUnit,
                                int snippetLines, String analyzerVersion, Instant analyzedAt) {
        Instant start = Instant.now();

        List<FileUnit> fileUnits = new ArrayList<>();
        List<SourceFile> sources = new ArrayList<>();
        long parseErrorCount = 0;

        for (ModuleInfo module : modules) {
            for (String sourceRoot : module.sourceRoots()) {
                Path moduleSourceRoot = projectRoot.resolve(module.module()).resolve(sourceRoot);
                JavaSources.Result parsed = JavaSources.parseAll(moduleSourceRoot);
                for (JavaSources.ParsedFile parsedFile : parsed.files()) {
                    FileInfo file = fileInfo(module, sourceRoot, parsedFile.relativePath(),
                        parsedFile.unit().getPackageDeclaration().map(pd -> pd.getNameAsString()).orElse(""));
                    byte[] bytes = readBytes(moduleSourceRoot.resolve(parsedFile.relativePath()));
                    sources.add(SourceFileBuilder.build(bytes, file, codeUnit));
                    List<String> lines = new String(bytes, StandardCharsets.UTF_8).lines().toList();
                    fileUnits.add(new FileUnit(file, parsedFile.unit(), lines));
                }
                for (JavaSources.ParseFailure failure : parsed.failures()) {
                    FileInfo file = fileInfo(module, sourceRoot, failure.relativePath(), "");
                    byte[] bytes = readBytes(moduleSourceRoot.resolve(failure.relativePath()));
                    sources.add(SourceFileBuilder.build(bytes, file, codeUnit));
                }
                parseErrorCount += parsed.failures().size();
            }
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
            List<String> lines = fileUnit.lines();
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
                    codeUnit, ordinal, lines, snippetLines, analyzerVersion, analyzedAt));

                callsByLoggingApi.merge(call.api(), 1L, Long::sum);
                callsByTemplateKind.merge(extracted.templateKind(), 1L, Long::sum);
                callsByDetection.merge(call.detection(), 1L, Long::sum);
                if (TemplateKind.UNSUPPORTED.equals(extracted.templateKind())) {
                    unsupportedByReason.merge(extracted.unsupportedReason(), 1L, Long::sum);
                }
            }
        }

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("module_count", modules.size());
        stats.put("file_count", sources.size());
        stats.put("parse_error_count", parseErrorCount);
        stats.put("statement_count", catalog.size());
        stats.put("calls_by_logging_api", callsByLoggingApi);
        stats.put("calls_by_template_kind", callsByTemplateKind);
        stats.put("calls_by_detection", callsByDetection);
        stats.put("unsupported_by_reason", unsupportedByReason);
        stats.put("duration_ms", Duration.between(start, Instant.now()).toMillis());

        return new Result(List.copyOf(catalog), List.copyOf(sources), List.copyOf(types), Map.copyOf(stats));
    }

    private static FileInfo fileInfo(ModuleInfo module, String sourceRoot, Path relativePath, String packageName) {
        String filePath = module.module() + "/" + sourceRoot + "/" + relativePath.toString().replace('\\', '/');
        return new FileInfo(module.module(), module.service(), filePath, packageName);
    }

    private static byte[] readBytes(Path path) {
        try {
            return Files.readAllBytes(path);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to read " + path, e);
        }
    }
}
