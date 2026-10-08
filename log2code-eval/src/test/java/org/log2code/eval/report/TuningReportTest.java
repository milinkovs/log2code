package org.log2code.eval.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.log2code.core.model.CodeVersion;
import org.log2code.eval.EvalUserException;
import org.log2code.eval.metrics.Score;
import org.log2code.eval.tune.ParameterSpace;
import org.log2code.eval.tune.Tuner;
import org.log2code.eval.tune.Validation;
import org.log2code.ingester.match.MatchingConfig;
import org.log2code.ingester.match.MatchingConfigLoader;

class TuningReportTest {

    private static final MatchingConfig CONFIG = MatchingConfigLoader.load(Path.of("..", "config", "matching.yml"));
    private static final CodeVersion CODE = new CodeVersion("petclinic", "abc123");
    private static final LocalDate DATE = LocalDate.of(2026, 10, 9);
    private static final String COMMAND = "scripts/eval.sh tune --dataset tune-02 --seed 42 --max-combos 60";

    /** A landscape where raising logger_exact helps, so the search has something to find. */
    private static Tuner.Result search() {
        return Tuner.run(CONFIG, c -> score(800 - (int) Math.round(Math.abs(c.weights().loggerExact() - 0.30) * 1000), 100, 99),
            new Tuner.Options(60, 42, 0.95));
    }

    @Test
    void theSearchReportStatesTheGoalTheMethodAndTheProposal() {
        String report = TuningReport.renderSearch(search(), "tune-02", CODE, DATE, COMMAND, List.of("`A#m:10`"));

        assertThat(report).startsWith("# Podešavanje težina matcher-a");
        assertThat(report).contains("`tune-02`", "abc123", COMMAND, "2026-10-09");
        assertThat(report).contains("## 1. Cilj i metod", "## 2. Rezultat na skupu za podešavanje", "## 3. Predložene vrednosti",
            "## 4. Najbolji pokušaji", "## 5. Tok pretrage", "## 6. Napomene i ograničenja");
        assertThat(report).contains("najveći accuracy@1 po događaju").contains("`seed` 42").contains("najviše 60 kombinacija");
        assertThat(report).contains("`A#m:10`");
        // all 18 parameters are listed, the changed one in bold
        for (ParameterSpace.Parameter parameter : ParameterSpace.PARAMETERS) {
            assertThat(report).contains("`" + parameter.name() + "`");
        }
        assertThat(report).containsPattern("\\| `logger_exact` \\| 0\\.2 \\| \\*\\*0\\.[2-3]\\d*\\*\\* \\|");
    }

    @Test
    void whenNothingBeatsTheBaselineTheReportSaysSo() {
        Tuner.Result flat = Tuner.run(CONFIG, c -> score(700, 100, 99), new Tuner.Options(40, 1, 0.95));

        String report = TuningReport.renderSearch(flat, "tune-02", CODE, DATE, COMMAND, List.of());

        assertThat(report).contains("Nijedna kombinacija nije bila bolja od podrazumevanih težina");
        assertThat(report).contains("0 sa više od podrazumevanih");
        assertThat(report).doesNotContain("**0.");
    }

    @Test
    void theValidationSectionComparesTheTwoConfigurationsAndGivesTheVerdict() {
        Validation.Result better = Validation.of("test-02", CODE, score(700, 100, 99), score(712, 100, 98), 0.95);

        String section = TuningReport.renderValidation(better, DATE, "scripts/eval.sh validate --dataset test-02");

        assertThat(section).startsWith("## 7. Validacija na test skupu `test-02`");
        assertThat(section).contains("evaluiran **jednom**", "**70.00%** (700 / 1000)", "**71.20%** (712 / 1000)", "+12 događaja");
        assertThat(section).contains("**više** tačnih događaja").contains("je ispunjen");
        assertThat(section).contains("je zamena `config/matching.yml` dozvoljena");
    }

    @Test
    void anIdenticalProposalOnlyDocumentsTheDefaultWeights() {
        Validation.Result same = Validation.of("test-02", CODE, score(700, 100, 99), score(700, 100, 99), 0.95, true);

        String section = TuningReport.renderValidation(same, DATE, "cmd");

        assertThat(section).contains("**jednak** podrazumevanim težinama").contains("`config/matching.yml` ostaje nepromenjen");
        assertThat(section).doesNotContain("zamena `config/matching.yml` dozvoljena");
    }

    @Test
    void aWorseProposalKeepsTheConfiguration() {
        Validation.Result worse = Validation.of("test-02", CODE, score(700, 100, 99), score(690, 100, 99), 0.95);

        String section = TuningReport.renderValidation(worse, DATE, "cmd");

        assertThat(section).contains("**manje** tačnih događaja").contains("`config/matching.yml` ostaje nepromenjen");
    }

