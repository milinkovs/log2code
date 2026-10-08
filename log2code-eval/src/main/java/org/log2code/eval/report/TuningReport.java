package org.log2code.eval.report;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import org.log2code.core.model.CodeVersion;
import org.log2code.eval.metrics.Score;
import org.log2code.eval.truth.CatalogView;
import org.log2code.eval.tune.ParameterSpace;
import org.log2code.eval.tune.Tuner;
import org.log2code.eval.tune.Validation;

/** Renders {@code tuning.md} (T34 step 4): the search on the tuning dataset, then the single validation on the test dataset. In Serbian (0.14). */
public final class TuningReport {

    private static final int TOP_TRIALS = 10;

    private TuningReport() {
    }

    /**
     * {@code Class#method:line} of the statement(s) behind each truth key (a key joins the ids of the statements
     * that count as correct together with {@code +}), as inline code for the reports.
     */
    public static List<String> describeStatements(CatalogView catalog, Collection<String> truthKeys) {
        return truthKeys.stream()
            .map(key -> String.join(", ", Arrays.stream(key.split("\\+"))
                .map(id -> "`" + ReportWriter.describe(catalog, id) + "`").toList()))
            .toList();
    }

    /** The search part, starting with the title. {@code tune} writes it to {@code tuning/search.md}; {@code validate} appends the validation to it. */
    public static String renderSearch(Tuner.Result result, String datasetId, CodeVersion code, LocalDate date, String command,
                                      List<String> dominantStatements) {
        Tuner.Trial baseline = result.baseline();
        Tuner.Trial best = result.best();
        StringBuilder out = new StringBuilder();

        out.append("# Podešavanje težina matcher-a\n\n");
        out.append("- **Skup za podešavanje:** `").append(datasetId).append("` (verzija koda `").append(code.name()).append("` @ `")
            .append(code.version()).append("`)\n");
        out.append("- **Generisano:** ").append(date).append(", komandom `").append(command).append("`\n\n");

        method(out, result);
        outcome(out, result, baseline, best);
        proposal(out, baseline, best);
        topTrials(out, result);
        course(out, result);
        notes(out, result, dominantStatements);
        return out.toString();
    }

