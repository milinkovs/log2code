package org.log2code.analyzer.cli;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Builds {@code docs/analysis/deps-report.md} (T14 step 7): one row per analyzed artifact - artifact,
 * file count, statement count, statements by {@code logging_api}, unsupported statements by reason,
 * parse errors and duration - plus a totals row at the end.
 */
final class DepsReport {

    /** One artifact's row. {@code skipped}: T14 step 5 (idempotent skip) reused an existing run's stats. */
    record Row(String artifact, long fileCount, long statementCount, Map<String, Long> byApi,
               Map<String, Long> unsupportedByReason, long parseErrorCount, long durationMs, boolean skipped) {

        static Row from(String artifact, Map<String, Object> stats, boolean skipped) {
            return new Row(artifact, longOf(stats, "file_count"), longOf(stats, "statement_count"),
                mapOf(stats, "calls_by_logging_api"), mapOf(stats, "unsupported_by_reason"),
                longOf(stats, "parse_error_count"), longOf(stats, "duration_ms"), skipped);
        }

        private static long longOf(Map<String, Object> stats, String key) {
            Object value = stats.get(key);
            return value instanceof Number number ? number.longValue() : 0L;
        }

        @SuppressWarnings("unchecked")
        private static Map<String, Long> mapOf(Map<String, Object> stats, String key) {
            Object value = stats.get(key);
            if (!(value instanceof Map<?, ?> raw)) {
                return Map.of();
            }
            Map<String, Long> result = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : ((Map<Object, Object>) raw).entrySet()) {
                result.put(String.valueOf(entry.getKey()), ((Number) entry.getValue()).longValue());
            }
            return result;
        }
    }

    private DepsReport() {
    }

    static void write(Path target, List<Row> rows) throws IOException {
        if (target.getParent() != null) {
            Files.createDirectories(target.getParent());
        }
        Files.writeString(target, render(rows), StandardCharsets.UTF_8);
    }

    static String render(List<Row> rows) {
        StringBuilder md = new StringBuilder();
        md.append("# log2code-analyzer — izveštaj analize zavisnosti (T14)\n\n");
        md.append("Generiše `scripts/analyzer.sh deps analyze` (T14 korak 7, 0.13: ovaj fajl se ne verzioniše, 0.2 pravilo 10). ")
            .append("Jedan red po izabranom artefaktu iz `data/work/deps/deps-manifest.json`; poslednji red su ukupne vrednosti. ")
            .append("„(preskočeno)” znači da je artefakt već bio analiziran istom verzijom analizatora (T14 korak 5) - brojevi su iz postojećeg upisa, ne iz ovog pokretanja.\n\n");
        md.append("| Artefakt | Fajlovi | Naredbe | Po API-ju | Nepodržano (razlog) | Greške parsiranja | Trajanje (ms) |\n");
        md.append("|---|---|---|---|---|---|---|\n");

        long totalFiles = 0;
        long totalStatements = 0;
        long totalParseErrors = 0;
        long totalDuration = 0;
        Map<String, Long> totalByApi = new TreeMap<>();
        Map<String, Long> totalUnsupported = new TreeMap<>();

        for (Row row : rows) {
            String artifact = row.skipped() ? row.artifact() + " *(preskočeno)*" : row.artifact();
            md.append("| ").append(artifact)
                .append(" | ").append(row.fileCount())
                .append(" | ").append(row.statementCount())
                .append(" | ").append(formatMap(row.byApi()))
                .append(" | ").append(formatMap(row.unsupportedByReason()))
                .append(" | ").append(row.parseErrorCount())
                .append(" | ").append(row.durationMs())
                .append(" |\n");

            totalFiles += row.fileCount();
            totalStatements += row.statementCount();
            totalParseErrors += row.parseErrorCount();
            totalDuration += row.durationMs();
            row.byApi().forEach((k, v) -> totalByApi.merge(k, v, Long::sum));
            row.unsupportedByReason().forEach((k, v) -> totalUnsupported.merge(k, v, Long::sum));
        }

        md.append("| **Ukupno** | ").append(totalFiles)
            .append(" | ").append(totalStatements)
            .append(" | ").append(formatMap(totalByApi))
            .append(" | ").append(formatMap(totalUnsupported))
            .append(" | ").append(totalParseErrors)
            .append(" | ").append(totalDuration)
            .append(" |\n");

        return md.toString();
    }

    private static String formatMap(Map<String, Long> map) {
        if (map.isEmpty()) {
            return "—";
        }
        Map<String, Long> sorted = new TreeMap<>(map);
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (Map.Entry<String, Long> entry : sorted.entrySet()) {
            if (!first) {
                sb.append(", ");
            }
            sb.append(entry.getKey()).append(": ").append(entry.getValue());
            first = false;
        }
        return sb.toString();
    }
}
