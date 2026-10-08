package org.log2code.eval.report;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import org.log2code.core.model.CodeVersion;
import org.log2code.eval.ablate.AblationResult;
import org.log2code.eval.ablate.AblationResult.VariantResult;
import org.log2code.eval.metrics.Score;
import org.log2code.eval.truth.CatalogView;

/** Renders {@code ablation.md} (T34 step 4): the variants, one table of results and conclusions derived from the numbers. In Serbian (0.14). */
public final class AblationReport {

    /** Differences below this many percentage points are reported as "no effect". */
    private static final double NEGLIGIBLE_PP = 0.05;
    private static final double HIGH_PRECISION_FLOOR = 0.95;

    private AblationReport() {
    }

    /** Writes {@code ablation.md} and {@code ablation.csv} (one row per variant) to {@code outRoot}. */
    public static void write(Path outRoot, AblationResult result, CodeVersion code, CatalogView catalog, LocalDate date,
                             String command) throws IOException {
        Files.createDirectories(outRoot);
        Files.writeString(outRoot.resolve("ablation.md"), render(result, code, catalog, date, command), StandardCharsets.UTF_8);

        List<String> header = List.of("variant", "candidates", "events", "evaluable", "correct_at_1", "accuracy_at_1", "accuracy_at_3",
            "covered", "coverage", "ambiguous_share", "high_events", "high_correct", "precision_high", "control_accuracy_at_1",
            "statements", "statements_hit", "macro_accuracy_at_1", "not_in_catalog", "false_links");
        List<List<?>> rows = new ArrayList<>();
        for (VariantResult v : result.variants()) {
            Score s = v.score();
            rows.add(Arrays.asList(v.variant().id(), v.variant().candidateMode().name().toLowerCase(Locale.ROOT), s.events(), s.evaluable(),
                s.correctAt1(), s.accuracyAt1(), s.accuracyAt3(), s.covered(), s.coverage(), s.ambiguousShare(), s.highCovered(),
                s.highCorrect(), s.precisionHigh(), s.controlAccuracyAt1(), s.statements(), s.statementsHit(), s.macroAccuracyAt1(),
                s.notInCatalog(), s.falseLinks()));
        }
        Csv.write(outRoot.resolve("ablation.csv"), header, rows);
    }

    public static String render(AblationResult result, CodeVersion code, CatalogView catalog, LocalDate date, String command) {
        VariantResult full = result.full();
        StringBuilder out = new StringBuilder();

        out.append("# Ablacije matcher-a: `").append(result.datasetId()).append("`\n\n");
        out.append("- **Verzija koda:** `").append(code.name()).append("` @ `").append(code.version()).append("`\n");
        out.append("- **Generisano:** ").append(date).append(", komandom `").append(command).append("`\n");
        out.append("- **Ulaz:** događaji se ponovo sastavljaju iz fajlova dataset-a (`EventAssembler`), a matcher radi u memoriji nad katalogom "
            + "iz `log2code-catalog`. U OpenSearch se ništa ne upisuje. Ground truth je isti kao u `eval run`: ručna oznaka, pa pouzdan oracle. "
            + "Osnova su težine i pragovi iz `config/matching.yml`.\n");
        out.append("- **Skup:** ovo je skup za podešavanje. Test skup se ne koristi za ablacije; evaluira se jednom, u `tuning.md`.\n\n");

        variants(out, result);
        results(out, result, full);
        conclusions(out, result, full);
        notes(out, result, full, catalog);
        return out.toString();
    }

    private static void variants(StringBuilder out, AblationResult result) {
        out.append("## 1. Varijante\n\n");
        out.append("Svaka varijanta uklanja jednu stvar iz punog matchera. Pragovi (`min_score`, `ambiguity_margin`, `high`…) su isti u svim varijantama, "
            + "pa se razlike vide samo kroz bodovanje i kandidate.\n\n");
        List<List<String>> rows = new ArrayList<>();
        for (VariantResult v : result.variants()) {
            rows.add(List.of("**" + v.variant().title() + "** (`" + v.variant().id() + "`)", v.variant().description()));
        }
        Md.table(out, List.of("Varijanta", "Šta se menja"), rows);
    }

