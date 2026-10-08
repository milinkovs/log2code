package org.log2code.eval.report;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.log2code.core.json.Json;
import org.log2code.core.model.CodeVersion;
import org.log2code.eval.EvalUserException;
import org.log2code.eval.tune.MatchingConfigWriter;
import org.log2code.eval.tune.ParameterSpace;
import org.log2code.eval.tune.Tuner;
import org.log2code.eval.tune.Validation;
import org.log2code.ingester.match.MatchingConfig;

/**
 * Writes what {@code eval tune} and {@code eval validate} leave behind (T34 steps 2-4):
 *
 * <pre>
 * docs/eval/tuning.md                      the report (search, then validation once that has run)
 * docs/eval/tuning/search.md               the search part of the report, kept for validate to append to
 * docs/eval/tuning/trials.csv              every combination tried, with all 18 parameters
 * docs/eval/tuning/matching.baseline.yml   the configuration the search started from
 * docs/eval/tuning/matching.proposed.yml   the best combination found
 * docs/eval/&lt;test dataset&gt;/validation.json   the single validation, which also stops a second one
 * </pre>
 */
public final class TuningWriter {

    public static final String TUNING_DIR = "tuning";

    private TuningWriter() {
    }

    public static Path tuningDir(Path outRoot) {
        return outRoot.resolve(TUNING_DIR);
    }

    public static Path baselineFile(Path outRoot) {
        return tuningDir(outRoot).resolve("matching.baseline.yml");
    }

    public static Path proposedFile(Path outRoot) {
        return tuningDir(outRoot).resolve("matching.proposed.yml");
    }

    public static Path validationFile(Path outRoot, String datasetId) {
        return outRoot.resolve(datasetId).resolve("validation.json");
    }

    /** A test dataset is evaluated exactly once: once {@code validation.json} exists, validating it again is refused. */
    public static void requireNotValidated(Path outRoot, String datasetId) {
        Path marker = validationFile(outRoot, datasetId);
        if (Files.exists(marker)) {
            throw new EvalUserException("dataset '" + datasetId + "' was already validated (" + marker + "). A test dataset is evaluated "
                + "exactly once with the final weights; delete that file only if the earlier run failed before producing a result.");
        }
    }

    public static void writeSearch(Path outRoot, Tuner.Result result, MatchingConfig base, String datasetId, CodeVersion code,
                                   List<String> dominantStatements, LocalDate date, String command) throws IOException {
        Path dir = tuningDir(outRoot);
        Files.createDirectories(dir);

        String search = TuningReport.renderSearch(result, datasetId, code, date, command, dominantStatements);
        Files.writeString(dir.resolve("search.md"), search, StandardCharsets.UTF_8);
        Files.writeString(outRoot.resolve("tuning.md"), search
            + "## 7. Validacija na test skupu\n\nJoš nije pokrenuta. Pokreće se jednom, komandom `scripts/eval.sh validate --dataset <test-skup>`.\n",
            StandardCharsets.UTF_8);

        writeTrials(dir.resolve("trials.csv"), result);
        String generated = "Generisano komandom `" + command + "` (T34) nad skupom " + datasetId + ".\n";
        MatchingConfigWriter.write(baselineFile(outRoot), base,
            generated + "Početna konfiguracija pretrage: sadržaj config/matching.yml u trenutku pretrage.");
        MatchingConfigWriter.write(proposedFile(outRoot), ParameterSpace.apply(base, result.best().values()),
            generated + "Predlog težina i pragova (pokušaj " + result.best().index() + "). Ne primenjuje se dok ga validacija na test skupu ne potvrdi.");
    }

    /** Writes {@code validation.json} and completes {@code tuning.md}. */
    public static void writeValidation(Path outRoot, Validation.Result result, LocalDate date, String command) throws IOException {
        Path search = tuningDir(outRoot).resolve("search.md");
        if (!Files.isRegularFile(search)) {
            throw new EvalUserException("no tuning report at " + search.toAbsolutePath() + "; run 'scripts/eval.sh tune --dataset <tuning-set>' first.");
        }
        requireNotValidated(outRoot, result.datasetId());
        Path marker = validationFile(outRoot, result.datasetId());
        Files.createDirectories(marker.getParent());
        Json.mapper().writerWithDefaultPrettyPrinter().writeValue(marker.toFile(), new ValidationRecord(date, command, result.notWorse(), result));

        Files.writeString(outRoot.resolve("tuning.md"),
            Files.readString(search, StandardCharsets.UTF_8) + TuningReport.renderValidation(result, date, command), StandardCharsets.UTF_8);
    }

    /** What {@code validation.json} holds: the date and command next to the whole result. */
    record ValidationRecord(LocalDate date, String command, boolean notWorse, Validation.Result result) {
    }

    private static void writeTrials(Path file, Tuner.Result result) throws IOException {
        List<String> header = new ArrayList<>(List.of("trial", "phase", "feasible", "correct_at_1", "accuracy_at_1", "accuracy_at_3",
            "coverage", "precision_high", "high_events", "control_accuracy_at_1", "distance"));
        ParameterSpace.PARAMETERS.forEach(p -> header.add(p.name()));
        List<List<?>> rows = new ArrayList<>();
        for (Tuner.Trial t : result.trials()) {
            List<Object> row = new ArrayList<>(List.of(t.index(), t.phase().name().toLowerCase(java.util.Locale.ROOT), t.feasible(),
                t.score().correctAt1()));
            row.add(t.score().accuracyAt1());
            row.add(t.score().accuracyAt3());
            row.add(t.score().coverage());
            row.add(t.score().precisionHigh());
            row.add(t.score().highCovered());
            row.add(t.score().controlAccuracyAt1());
            row.add(t.distance());
            for (double value : t.values()) {
                row.add(Md.number(value));
            }
            rows.add(row);
        }
        Csv.write(file, header, rows);
    }
}