    /** The validation part. */
    public static String renderValidation(Validation.Result v, LocalDate date, String command) {
        Score b = v.baseline();
        Score p = v.proposed();
        StringBuilder out = new StringBuilder();
        out.append("## 7. Validacija na test skupu `").append(v.datasetId()).append("`\n\n");
        out.append("Test skup je evaluiran **jednom**, ").append(date).append(", komandom `").append(command)
            .append("`, sa konačnim težinama iz odeljka 3. Nije korišćen ni za jednu odluku u pretrazi. Podrazumevane težine su na ovom skupu "
                + "već jednom merene u T33 (`eval run`, ADR-044 tačka 7); ovde se mere iznova, u istoj proceduri kao podešene, da bi poređenje bilo čisto.\n\n");

        List<List<String>> rows = new ArrayList<>();
        rows.add(List.of("Događaja za tačnost", String.valueOf(b.evaluable()), String.valueOf(p.evaluable()), "—"));
        rows.add(List.of("**Accuracy@1**", "**" + Md.pct2(b.accuracyAt1()) + "** (" + Md.frac(b.correctAt1(), b.evaluable()) + ")",
            "**" + Md.pct2(p.accuracyAt1()) + "** (" + Md.frac(p.correctAt1(), p.evaluable()) + ")",
            Md.pp(b.accuracyAt1(), p.accuracyAt1()) + " (" + Md.delta(b.correctAt1(), p.correctAt1()) + " događaja)"));
        rows.add(List.of("Accuracy@3", Md.pct(b.accuracyAt3()), Md.pct(p.accuracyAt3()), Md.pp(b.accuracyAt3(), p.accuracyAt3())));
        rows.add(List.of("Pokrivenost", Md.pct(b.coverage()), Md.pct(p.coverage()), Md.pp(b.coverage(), p.coverage())));
        rows.add(List.of("Preciznost `high`", Md.pct(b.precisionHigh()) + " (n=" + b.highCovered() + ")",
            Md.pct(p.precisionHigh()) + " (n=" + p.highCovered() + ")", Md.pp(b.precisionHigh(), p.precisionHigh())));
        rows.add(List.of("Preciznost `medium`", Md.pct(b.precisionMedium()) + " (n=" + b.mediumCovered() + ")",
            Md.pct(p.precisionMedium()) + " (n=" + p.mediumCovered() + ")", Md.pp(b.precisionMedium(), p.precisionMedium())));
        rows.add(List.of("Preciznost `low`", Md.pct(b.precisionLow()) + " (n=" + b.lowCovered() + ")",
            Md.pct(p.precisionLow()) + " (n=" + p.lowCovered() + ")", Md.pp(b.precisionLow(), p.precisionLow())));
        rows.add(List.of("Udeo `ambiguous`", Md.pct(b.ambiguousShare()), Md.pct(p.ambiguousShare()), Md.pp(b.ambiguousShare(), p.ambiguousShare())));
        rows.add(List.of("Naredbe pogođene bar jednom", Md.frac(b.statementsHit(), b.statements()), Md.frac(p.statementsHit(), p.statements()),
            Md.delta(b.statementsHit(), p.statementsHit())));
        rows.add(List.of("Prosečna tačnost po naredbi (macro @1)", Md.pct(b.macroAccuracyAt1()), Md.pct(p.macroAccuracyAt1()),
            Md.pp(b.macroAccuracyAt1(), p.macroAccuracyAt1())));
        rows.add(List.of("Accuracy@1 bez dominantnih naredbi", Md.pct(b.controlAccuracyAt1()), Md.pct(p.controlAccuracyAt1()),
            Md.pp(b.controlAccuracyAt1(), p.controlAccuracyAt1())));
        rows.add(List.of("Lažna povezivanja", Md.pct(b.falseLinkShare()), Md.pct(p.falseLinkShare()), Md.pp(b.falseLinkShare(), p.falseLinkShare())));
        Md.table(out, List.of("Metrika", "Podrazumevane težine", "Podešene težine", "Razlika"), rows);

        String verdict = switch (v.verdict()) {
            case BETTER -> "Podešene težine imaju **više** tačnih događaja na prvom mestu (" + Md.delta(b.correctAt1(), p.correctAt1()) + ").";
            case EQUAL -> "Podešene težine imaju **isti** broj tačnih događaja na prvom mestu kao podrazumevane.";
            case WORSE -> "Podešene težine imaju **manje** tačnih događaja na prvom mestu (" + Md.delta(b.correctAt1(), p.correctAt1()) + ").";
        };
        out.append("**Ishod.** ").append(verdict).append(" Uslov preciznosti `high` ≥ ")
            .append(Md.number(v.minHighPrecision())).append(" je ").append(v.constraintMet() ? "ispunjen" : "**nije ispunjen**")
            .append(" (").append(Md.pct(p.precisionHigh())).append(").\n\n");
        if (v.identical()) {
            out.append("Predlog pretrage je **jednak** podrazumevanim težinama (nijedna kombinacija nije bila bolja na skupu za podešavanje), "
                + "pa su obe kolone isti rezultat. Ova validacija samo dokumentuje rezultat podrazumevanih težina na test skupu; "
                + "`config/matching.yml` ostaje nepromenjen (T34, korak 5).\n");
        } else {
            out.append(v.notWorse()
                ? "Podešene težine nisu lošije na test skupu, pa je zamena `config/matching.yml` dozvoljena (T34, korak 5).\n"
                : "Podešene težine su lošije na test skupu ili ne ispunjavaju uslov, pa `config/matching.yml` ostaje nepromenjen (T34, korak 5).\n");
        }
        return out.toString();
    }