    private static void results(StringBuilder out, AblationResult result, VariantResult full) {
        Score f = full.score();
        out.append("## 2. Rezultati\n\n");
        out.append("Događaja: ").append(f.events()).append(", od toga za tačnost: ").append(f.evaluable())
            .append(" (ground truth je naredba iz kataloga). Razlika je prema punom matcher-u, u procentnim poenima (pp).\n\n");
        List<List<String>> rows = new ArrayList<>();
        for (VariantResult v : result.variants()) {
            Score s = v.score();
            boolean isFull = v == full;
            rows.add(List.of(
                (isFull ? "**" : "") + v.variant().title() + (isFull ? "**" : ""),
                Md.pct2(s.accuracyAt1()) + " (" + Md.frac(s.correctAt1(), s.evaluable()) + ")",
                isFull ? "—" : Md.pp(f.accuracyAt1(), s.accuracyAt1()),
                Md.pct(s.accuracyAt3()),
                Md.pct(s.coverage()),
                isFull ? "—" : Md.pp(f.coverage(), s.coverage()),
                Md.pct(s.precisionHigh()) + " (n=" + s.highCovered() + ")",
                Md.pct(s.ambiguousShare()),
                Md.pct(s.controlAccuracyAt1())));
        }
        Md.table(out, List.of("Varijanta", "Accuracy@1", "Δ @1", "Accuracy@3", "Pokrivenost", "Δ pokr.",
            "Preciznost `high`", "Udeo `ambiguous`", "Accuracy@1 bez dominantnih"), rows);
        out.append("""
            - **Accuracy@1:** tačna naredba je prvi izbor, nad događajima za tačnost; `unmatched` je promašaj.
            - **Accuracy@3:** tačna naredba je među prva 3 sačuvana kandidata (i kod `unmatched`).
            - **Pokrivenost:** udeo svih događaja sa statusom `matched` ili `ambiguous`.
            - **Preciznost `high`:** tačnih među predviđanjima sa nivoom pouzdanosti `high`; `n` je broj takvih događaja.
            - **Accuracy@1 bez dominantnih:** isto, ali bez događaja čija je tačna naredba jedna od dve najčešće u skupu (vidi napomene).

            """);

        List<List<String>> uniqueRows = new ArrayList<>();
        for (VariantResult v : result.variants()) {
            Score s = v.score();
            uniqueRows.add(List.of(v.variant().title(), Md.frac(s.statementsHit(), s.statements()),
                Md.pct(s.statementHitRate()), Md.pct(s.macroAccuracyAt1()), Md.pct(s.falseLinkShare())));
        }
        out.append("Tačnost po jedinstvenoj naredbi i lažna povezivanja (događaj čija naredba nije u katalogu, a ipak je dobio predviđanje):\n\n");
        Md.table(out, List.of("Varijanta", "Naredbe pogođene bar jednom", "Udeo", "Prosečna tačnost po naredbi (macro @1)", "Lažna povezivanja"), uniqueRows);
    }

    private static void conclusions(StringBuilder out, AblationResult result, VariantResult full) {
        Score f = full.score();
        out.append("## 3. Zaključci\n\n");
        List<VariantResult> others = result.variants().stream().filter(v -> v != full)
            .sorted(Comparator.comparingInt((VariantResult v) -> v.score().correctAt1())).toList();
        for (VariantResult v : others) {
            out.append("- ").append(sentence(v, f)).append("\n");
        }
        List<String> imprecise = result.variants().stream()
            .filter(v -> v.score().precisionHigh() != null && v.score().precisionHigh() < HIGH_PRECISION_FLOOR)
            .map(v -> v.variant().title()).toList();
        out.append("- ").append(imprecise.isEmpty()
            ? "Preciznost nivoa `high` ostaje najmanje 95% u svim varijantama."
            : "Preciznost nivoa `high` pada ispod 95% u varijantama: " + String.join(", ", imprecise) + ".").append("\n\n");
    }

