package org.log2code.analyzer.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DepsReportTest {

    @Test
    void rowFromStatsExtractsNumbersAndNestedMaps() {
        Map<String, Object> stats = Map.of(
            "file_count", 12,
            "statement_count", 5,
            "calls_by_logging_api", Map.of("slf4j", 5),
            "unsupported_by_reason", Map.of(),
            "parse_error_count", 0,
            "duration_ms", 340L
        );

        DepsReport.Row row = DepsReport.Row.from("g:a:1.0", stats, false);

        assertThat(row.fileCount()).isEqualTo(12);
        assertThat(row.statementCount()).isEqualTo(5);
        assertThat(row.byApi()).containsEntry("slf4j", 5L);
        assertThat(row.unsupportedByReason()).isEmpty();
        assertThat(row.durationMs()).isEqualTo(340L);
        assertThat(row.skipped()).isFalse();
    }

    @Test
    void rowFromMissingKeysDefaultsToZeroAndEmpty() {
        DepsReport.Row row = DepsReport.Row.from("g:a:1.0", Map.of(), true);

        assertThat(row.fileCount()).isZero();
        assertThat(row.statementCount()).isZero();
        assertThat(row.byApi()).isEmpty();
        assertThat(row.skipped()).isTrue();
    }

    @Test
    void renderIncludesATotalsRowSummedAcrossArtifacts() {
        DepsReport.Row a = new DepsReport.Row("g:a1:1.0", 10, 4, Map.of("slf4j", 4L), Map.of(), 0, 100, false);
        DepsReport.Row b = new DepsReport.Row("g:a2:2.0", 5, 2,
            Map.of("slf4j", 1L, "jcl", 1L), Map.of("unknown-signature", 1L), 1, 50, true);

        String markdown = DepsReport.render(List.of(a, b));

        assertThat(markdown).contains("| g:a1:1.0 | 10 | 4 | slf4j: 4 | — | 0 | 100 |");
        assertThat(markdown).contains("| g:a2:2.0 *(preskočeno)* | 5 | 2 | jcl: 1, slf4j: 1 | unknown-signature: 1 | 1 | 50 |");
        assertThat(markdown).contains("| **Ukupno** | 15 | 6 | jcl: 1, slf4j: 5 | unknown-signature: 1 | 1 | 150 |");
    }

    @Test
    void renderOfNoRowsStillHasAZeroTotalsRow() {
        String markdown = DepsReport.render(List.of());

        assertThat(markdown).contains("| **Ukupno** | 0 | 0 | — | — | 0 | 0 |");
    }

    @Test
    void writeCreatesParentDirectoriesAndFile(@TempDir Path dir) throws IOException {
        Path target = dir.resolve("analysis").resolve("deps-report.md");

        DepsReport.write(target, List.of(new DepsReport.Row("g:a:1.0", 1, 1, Map.of(), Map.of(), 0, 1, false)));

        assertThat(target).isRegularFile();
        assertThat(Files.readString(target)).contains("g:a:1.0");
    }
}