    private static void method(StringBuilder out, Tuner.Result result) {
        Tuner.Options o = result.options();
        out.append("## 1. Cilj i metod\n\n");
        out.append("**Cilj:** najveći accuracy@1 po događaju (tačna naredba je prvi izbor, nad događajima čiji je ground truth naredba iz kataloga), "
            + "uz uslov da je preciznost nivoa pouzdanosti `high` najmanje ").append(Md.number(o.minHighPrecision())).append(".\n\n");
        out.append("**Šta se podešava:** 13 težina iz 0.10 koraka 3 i 5 pragova iz koraka 4 (18 brojeva, rasponi su u odeljku 3). "
            + "Ograničenja: `regex_prefix ≤ regex_full`, `medium + 0.05 ≤ high`; kazne ostaju ≤ 0, a bonusi ≥ 0. "
            + "Ne podešavaju se `top_k_tokens`, `max_candidates` (menjali bi generisanje kandidata) ni lista `oracle.unreliable-callers` (menjala bi ground truth).\n\n");
        out.append("**Pretraga** (najviše ").append(o.maxCombos()).append(" kombinacija, `seed` ").append(o.seed()).append("):\n\n");
        out.append("1. pokušaj 0 su podrazumevane težine iz `config/matching.yml`;\n");
        out.append("2. slučajna pretraga: ").append((o.maxCombos() - 1) / 2).append(" nasumičnih kombinacija, ravnomerno po rasponima;\n");
        out.append("3. lokalna grid pretraga oko najboljeg dotadašnjeg pokušaja: svaki parametar se pomera za jedan korak naniže i naviše, "
            + "pomak koji poboljšava rezultat se zadržava, a kad cela runda ne nađe ništa, korak se prepolovi (jednom).\n\n");
        out.append("**Poređenje pokušaja** (redom): (1) ispunjen uslov preciznosti `high`; (2) više tačnih događaja na prvom mestu; "
            + "(3) manje odstupanje od podrazumevanih težina; (4) više tačnih događaja sa nivoom `high`. "
            + "Tačke 3 i 4 samo razbijaju nerešeno: pri istom accuracy@1 pretraga ne pomera težine bez razloga.\n\n");
    }

    private static void outcome(StringBuilder out, Tuner.Result result, Tuner.Trial baseline, Tuner.Trial best) {
        Score b = baseline.score();
        Score s = best.score();
        out.append("## 2. Rezultat na skupu za podešavanje\n\n");
        List<List<String>> rows = new ArrayList<>();
        rows.add(List.of("**Accuracy@1**", "**" + Md.pct2(b.accuracyAt1()) + "** (" + Md.frac(b.correctAt1(), b.evaluable()) + ")",
            "**" + Md.pct2(s.accuracyAt1()) + "** (" + Md.frac(s.correctAt1(), s.evaluable()) + ")",
            Md.pp(b.accuracyAt1(), s.accuracyAt1()) + " (" + Md.delta(b.correctAt1(), s.correctAt1()) + " događaja)"));
        rows.add(List.of("Accuracy@3", Md.pct(b.accuracyAt3()), Md.pct(s.accuracyAt3()), Md.pp(b.accuracyAt3(), s.accuracyAt3())));
        rows.add(List.of("Pokrivenost", Md.pct(b.coverage()), Md.pct(s.coverage()), Md.pp(b.coverage(), s.coverage())));
        rows.add(List.of("Preciznost `high`", Md.pct(b.precisionHigh()) + " (n=" + b.highCovered() + ")",
            Md.pct(s.precisionHigh()) + " (n=" + s.highCovered() + ")", Md.pp(b.precisionHigh(), s.precisionHigh())));
        rows.add(List.of("Tačnih sa nivoom `high`", String.valueOf(b.highCorrect()), String.valueOf(s.highCorrect()), Md.delta(b.highCorrect(), s.highCorrect())));
        rows.add(List.of("Udeo `ambiguous`", Md.pct(b.ambiguousShare()), Md.pct(s.ambiguousShare()), Md.pp(b.ambiguousShare(), s.ambiguousShare())));
        rows.add(List.of("Naredbe pogođene bar jednom", Md.frac(b.statementsHit(), b.statements()), Md.frac(s.statementsHit(), s.statements()),
            Md.delta(b.statementsHit(), s.statementsHit())));
        rows.add(List.of("Prosečna tačnost po naredbi (macro @1)", Md.pct(b.macroAccuracyAt1()), Md.pct(s.macroAccuracyAt1()),
            Md.pp(b.macroAccuracyAt1(), s.macroAccuracyAt1())));
        rows.add(List.of("Accuracy@1 bez dominantnih naredbi", Md.pct(b.controlAccuracyAt1()), Md.pct(s.controlAccuracyAt1()),
            Md.pp(b.controlAccuracyAt1(), s.controlAccuracyAt1())));
        rows.add(List.of("Lažna povezivanja", Md.pct(b.falseLinkShare()), Md.pct(s.falseLinkShare()), Md.pp(b.falseLinkShare(), s.falseLinkShare())));
        Md.table(out, List.of("Metrika", "Podrazumevane težine", "Najbolje nađene", "Razlika"), rows);

        if (best == baseline) {
            out.append("Nijedna kombinacija nije bila bolja od podrazumevanih težina, pa je predlog jednak podrazumevanim.\n\n");
        } else {
            out.append("Najbolja kombinacija je pokušaj ").append(best.index()).append(" (faza: ").append(phase(best.phase())).append(").");
            if (result.baseline().feasible()) {
                out.append(" Podrazumevane težine već ispunjavaju uslov preciznosti `high`, pa pretraga traži samo bolji accuracy@1.");
            } else {
                out.append(" Podrazumevane težine ne ispunjavaju uslov preciznosti `high`.");
            }
            out.append("\n\n");
        }
    }