    private static String sentence(VariantResult v, Score full) {
        Score s = v.score();
        double deltaPp = (s.accuracyAt1() - full.accuracyAt1()) * 100;
        double coveragePp = (s.coverage() - full.coverage()) * 100;
        String title = "**" + v.variant().title() + "**: ";
        String control = s.controlAccuracyAt1() == null || full.controlAccuracyAt1() == null ? ""
            : " Bez dominantnih naredbi: " + Md.pct(full.controlAccuracyAt1()) + " → " + Md.pct(s.controlAccuracyAt1())
            + " (" + Md.pp(full.controlAccuracyAt1(), s.controlAccuracyAt1()) + ").";
        String coverage = Math.abs(coveragePp) < NEGLIGIBLE_PP ? "pokrivenost se ne menja"
            : String.format(java.util.Locale.ROOT, "pokrivenost %s (%s)", coveragePp < 0 ? "pada" : "raste", Md.pp(full.coverage(), s.coverage()));
        if (Math.abs(deltaPp) < NEGLIGIBLE_PP) {
            return title + "accuracy@1 se ne menja (" + Md.pct2(s.accuracyAt1()) + "), " + coverage + ". Ova komponenta nema merljiv uticaj na ovom skupu." + control;
        }
        String direction = deltaPp < 0 ? "pada" : "raste";
        String tail = deltaPp < 0 ? "" : " Ova varijanta je po ovoj metrici bolja od punog matchera, pa tu komponentu treba proveriti u podešavanju.";
        return title + "accuracy@1 " + direction + " za " + String.format(java.util.Locale.ROOT, "%.2f", Math.abs(deltaPp))
            + " pp (sa " + Md.pct2(full.accuracyAt1()) + " na " + Md.pct2(s.accuracyAt1()) + "), " + coverage + "." + control + tail;
    }

    private static void errorAnalysis(StringBuilder out, Score f) {
        int other = f.errors() - f.tiedErrors() - f.unmatchedErrors();
        out.append("- **Greške punog matchera:** ").append(f.errors()).append(" od ").append(f.evaluable()).append(" događaja za tačnost. Od toga je ")
            .append(f.tiedErrors()).append(" izjednačenih (tačna naredba ima isti skor kao izabrana, pa odlučuje samo `statement_id`; nijedna težina to ne menja), ")
            .append(f.unmatchedErrors()).append(" bez predviđanja (`unmatched`) i ").append(other).append(" ostalih. Izvan dominantnih naredbi grešaka je ")
            .append(f.controlErrors()).append(", od toga izjednačenih ").append(f.controlTiedErrors()).append(".\n");
    }

    /** 0.10: a {@code dynamic} template scores at most 0.35, which is exactly {@code min_score}. */
    private static void dynamicNote(StringBuilder out, AblationResult result, CatalogView catalog) {
        List<String> kinds = result.dominantTruth().stream().flatMap(key -> Arrays.stream(key.split("\\+")))
            .map(id -> catalog.byId(id).map(e -> e.templateKind()).orElse("?")).toList();
        if (!kinds.isEmpty() && kinds.stream().allMatch("dynamic"::equals)) {
            out.append("- **Šablon `dynamic`:** dominantne naredbe imaju šablon `{}`. Po 0.10 takav kandidat nema bodove za regex ni za specifičnost, pa najviše može dobiti "
                + "logger + nivo + throwable = 0.35, što je jednako `min_score`. Zato svaka varijanta koja ukloni logger ili nivo, ili kandidate po loggeru, "
                + "gubi sve takve događaje (postaju `unmatched`), što objašnjava veliki pad u prvim redovima tabele.\n");
        }
    }

    private static void notes(StringBuilder out, AblationResult result, VariantResult full, CatalogView catalog) {
        Score f = full.score();
        out.append("## 4. Napomene\n\n");
        List<String> dominant = TuningReport.describeStatements(catalog, result.dominantTruth());
        out.append("- **Dominantne naredbe:** dve najčešće tačne naredbe u skupu su ").append(String.join(" i ", dominant))
            .append(". Zajedno čine ").append(Md.pct(f.evaluable() == 0 ? null : 1.0 - (double) f.controlEvaluable() / f.evaluable()))
            .append(" događaja za tačnost. Ako imaju isti šablon i nivo, nijedna težina ih ne razlikuje (identični signali, pobeđuje manji `statement_id`), "
                + "pa su one konstantan doprinos svim varijantama. Poslednja kolona ih izostavlja da se vidi efekat na ostatak.\n");
        errorAnalysis(out, f);
        dynamicNote(out, result, catalog);
        out.append("- **Kandidati:** u varijantama sa jednim izvorom kandidata isti događaj može dobiti manje kandidata; `unmatched` tada znači da izvor nije pronašao nijednu naredbu čiji regex odgovara poruci.\n");
        out.append("- **Poređenje sa `eval run`:** red \"Pun matcher\" mora da se poklapa sa izveštajem `docs/eval/").append(result.datasetId())
            .append("/report.md`, ako je dataset ingestovan sa istim `config/matching.yml`.\n");
    }
}