    @Test
    void anImpreciseProposalIsFlaggedEvenWhenItIsMoreAccurate() {
        Validation.Result imprecise = Validation.of("test-02", CODE, score(700, 100, 99), score(750, 100, 90), 0.95);

        String section = TuningReport.renderValidation(imprecise, DATE, "cmd");

        assertThat(section).contains("**nije ispunjen**").contains("`config/matching.yml` ostaje nepromenjen");
    }

    @Test
    void writeSearchLeavesTheReportTheTrialsAndBothConfigurations(@TempDir Path dir) throws IOException {
        Tuner.Result result = search();

        TuningWriter.writeSearch(dir, result, CONFIG, "tune-02", CODE, List.of(), DATE, COMMAND);

        assertThat(Files.readString(dir.resolve("tuning.md"))).contains("# Podešavanje težina").contains("## 7. Validacija na test skupu")
            .contains("Još nije pokrenuta");
        assertThat(dir.resolve("tuning/search.md")).exists();
        List<String> trials = Files.readAllLines(dir.resolve("tuning/trials.csv"));
        assertThat(trials).hasSize(1 + result.trials().size());
        assertThat(trials.get(0)).startsWith("trial,phase,feasible,correct_at_1").endsWith("high,medium");
        assertThat(trials.get(1)).startsWith("0,baseline,true,700");
        // the proposal and the baseline are valid matching.yml files
        MatchingConfig proposed = MatchingConfigLoader.load(TuningWriter.proposedFile(dir));
        assertThat(proposed).isEqualTo(ParameterSpace.apply(CONFIG, result.best().values()));
        assertThat(MatchingConfigLoader.load(TuningWriter.baselineFile(dir))).isEqualTo(CONFIG);
    }

    @Test
    void writeValidationCompletesTheReportAndLeavesTheMarker(@TempDir Path dir) throws IOException {
        TuningWriter.writeSearch(dir, search(), CONFIG, "tune-02", CODE, List.of(), DATE, COMMAND);
        Validation.Result result = Validation.of("test-02", CODE, score(700, 100, 99), score(705, 100, 99), 0.95);

        TuningWriter.writeValidation(dir, result, DATE, "scripts/eval.sh validate --dataset test-02");

        String report = Files.readString(dir.resolve("tuning.md"));
        assertThat(report).contains("## 6. Napomene i ograničenja", "## 7. Validacija na test skupu `test-02`")
            .doesNotContain("Još nije pokrenuta");
        assertThat(TuningWriter.validationFile(dir, "test-02")).exists();
        assertThat(Files.readString(TuningWriter.validationFile(dir, "test-02"))).contains("\"not_worse\" : true", "\"verdict\" : \"BETTER\"");
    }

    @Test
    void aTestDatasetCanBeValidatedOnlyOnce(@TempDir Path dir) throws IOException {
        TuningWriter.writeSearch(dir, search(), CONFIG, "tune-02", CODE, List.of(), DATE, COMMAND);
        Validation.Result result = Validation.of("test-02", CODE, score(700, 100, 99), score(705, 100, 99), 0.95);
        TuningWriter.writeValidation(dir, result, DATE, "cmd");

        assertThatThrownBy(() -> TuningWriter.requireNotValidated(dir, "test-02"))
            .isInstanceOf(EvalUserException.class).hasMessageContaining("already validated").hasMessageContaining("exactly once");
        assertThatThrownBy(() -> TuningWriter.writeValidation(dir, result, DATE, "cmd")).isInstanceOf(EvalUserException.class);
        TuningWriter.requireNotValidated(dir, "test-03");
    }

    @Test
    void validationNeedsTheSearchReportThatTuneWrites(@TempDir Path dir) {
        Validation.Result result = Validation.of("test-02", CODE, score(700, 100, 99), score(705, 100, 99), 0.95);

        assertThatThrownBy(() -> TuningWriter.writeValidation(dir, result, DATE, "cmd"))
            .isInstanceOf(EvalUserException.class).hasMessageContaining("run 'scripts/eval.sh tune");
        assertThat(dir.resolve("test-02/validation.json")).doesNotExist();
    }

    private static Score score(int correct, int highCovered, int highCorrect) {
        return new Score(1000, 1000, 1000, correct, correct, 0, highCovered, highCorrect, 0, 0, 0, 0, 0, 0, 10, 5, 0.5,
            1000, correct, 0, 0, 0);
    }
}