    private static void proposal(StringBuilder out, Tuner.Trial baseline, Tuner.Trial best) {
        out.append("## 3. Predložene vrednosti\n\n");
        out.append("Ceo predlog je u `docs/eval/tuning/matching.proposed.yml`. Podebljano je ono što se razlikuje od podrazumevanih vrednosti.\n\n");
        List<List<String>> rows = new ArrayList<>();
        for (int i = 0; i < ParameterSpace.size(); i++) {
            ParameterSpace.Parameter p = ParameterSpace.PARAMETERS.get(i);
            boolean changed = Math.abs(baseline.values()[i] - best.values()[i]) > 1e-9;
            String from = Md.number(baseline.values()[i]);
            String to = Md.number(best.values()[i]);
            rows.add(List.of(Md.code(p.name()), from, changed ? "**" + to + "**" : to,
                "[" + Md.number(p.min()) + ", " + Md.number(p.max()) + "]", Md.number(p.step())));
        }
        Md.table(out, List.of("Parametar", "Podrazumevano", "Predloženo", "Raspon pretrage", "Korak"), rows);
    }

    private static void topTrials(StringBuilder out, Tuner.Result result) {
        out.append("## 4. Najbolji pokušaji\n\n");
        List<Tuner.Trial> ranked = result.trials().stream()
            .sorted((a, b) -> Tuner.better(a, b) ? -1 : Tuner.better(b, a) ? 1 : 0)
            .limit(TOP_TRIALS).toList();
        List<List<String>> rows = new ArrayList<>();
        for (Tuner.Trial t : ranked) {
            Score s = t.score();
            rows.add(List.of(String.valueOf(t.index()), phase(t.phase()), t.feasible() ? "da" : "ne",
                Md.pct2(s.accuracyAt1()) + " (" + s.correctAt1() + ")", Md.pct(s.coverage()),
                Md.pct(s.precisionHigh()) + " (n=" + s.highCovered() + ")", Md.pct(s.controlAccuracyAt1()),
                String.format(Locale.ROOT, "%.3f", t.distance())));
        }
        Md.table(out, List.of("Pokušaj", "Faza", "Uslov ispunjen", "Accuracy@1 (tačnih)", "Pokrivenost", "Preciznost `high`",
            "Accuracy@1 bez dominantnih", "Odstupanje od podrazumevanih"), rows);
        out.append("Svi pokušaji sa svim parametrima su u `docs/eval/tuning/trials.csv`.\n\n");
    }

