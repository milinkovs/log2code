package org.log2code.eval;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.log2code.core.model.AnalysisRun;
import org.log2code.core.model.Candidate;
import org.log2code.core.model.CatalogEntry;
import org.log2code.core.model.CodeUnit;
import org.log2code.core.model.CodeVersion;
import org.log2code.core.model.EnrichedLog;
import org.log2code.core.model.GroundTruth;
import org.log2code.core.model.Label;
import org.log2code.core.model.Level;
import org.log2code.core.model.MatchResult;
import org.log2code.core.model.ModuleInfo;
import org.log2code.eval.data.EvalDataSource;
import org.log2code.eval.truth.CatalogView;

/** Builders for the many-field model records the evaluation tests need. */
public final class TestData {

    public static final String PROJECT = "petclinic";
    public static final String VERSION = "v1";
    public static final CodeUnit PROJECT_UNIT = new CodeUnit(CodeUnit.TYPE_PROJECT, PROJECT, VERSION);
    public static final CodeVersion CODE = new CodeVersion(PROJECT, VERSION);

    /** Gives every built log its own line number, so sorting by (file, line) is never ambiguous. */
    private static final AtomicInteger NEXT_LINE = new AtomicInteger();

    private TestData() {
    }

    public static CodeUnit dependency(String name, String version) {
        return new CodeUnit(CodeUnit.TYPE_DEPENDENCY, name, version);
    }

    /** A project statement of {@code service}; {@code classFqn} may differ from {@code classBinary} for nested classes. */
    public static CatalogEntry projectStatement(String id, String service, String classBinary, String classFqn,
                                                String method, int line, int endLine) {
        return statement(id, PROJECT_UNIT, service, classBinary, classFqn, method, line, endLine, "placeholders", "msg " + id + " {}");
    }

    public static CatalogEntry dependencyStatement(String id, CodeUnit unit, String classFqn, String method, int line, int endLine) {
        return statement(id, unit, null, classFqn, classFqn, method, line, endLine, "placeholders", "msg " + id + " {}");
    }

    public static CatalogEntry statement(String id, CodeUnit unit, String service, String classBinary, String classFqn,
                                         String method, int line, int endLine, String templateKind, String template) {
        return new CatalogEntry(id, "logical-" + id, unit, "module", service, "src/" + classFqn + ".java", "file-" + id,
            "pkg", classFqn, classBinary, method, method + "()", null, false, line, endLine, 1, line - 5, endLine + 5,
            "slf4j", "typed", "log", classFqn, "class_literal", Level.INFO, false, template, template, templateKind,
            null, "^x$", List.of(), 10, 1, false, null, null, "", line - 2, null, "test", Instant.EPOCH);
    }

    public static ModuleInfo module(String service, String... selectedDependencies) {
        return new ModuleInfo("module-" + service, service, List.of(), List.of(), List.of(selectedDependencies));
    }

    public static AnalysisRun run(ModuleInfo... modules) {
        return new AnalysisRun("run-1", CodeUnit.TYPE_PROJECT, PROJECT_UNIT, "https://example.org/repo", "test",
            Instant.EPOCH, Instant.EPOCH, 0, Map.of(), List.of(modules));
    }

    public static MatchResult match(String status, String statementId, String confidenceLevel, String... candidateIds) {
        List<Candidate> candidates = new ArrayList<>();
        double score = 0.9;
        for (String id : candidateIds) {
            candidates.add(new Candidate(id, score));
            score -= 0.1;
        }
        return new MatchResult(status, statementId, statementId == null ? null : 0.8, confidenceLevel, candidates,
            statementId == null ? null : Map.of("regex_full", 0.45), List.of(), null, null, null, null, null, null,
            null, null, null, null);
    }

    public static GroundTruth reliable(String className, int line) {
        return new GroundTruth(className, "method", line, true);
    }

    public static GroundTruth unreliable(String className, int line) {
        return new GroundTruth(className, "log", line, false);
    }

    public static EnrichedLog log(String logId, String service, GroundTruth truth, MatchResult match) {
        return new EnrichedLog(logId, Instant.EPOCH, "t", "ds", "logs/" + service + ".log", NEXT_LINE.incrementAndGet(), 1,
            0, service, "module-" + service, service, "1", "main", Level.INFO, "logger", null, "message of " + logId,
            "raw of " + logId, null, null, null, CODE, match, truth, "spring-boot-default", "test", Instant.EPOCH);
    }

    public static Label label(String logId, String verdict, String correctStatementId, String predictedStatementId) {
        return new Label(logId, "ds", verdict, correctStatementId, predictedStatementId, null, Instant.EPOCH);
    }

    /** An evaluation data source serving fixed events, labels and catalog. */
    public record InMemorySource(List<EnrichedLog> events, Map<String, Label> labels, CatalogView catalog)
        implements EvalDataSource {

        @Override
        public List<EnrichedLog> events(String datasetId) {
            return events;
        }

        @Override
        public Map<String, Label> labels(String datasetId) {
            return labels;
        }

        @Override
        public CatalogView catalog(CodeVersion code) {
            return catalog;
        }
    }
}
