package org.log2code.eval.report;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.log2code.eval.ablate.AblationResult;
import org.log2code.eval.ablate.AblationRunner;
import org.log2code.eval.ablate.Variants;
import org.log2code.eval.replay.ReplayDataset;
import org.log2code.eval.replay.SyntheticDatasetAccess;
import org.log2code.ingester.match.MatchingConfig;
import org.log2code.ingester.match.MatchingConfigLoader;

class AblationReportTest {

    private static final MatchingConfig CONFIG = MatchingConfigLoader.load(Path.of("..", "config", "matching.yml"));
    private static final LocalDate DATE = LocalDate.of(2026, 10, 9);

    @Test
    void theReportListsEveryVariantWithItsResult() {
        AblationResult result = result();

        String report = AblationReport.render(result, SyntheticDatasetAccess.code(), SyntheticDatasetAccess.catalog(), DATE,
            "scripts/eval.sh ablate --dataset syn-01");

        assertThat(report).startsWith("# Ablacije matcher-a: `syn-01`");
        assertThat(report).contains("scripts/eval.sh ablate --dataset syn-01", "2026-10-09");
        for (String title : List.of("Pun matcher", "Bez loggera", "Bez nivoa", "Samo kandidati po loggeru",
            "Samo kandidati po tokenima", "Bez specifičnosti", "Samo regex")) {
            assertThat(report).contains(title);
        }
        // the full variant: 3 of 3, 100%; no_logger: 1 of 3
        assertThat(report).contains("100.00% (3 / 3)").contains("33.33% (1 / 3)");
    }

    @Test
    void theConclusionsNameTheLargestDropFirstAndTheVariantsWithoutEffect() {
        String report = AblationReport.render(result(), SyntheticDatasetAccess.code(), SyntheticDatasetAccess.catalog(), DATE, "cmd");

        int conclusions = report.indexOf("## 3. Zaključci");
        String section = report.substring(conclusions, report.indexOf("## 4. Napomene"));
        assertThat(section.indexOf("Bez loggera")).isLessThan(section.indexOf("Bez nivoa")); // 1 correct before 2 correct
        assertThat(section).contains("**Bez specifičnosti**: accuracy@1 se ne menja");
        assertThat(section).contains("Preciznost nivoa `high` ostaje najmanje 95%");
    }

    @Test
    void theNotesExplainTheErrorsAndTheDynamicTemplateEffect() {
        String report = AblationReport.render(result(), SyntheticDatasetAccess.code(), SyntheticDatasetAccess.catalog(), DATE, "cmd");

        String notes = report.substring(report.indexOf("## 4. Napomene"));
        assertThat(notes).contains("Dominantne naredbe").contains("Greške punog matchera");
        // the full variant makes no errors on the synthetic dataset
        assertThat(notes).contains("0 od 3 događaja za tačnost");
    }

    @Test
    void writesTheMarkdownAndOneCsvRowPerVariant(@TempDir Path dir) throws IOException {
        AblationReport.write(dir, result(), SyntheticDatasetAccess.code(), SyntheticDatasetAccess.catalog(), DATE, "cmd");

        assertThat(dir.resolve("ablation.md")).exists();
        List<String> rows = Files.readAllLines(dir.resolve("ablation.csv"));
        assertThat(rows).hasSize(1 + 7);
        assertThat(rows.get(0)).startsWith("variant,candidates,events,evaluable,correct_at_1,accuracy_at_1");
        assertThat(rows.get(1)).startsWith("full,both,3,3,3,1.0000");
        assertThat(rows.get(2)).startsWith("no_logger,both,3,3,1,0.3333");
        assertThat(rows.get(4)).startsWith("logger_candidates_only,logger_only,3,3,3");
    }

    private static AblationResult result() {
        ReplayDataset dataset = SyntheticDatasetAccess.dataset();
        return AblationRunner.run(dataset, CONFIG, Variants.standard());
    }
}
