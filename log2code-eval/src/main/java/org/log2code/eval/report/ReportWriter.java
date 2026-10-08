package org.log2code.eval.report;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import org.log2code.core.json.Json;
import org.log2code.core.model.Candidate;
import org.log2code.core.model.CatalogEntry;
import org.log2code.eval.EvalResult;
import org.log2code.eval.metrics.EventEvaluation;
import org.log2code.eval.metrics.StatementRow;
import org.log2code.eval.truth.CatalogView;
import org.log2code.eval.truth.TruthInfo;

/** Writes {@code report.md}, {@code metrics.json}, {@code errors.csv} and {@code per_statement.csv} (T33 step 5). */
public final class ReportWriter {

    private static final int MESSAGE_LIMIT = 300;
    private static final int TOP_CANDIDATES = 3;

    /** The shared snake_case mapper (0.14), but with {@code accuracy_at_1} instead of Jackson's {@code accuracy_at1}. */
    private static final ObjectMapper METRICS_MAPPER = Json.mapper().copy()
        .setPropertyNamingStrategy(new SnakeCaseWithDigits());

    /** Snake case that also separates a trailing number from the word before it. */
    private static final class SnakeCaseWithDigits extends PropertyNamingStrategies.NamingBase {
        private static final PropertyNamingStrategies.SnakeCaseStrategy PLAIN_SNAKE_CASE = new PropertyNamingStrategies.SnakeCaseStrategy();

        @Override
        public String translate(String propertyName) {
            return PLAIN_SNAKE_CASE.translate(propertyName).replaceAll("([a-z])(\\d)", "$1_$2");
        }
    }

    private ReportWriter() {
    }

    /** @return the folder the four files were written to ({@code outRoot/<dataset>}) */
    public static Path write(Path outRoot, EvalResult result, LocalDate date, String command) throws IOException {
        Path dir = outRoot.resolve(result.datasetId());
        Files.createDirectories(dir);

        Files.writeString(dir.resolve("report.md"), MarkdownReport.render(result, date, command), StandardCharsets.UTF_8);
        METRICS_MAPPER.writerWithDefaultPrettyPrinter().writeValue(dir.resolve("metrics.json").toFile(), result.metrics());
        writeErrors(dir.resolve("errors.csv"), result);
        writeStatements(dir.resolve("per_statement.csv"), result);
        return dir;
    }

    private static void writeErrors(Path file, EvalResult result) throws IOException {
        List<String> header = List.of("log_id", "service", "level", "source_file", "line_number", "message", "status",
            "confidence", "confidence_level", "predicted_statement_id", "predicted_class", "predicted_method",
            "predicted_line", "predicted_template", "truth_source", "truth_statement_ids", "truth_class",
            "truth_method", "truth_line", "truth_template", "truth_rank_in_candidates", "same_error_events",
            "top_candidates", "score_breakdown");
        List<List<?>> rows = new ArrayList<>();
        for (ErrorSample sample : result.errorSamples()) {
            EventEvaluation event = sample.event();
            Optional<CatalogEntry> predicted = event.covered() && event.predictedStatementId() != null
                ? result.catalog().byId(event.predictedStatementId()) : Optional.empty();
            TruthInfo truth = event.truthInfo();
            rows.add(Arrays.asList(event.logId(), event.service(), event.level(), event.sourceFile(), event.lineNumber(),
                oneLine(event.message(), MESSAGE_LIMIT), event.status(), event.confidence(), event.confidenceLevel(),
                event.covered() ? event.predictedStatementId() : null,
                predicted.map(CatalogEntry::classFqn).orElse(null), predicted.map(CatalogEntry::methodName).orElse(null),
                predicted.map(CatalogEntry::line).orElse(null), predicted.map(CatalogEntry::template).orElse(null),
                event.truthSource().name().toLowerCase(Locale.ROOT), String.join(" ", event.truthStatementIds()),
                truth == null ? null : truth.classFqn(), truth == null ? null : truth.methodName(),
                truth == null ? null : truth.line(), truth == null ? null : truth.template(),
                event.truthRank() == 0 ? "not in top 5" : event.truthRank(), sample.events(),
                topCandidates(event, result.catalog()), scoreBreakdown(event)));
        }
        Csv.write(file, header, rows);
    }

    private static void writeStatements(Path file, EvalResult result) throws IOException {
        List<String> header = List.of("truth_statement_ids", "artifact", "code_unit_type", "class", "method", "line",
            "logging_api", "template_kind", "template", "events", "covered", "correct_at_1", "accuracy_at_1",
            "correct_at_3", "accuracy_at_3", "top_prediction_id", "top_prediction_class", "top_prediction_method",
            "top_prediction_line", "top_prediction_template", "top_prediction_count");
        List<List<?>> rows = new ArrayList<>();
        for (StatementRow row : result.statements()) {
            TruthInfo info = row.info();
            Optional<CatalogEntry> top = row.topPredictionId() == null
                ? Optional.empty() : result.catalog().byId(row.topPredictionId());
            rows.add(Arrays.asList(String.join(" ", row.statementIds()), info.artifact(), info.codeUnitType(),
                info.classFqn(), info.methodName(), info.line(), info.loggingApi(), info.templateKind(), info.template(),
                row.events(), row.covered(), row.correctAt1(), row.accuracyAt1(), row.correctAt3(), row.accuracyAt3(),
                row.topPredictionId(), top.map(CatalogEntry::classFqn).orElse(null),
                top.map(CatalogEntry::methodName).orElse(null), top.map(CatalogEntry::line).orElse(null),
                top.map(CatalogEntry::template).orElse(null), row.topPredictionCount()));
        }
        Csv.write(file, header, rows);
    }

    private static String topCandidates(EventEvaluation event, CatalogView catalog) {
        List<String> parts = new ArrayList<>();
        for (Candidate candidate : event.candidates().stream().limit(TOP_CANDIDATES).toList()) {
            parts.add(describe(catalog, candidate.statementId()) + String.format(Locale.ROOT, " (%.2f)", candidate.score()));
        }
        return String.join(" | ", parts);
    }

    private static String scoreBreakdown(EventEvaluation event) {
        Map<String, Double> breakdown = event.scoreBreakdown();
        if (breakdown.isEmpty()) {
            return null;
        }
        try {
            return Json.mapper().writeValueAsString(new TreeMap<>(breakdown));
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** {@code Class#method:line} of a statement, or its id if the catalog does not have it. */
    static String describe(CatalogView catalog, String statementId) {
        return catalog.byId(statementId)
            .map(e -> shortClass(e.classFqn()) + "#" + e.methodName() + ":" + e.line())
            .orElse(statementId);
    }

    static String shortClass(String classFqn) {
        return classFqn == null ? "?" : classFqn.substring(classFqn.lastIndexOf('.') + 1);
    }

    static String oneLine(String text, int limit) {
        if (text == null) {
            return null;
        }
        String flat = text.replaceAll("\\s+", " ").strip();
        return flat.length() <= limit ? flat : flat.substring(0, limit - 1) + "…";
    }
}
