package org.log2code.eval.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.log2code.eval.TestData.log;
import static org.log2code.eval.TestData.match;
import static org.log2code.eval.TestData.module;
import static org.log2code.eval.TestData.projectStatement;
import static org.log2code.eval.TestData.reliable;
import static org.log2code.eval.TestData.run;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.log2code.core.json.Json;
import org.log2code.core.model.CatalogEntry;
import org.log2code.core.model.EnrichedLog;
import org.log2code.eval.EvalResult;
import org.log2code.eval.EvalRunner;
import org.log2code.eval.TestData.InMemorySource;
import org.log2code.eval.truth.CatalogView;

class ReportWriterTest {

    private static final LocalDate DATE = LocalDate.of(2026, 10, 8);

    private EvalResult result() throws Exception {
        CatalogEntry a = projectStatement("a", "svc", "p.C", "p.C", "first", 10, 10);
        CatalogEntry b = projectStatement("b", "svc", "p.C", "p.C", "second", 20, 20);
        List<EnrichedLog> logs = List.of(
            log("ok", "svc", reliable("p.C", 10), match("matched", "a", "high", "a", "b")),
            // the id (and so the message) contains a comma and quotes, to exercise CSV quoting
            log("bad,\"id\"", "svc", reliable("p.C", 10), match("ambiguous", "b", "low", "b", "a")),
            log("missed", "svc", reliable("p.C", 20), match("unmatched", null, null)));
        CatalogView view = CatalogView.build(run(module("svc")), List.of(a, b));
        return EvalRunner.run(new InMemorySource(logs, Map.of(), view), "ds", 25, 42);
    }

    @Test
    void writesTheFourFilesIntoTheDatasetFolder(@TempDir Path out) throws Exception {
        Path dir = ReportWriter.write(out, result(), DATE, "scripts/eval.sh run --dataset ds");

        assertThat(dir).isEqualTo(out.resolve("ds"));
        assertThat(dir.resolve("report.md")).isRegularFile();
        assertThat(dir.resolve("metrics.json")).isRegularFile();
        assertThat(dir.resolve("errors.csv")).isRegularFile();
        assertThat(dir.resolve("per_statement.csv")).isRegularFile();
    }

    @Test
    void metricsJsonUsesSnakeCaseAndOmitsNothingThatHasAValue(@TempDir Path out) throws Exception {
        Path dir = ReportWriter.write(out, result(), DATE, "cmd");

        JsonNode json = Json.mapper().readTree(dir.resolve("metrics.json").toFile());

        assertThat(json.at("/dataset_id").asText()).isEqualTo("ds");
        assertThat(json.at("/code_version").asText()).isEqualTo("v1");
        assertThat(json.at("/counts/total_events").asInt()).isEqualTo(3);
        assertThat(json.at("/headline/accuracy_at_1").asDouble()).isEqualTo(1.0 / 3);   // only "ok" is right
        assertThat(json.at("/headline/accuracy_at_3").asDouble()).isEqualTo(2.0 / 3);   // "missed" has no candidates
        assertThat(json.at("/by_confidence_level/0/key").asText()).isEqualTo("high");
        assertThat(json.at("/breakdowns/code_unit_type/0/key").asText()).isEqualTo("project");
        assertThat(json.at("/unique_statements/statements").asInt()).isEqualTo(2);
    }

    @Test
    void errorsCsvHasOneRowPerSampledKindAndQuotesAwkwardText(@TempDir Path out) throws Exception {
        Path dir = ReportWriter.write(out, result(), DATE, "cmd");

        String csv = Files.readString(dir.resolve("errors.csv"), StandardCharsets.UTF_8);
        List<String> lines = csv.lines().toList();

        assertThat(lines.get(0)).startsWith("log_id,service,level,source_file,line_number,message,status");
        assertThat(lines.get(0)).contains("score_breakdown").contains("same_error_events").contains("truth_rank_in_candidates");
        assertThat(lines).hasSize(3);                        // header plus two kinds of mistake
        assertThat(csv).contains("\"bad,\"\"id\"\"\"");      // the log id, quoted with doubled quotes
        assertThat(csv).contains("message of missed").contains("unmatched");
        assertThat(csv).contains("{\"\"regex_full\"\":0.45}");  // score_breakdown as quoted JSON
    }

    @Test
    void perStatementCsvListsEveryCorrectStatement(@TempDir Path out) throws Exception {
        Path dir = ReportWriter.write(out, result(), DATE, "cmd");

        List<String> lines = Files.readAllLines(dir.resolve("per_statement.csv"), StandardCharsets.UTF_8);

        assertThat(lines).hasSize(3);                        // header plus statements a and b
        assertThat(lines.get(0)).startsWith("truth_statement_ids,artifact,code_unit_type,class,method,line");
        assertThat(lines.get(1)).startsWith("a,petclinic,project,p.C,first,10,");
        assertThat(lines.get(1)).contains(",2,2,1,0.5000,2,1.0000,");   // 2 events, 2 covered, 1 right at @1, 2 at @3
    }

    @Test
    void reportIsSerbianUtf8WithAllSectionsAndTheCommand(@TempDir Path out) throws Exception {
        Path dir = ReportWriter.write(out, result(), DATE, "scripts/eval.sh run --dataset ds");

        String report = Files.readString(dir.resolve("report.md"), StandardCharsets.UTF_8);

        assertThat(report).startsWith("# Evaluacija povezivanja: `ds`");
        assertThat(report).contains("2026-10-08").contains("scripts/eval.sh run --dataset ds");
        for (String heading : List.of("## 1. Ground truth", "## 2. Sažetak", "## 3. Kalibracija", "## 4. Rezultat po statusu",
            "## 5. Razlaganje metrika", "## 6. Tačnost po jedinstvenoj naredbi", "## 7. Greške", "## 8. Definicije metrika",
            "## 9. Napomene i ograničenja")) {
            assertThat(report).contains(heading);
        }
        assertThat(report).contains("| Accuracy@1 | 33.3% |");
        assertThat(report).contains("Nema ručnih oznaka");
        assertThat(report).doesNotContain("\r");
    }

    @Test
    void anEmptyErrorListIsReportedWithoutATable(@TempDir Path out) throws Exception {
        CatalogEntry a = projectStatement("a", "svc", "p.C", "p.C", "first", 10, 10);
        List<EnrichedLog> logs = List.of(log("ok", "svc", reliable("p.C", 10), match("matched", "a", "high", "a")));
        EvalResult perfect = EvalRunner.run(
            new InMemorySource(logs, Map.of(), CatalogView.build(run(module("svc")), List.of(a))), "perfect", 25, 42);

        Path dir = ReportWriter.write(out, perfect, DATE, "cmd");

        assertThat(Files.readString(dir.resolve("report.md"), StandardCharsets.UTF_8)).contains("Nema grešaka");
        assertThat(Files.readAllLines(dir.resolve("errors.csv"), StandardCharsets.UTF_8)).hasSize(1);   // header only
    }
}