    private static void course(StringBuilder out, Tuner.Result result) {
        out.append("## 5. Tok pretrage\n\n");
        Score b = result.baseline().score();
        double bestRandom = result.trials().stream().filter(t -> t.phase() == Tuner.Phase.RANDOM && t.feasible())
            .mapToInt(t -> t.score().correctAt1()).max().orElse(-1);
        out.append("- Ukupno pokušaja: ").append(result.trials().size()).append(" od dozvoljenih ").append(result.options().maxCombos())
            .append(" (osnovni 1, slučajnih ").append(result.trials(Tuner.Phase.RANDOM)).append(", grid ")
            .append(result.trials(Tuner.Phase.GRID)).append(").\n");
        out.append("- Ispunjava uslov preciznosti `high`: ").append(result.feasibleTrials()).append(" pokušaja.\n");
        out.append("- Tačnih na prvom mestu: podrazumevane ").append(b.correctAt1()).append(", najbolja slučajna kombinacija ")
            .append(bestRandom < 0 ? "—" : String.valueOf((int) bestRandom)).append(", najbolja ukupno ")
            .append(result.best().score().correctAt1()).append(".\n");

        long same = result.trials().stream().filter(t -> t.score().correctAt1() == b.correctAt1()).count();
        long more = result.trials().stream().filter(t -> t.score().correctAt1() > b.correctAt1()).count();
        long fewer = result.trials().size() - same - more;
        out.append("- Prema broju tačnih na prvom mestu pokušaji se dele ovako: ").append(more).append(" sa više od podrazumevanih, ")
            .append(same).append(" sa istim brojem, ").append(fewer).append(" sa manjim.\n");

        int topAccuracy = result.trials().stream().filter(Tuner.Trial::feasible).mapToInt(t -> t.score().correctAt1()).max().orElse(-1);
        result.trials().stream()
            .filter(t -> t.feasible() && t.score().correctAt1() == topAccuracy)
            .max(java.util.Comparator.comparingInt((Tuner.Trial t) -> t.score().highCorrect()))
            .filter(t -> t.score().highCorrect() > b.highCorrect())
            .ifPresent(t -> out.append("- Među pokušajima sa najvećim accuracy@1 koji ispunjavaju uslov, najviše tačnih događaja sa nivoom `high` ima pokušaj ")
                .append(t.index()).append(": ").append(t.score().highCorrect()).append(" (preciznost ").append(Md.pct(t.score().precisionHigh()))
                .append(", n=").append(t.score().highCovered()).append(") naspram ").append(b.highCorrect()).append(" (")
                .append(Md.pct(b.precisionHigh())).append(", n=").append(b.highCovered()).append(") kod podrazumevanih težina. "
                    + "To je razlika u kalibraciji, ne u tačnosti, i nije kriterijum izbora u ovom zadatku.\n"));
        out.append("\n");
    }

    private static void notes(StringBuilder out, Tuner.Result result, List<String> dominantStatements) {
        Score b = result.baseline().score();
        out.append("## 6. Napomene i ograničenja\n\n");
        out.append("- **Plafon tačnosti:** podrazumevane težine greše na ").append(b.errors()).append(" od ").append(b.evaluable())
            .append(" događaja za tačnost. Od toga je ").append(b.tiedErrors()).append(" izjednačenih (tačna naredba ima isti skor kao izabrana, "
                + "pa odlučuje samo `statement_id`, što nijedna težina ne menja) i ").append(b.unmatchedErrors())
            .append(" bez predviđanja. Izvan dominantnih naredbi grešaka je ").append(b.controlErrors()).append(", od toga izjednačenih ")
            .append(b.controlTiedErrors()).append(". Takve greške može da ispravi samo novi signal, ne drugačije težine.\n");
        out.append("- **Dominantne naredbe:** ").append(dominantStatements.isEmpty() ? "nema" : String.join(" i ", dominantStatements))
            .append(" su dve najčešće tačne naredbe u skupu. Kad imaju isti šablon i nivo, težine ih ne mogu razlikovati, pa čine konstantan doprinos "
                + "accuracy@1 u svim pokušajima; zato je prikazan i accuracy@1 bez njih.\n");
        out.append("- **Šta ne utiče na accuracy@1:** `ambiguity_margin`, `ambiguous_penalty`, `high` i `medium` menjaju samo status i nivo pouzdanosti, "
            + "ne i izbor naredbe. Pretraga ih koristi samo da održi uslov preciznosti `high`.\n");
        out.append("- **Preprilagođavanje:** težine se biraju na jednom skupu (jedna sesija saobraćaja, jedan commit), pa je poređenje na test skupu "
            + "jedina nepristrasna procena.\n");
        out.append("- **Ponovni ingest:** `ingester ingest` upisuje rezultat sa težinama iz `config/matching.yml` u trenutku ingest-a. Ako se `config/matching.yml` "
            + "promeni, izveštaji `eval run` i događaji u `log2code-logs` se ne poklapaju sa novim težinama dok se dataset-i ponovo ne ingestuju.\n\n");
    }

    private static String phase(Tuner.Phase phase) {
        return switch (phase) {
            case BASELINE -> "osnovna";
            case RANDOM -> "slučajna";
            case GRID -> "grid";
        };
    }
}
