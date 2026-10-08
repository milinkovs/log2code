package org.log2code.eval.report;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.log2code.eval.EvalResult;
import org.log2code.eval.metrics.EvalMetrics;
import org.log2code.eval.metrics.EvalMetrics.BreakdownRow;
import org.log2code.eval.metrics.EvalMetrics.Counts;
import org.log2code.eval.metrics.EvalMetrics.GroupRow;
import org.log2code.eval.metrics.EvalMetrics.Headline;
import org.log2code.eval.metrics.EvalMetrics.UniqueStatements;
import org.log2code.eval.metrics.EventEvaluation;
import org.log2code.eval.metrics.StatementRow;

/** Renders {@code report.md}: tables with a short explanation of every metric, in Serbian (docs are Serbian, 0.14). */
final class MarkdownReport {

    private static final int TOP_STATEMENTS = 10;
    private static final int TOP_ERROR_TYPES = 10;
    private static final int MESSAGE_LIMIT = 90;
    private static final double DOMINANT_SHARE = 0.25;

    private static final Map<String, String> DIMENSION_TITLES = Map.of(
        "code_unit_type", "Po tipu code unit-a (projekat ili biblioteka)",
        "artifact", "Po artefaktu (15 najčešćih)",
        "logging_api", "Po `logging_api`",
        "template_kind", "Po `template_kind`",
        "level", "Po nivou loga",
        "service", "Po servisu");
    private static final List<String> DIMENSION_ORDER = List.of(
        "code_unit_type", "artifact", "logging_api", "template_kind", "level", "service");

    private MarkdownReport() {
    }

    static String render(EvalResult result, LocalDate date, String command) {
        EvalMetrics metrics = result.metrics();
        StringBuilder out = new StringBuilder();

        out.append("# Evaluacija povezivanja: `").append(result.datasetId()).append("`\n\n");
        out.append("- **Verzija koda:** `").append(metrics.codeName()).append("` @ `").append(metrics.codeVersion()).append("`\n");
        out.append("- **Generisano:** ").append(date).append(", komandom `").append(command).append("`\n");
        out.append("- **Ulaz:** događaji iz indeksa `log2code-logs` (upisani komandom `ingester ingest`) i katalog iz "
            + "`log2code-catalog` za istu verziju koda. Rezultat zavisi od težina u `config/matching.yml` u trenutku ingest-a.\n\n");

        groundTruth(out, metrics.counts());
        summary(out, metrics);
        calibration(out, metrics);
        statuses(out, metrics);
        breakdowns(out, metrics);
        uniqueStatements(out, metrics, result);
        errorExamples(out, result);
        definitions(out);
        notes(out, metrics);
        return out.toString();
    }

    private static void groundTruth(StringBuilder out, Counts c) {
        out.append("## 1. Ground truth\n\n");
        out.append("Za svaki događaj ground truth se uzima po prioritetu: (1) ručna oznaka, (2) oracle zapis ako je pouzdan, "
            + "(3) inače događaj nema ground truth. Takav događaj ne ulazi u tačnost, ali ulazi u pokrivenost. "
            + "Oracle klasa i linija se prevode u skup tačnih naredbi: to su naredbe koje matcher sme da izabere za servis "
            + "događaja (projekat i izabrane zavisnosti njegovog modula), čija je klasa jednaka oracle klasi i čiji "
            + "raspon poziva obuhvata oracle liniju.\n\n");
        List<List<String>> rows = new ArrayList<>();
        rows.add(List.of("Događaja ukupno", num(c.totalEvents())));
        rows.add(List.of("Ground truth iz ručne oznake", num(c.truthManual())));
        rows.add(List.of("Ground truth iz oracle-a (pouzdan)", num(c.truthOracle())));
        rows.add(List.of("Bez ground truth-a (nepouzdan oracle ili običan dataset)", num(c.truthNone())));
        rows.add(List.of("Ground truth van kataloga (`gt_not_in_catalog`)", num(c.truthNotInCatalog())));
        rows.add(List.of("**Događaja za tačnost** (ground truth je naredba iz kataloga)", "**" + num(c.evaluable()) + "**"));
        rows.add(List.of("od toga naredba ima nepodržan šablon (`unsupported`)", num(c.evaluableUnsupported())));
        table(out, List.of("Stavka", "Događaja"), rows);
    }

    private static void summary(StringBuilder out, EvalMetrics m) {
        Headline h = m.headline();
        Counts c = m.counts();
        UniqueStatements u = m.uniqueStatements();
        int covered = c.matched() + c.ambiguous();
        out.append("## 2. Sažetak\n\n");
        List<List<String>> rows = new ArrayList<>();
        rows.add(List.of("Pokrivenost", pct(h.coverage()), frac(covered, c.totalEvents()) + " događaja je `matched` ili `ambiguous`"));
        rows.add(List.of("Accuracy@1", pct(h.accuracyAt1()), "tačna naredba je prvi izbor; `unmatched` je promašaj"));
        rows.add(List.of("Accuracy@3", pct(h.accuracyAt3()), "tačna naredba je među prva 3 kandidata (i kod `unmatched`)"));
        rows.add(List.of("Accuracy@3, samo `matched`/`ambiguous`", pct(h.accuracyAt3Covered()), "isto, bez `unmatched` događaja"));
        rows.add(List.of("Preciznost nad pokrivenim", pct(h.precisionCovered()), "tačnih među `matched`/`ambiguous`"));
        if (c.evaluableUnsupported() > 0) {
            rows.add(List.of("Accuracy@1 bez `unsupported` naredbi", pct(h.accuracyAt1Supported()),
                "matcher nikad ne razmatra naredbe sa nepodržanim šablonom, pa ih ovde izostavljamo"));
        }
        rows.add(List.of("Udeo `ambiguous`", pct(h.ambiguousShare()), "svih događaja; među događajima za tačnost: " + pct(h.ambiguousShareEvaluable())));
        rows.add(List.of("Udeo `gt_not_in_catalog`", pct(h.notInCatalogShare()), "događaja sa ground truth-om čija naredba nije u katalogu (pokrivenost kataloga)"));
        rows.add(List.of("Lažna povezivanja kad naredbe nema u katalogu", pct(h.falseMatchOnNotInCatalog()),
            "`gt_not_in_catalog` događaja koji su ipak dobili predviđanje"));
        rows.add(List.of("Jedinstvene naredbe pogođene bar jednom", pct(u.hitRate()), u.hitAtLeastOnce() + " od " + u.statements() + " naredbi"));
        rows.add(List.of("Prosečna tačnost po naredbi (macro @1)", pct(u.macroAccuracyAt1()), "svaka naredba ima istu težinu bez obzira na učestalost"));
        table(out, List.of("Metrika", "Vrednost", "Napomena"), rows);
    }

    private static void calibration(StringBuilder out, EvalMetrics m) {
        out.append("## 3. Kalibracija: preciznost po nivou pouzdanosti\n\n");
        out.append("Za događaje sa predviđanjem (`matched` i `ambiguous`) računa se koliko je tačnih u svakom nivou pouzdanosti. "
            + "Ako je pouzdanost dobro kalibrisana, preciznost opada od `high` ka `low`. "
            + "Pouzdanost `ambiguous` događaja je pomnožena sa 0.6, pa oni uglavnom padaju u niže nivoe.\n\n");
        groupTable(out, "Nivo", m.byConfidenceLevel());
    }

    private static void statuses(StringBuilder out, EvalMetrics m) {
        out.append("## 4. Rezultat po statusu\n\n");
        out.append("Samo događaji za tačnost. Kod `unmatched` nema predviđanja, pa je accuracy@1 uvek 0, "
            + "a accuracy@3 pokazuje da li je tačna naredba bila među sačuvanim kandidatima.\n\n");
        groupTable(out, "Status", m.byStatus());
    }

    private static void groupTable(StringBuilder out, String keyHeader, List<GroupRow> groups) {
        List<List<String>> rows = new ArrayList<>();
        for (GroupRow g : groups) {
            rows.add(List.of(g.key(), num(g.events()), num(g.correctAt1()), pct(g.accuracyAt1()), num(g.correctAt3()), pct(g.accuracyAt3())));
        }
        table(out, List.of(keyHeader, "Događaja", "Tačnih @1", "Accuracy@1", "Tačnih @3", "Accuracy@3"), rows);
    }

    private static void breakdowns(StringBuilder out, EvalMetrics m) {
        out.append("## 5. Razlaganje metrika\n\n");
        out.append("Samo događaji za tačnost. Događaj pripada grupi naredbe koju je trebalo pronaći (ground truth), "
            + "osim za nivo loga i servis, gde se koristi polje samog događaja. Pokrivenost je udeo `matched` i `ambiguous` događaja. "
            + "\"Naredbi\" je broj jedinstvenih tačnih naredbi u grupi, a \"pogođeno\" koliko je njih bar jednom tačno prepoznato.\n\n");
        int index = 1;
        for (String dimension : DIMENSION_ORDER) {
            List<BreakdownRow> rows = m.breakdowns().get(dimension);
            if (rows == null) {
                continue;
            }
            out.append("### 5.").append(index++).append(" ").append(DIMENSION_TITLES.get(dimension)).append("\n\n");
            List<List<String>> body = new ArrayList<>();
            for (BreakdownRow r : rows) {
                body.add(List.of(r.key(), num(r.events()), pct(r.coverage()), pct(r.accuracyAt1()), pct(r.accuracyAt3()),
                    num(r.uniqueStatements()), num(r.uniqueStatementsHit())));
            }
            table(out, List.of("Grupa", "Događaja", "Pokrivenost", "Accuracy@1", "Accuracy@3", "Naredbi", "Pogođeno"), body);
        }
    }

    private static void uniqueStatements(StringBuilder out, EvalMetrics m, EvalResult result) {
        UniqueStatements u = m.uniqueStatements();
        out.append("## 6. Tačnost po jedinstvenoj naredbi\n\n");
        out.append("Česte poruke mogu da dominiraju tačnošću po događaju, pa se računa i tačnost po naredbi: ")
            .append("svaka naredba iz ground truth-a se broji jednom. Obe mere su u tabeli sažetka. ")
            .append("Ovde je ").append(num(u.statements())).append(" jedinstvenih naredbi, od kojih je ")
            .append(num(u.hitAtLeastOnce())).append(" (").append(pct(u.hitRate())).append(") bar jednom pogođeno. ")
            .append("Najčešća naredba čini ").append(pct(u.topStatementShare())).append(" događaja za tačnost.\n\n");
        if (u.topStatementShare() >= DOMINANT_SHARE) {
            out.append("> **Pažnja:** jedna naredba čini veliki deo dataset-a, pa su metrike po događaju (odeljci 2–5) pod njenim uticajem. "
                + "Tačnost po naredbi pokazuje stanje bez te dominacije.\n\n");
        }
        out.append("Najčešće naredbe (`per_statement.csv` ima sve):\n\n");
        List<List<String>> rows = new ArrayList<>();
        for (StatementRow s : result.statements().stream().limit(TOP_STATEMENTS).toList()) {
            String top = s.topPredictionId() == null ? "bez predviđanja" : ReportWriter.describe(result.catalog(), s.topPredictionId());
            rows.add(List.of(
                escape(ReportWriter.shortClass(s.info().classFqn()) + "#" + s.info().methodName() + ":" + s.info().line()),
                escape(ReportWriter.oneLine(s.info().template(), MESSAGE_LIMIT)),
                num(s.events()), pct(s.accuracyAt1()), pct(s.accuracyAt3()), escape(top) + " (" + s.topPredictionCount() + ")"));
        }
        table(out, List.of("Naredba", "Šablon", "Događaja", "Accuracy@1", "Accuracy@3", "Najčešće predviđanje"), rows);
    }

    private static void errorExamples(StringBuilder out, EvalResult result) {
        out.append("## 7. Greške\n\n");
        if (result.errorTypes().isEmpty()) {
            out.append("Nema grešaka među događajima za tačnost.\n\n");
            return;
        }
        int wrongEvents = result.errorTypes().stream().mapToInt(ErrorSample::events).sum();
        out.append("Greška je događaj za tačnost čije prvo predviđanje nije tačno ili ne postoji. Ukupno je ")
            .append(wrongEvents).append(" takvih događaja, a pripadaju ").append(result.errorTypes().size())
            .append(" različitih tipova grešaka. Tip greške je par (predviđena naredba, tačna naredba). ")
            .append("Jedan tip može da se ponovi hiljadu puta, pa se primeri biraju po tipu, a ne po događaju. ")
            .append("\"Događaja\" je broj događaja sa tim tipom greške, a \"Rang\" mesto tačne naredbe među kandidatima. ")
            .append("Poruka, `score_breakdown` i kandidati su u `errors.csv`.\n\n");

        out.append("### 7.1 Najčešći tipovi grešaka\n\n");
        errorTable(out, result, result.errorTypes().stream().limit(TOP_ERROR_TYPES).toList());

        out.append("### 7.2 Nasumičan uzorak tipova grešaka\n\n");
        out.append(result.errorSamples().size()).append(" tipova izabrano nasumično (fiksan `seed`, ponovljivo), jedan primer po tipu.\n\n");
        errorTable(out, result, result.errorSamples());
    }

    private static void errorTable(StringBuilder out, EvalResult result, List<ErrorSample> samples) {
        List<List<String>> rows = new ArrayList<>();
        for (ErrorSample sample : samples) {
            EventEvaluation e = sample.event();
            String predicted = e.covered() && e.predictedStatementId() != null
                ? ReportWriter.describe(result.catalog(), e.predictedStatementId()) : "bez predviđanja";
            String truth = e.truthStatementIds().stream().map(id -> ReportWriter.describe(result.catalog(), id)).reduce((a, b) -> a + ", " + b).orElse("?");
            rows.add(List.of(String.valueOf(sample.events()), e.service() == null ? "?" : e.service(), e.level() == null ? "?" : e.level(),
                escape(ReportWriter.oneLine(e.message(), MESSAGE_LIMIT)), escape(predicted), escape(truth),
                e.status() + (e.confidenceLevel() == null ? "" : " / " + e.confidenceLevel()),
                e.truthRank() == 0 ? "—" : String.valueOf(e.truthRank())));
        }
        table(out, List.of("Događaja", "Servis", "Nivo", "Poruka", "Predviđanje", "Tačna naredba", "Status / pouzdanost", "Rang"), rows);
    }

    private static void definitions(StringBuilder out) {
        out.append("## 8. Definicije metrika\n\n");
        out.append("- **Pokrivenost:** udeo svih događaja sa statusom `matched` ili `ambiguous`.\n");
        out.append("- **Accuracy@1:** udeo događaja za tačnost čija je prvo izabrana naredba jedna od tačnih. "
            + "Za `ambiguous` je to najbolji kandidat. `unmatched` nema predviđanje i računa se kao promašaj.\n");
        out.append("- **Accuracy@3:** udeo događaja za tačnost kod kojih je tačna naredba među prva 3 sačuvana kandidata. "
            + "Kandidati se čuvaju i za `unmatched`, pa se oni ovde računaju; zato je dodata i varijanta samo za `matched`/`ambiguous`.\n");
        out.append("- **Preciznost po nivou pouzdanosti:** accuracy@1 unutar svakog nivoa (`high` ≥ 0.75, `medium` ≥ 0.50, inače `low`) "
            + "nad `matched` i `ambiguous` događajima.\n");
        out.append("- **`gt_not_in_catalog`:** ground truth je poznat, ali u katalogu nema naredbe u toj klasi na toj liniji "
            + "(za servis događaja). To meri pokrivenost kataloga, ne grešku matcher-a, pa se ne broji u tačnost.\n");
        out.append("- **`unsupported`:** naredbe čiji se šablon ne može izvući (npr. Tomcat `StringManager`). "
            + "Postoje u katalogu i mogu biti tačan odgovor, ali ih matcher po specifikaciji ne razmatra, pa uvek ostaju promašaj.\n");
        out.append("- **Tačnost po jedinstvenoj naredbi:** događaji se grupišu po tačnoj naredbi. "
            + "\"Pogođena bar jednom\" znači da je za bar jedan njen događaj prvi izbor bio tačan. "
            + "Prosečna tačnost po naredbi je prosek tačnosti @1 po naredbama.\n\n");
    }

    private static void notes(StringBuilder out, EvalMetrics m) {
        out.append("## 9. Napomene i ograničenja\n\n");
        Counts c = m.counts();
        if (c.truthManual() == 0) {
            out.append("- Nema ručnih oznaka (`log2code-labels` je prazan za ovaj dataset), pa je ground truth samo oracle.\n");
        }
        if (c.truthOracle() + c.truthManual() == 0) {
            out.append("- Dataset nema ground truth (nije snimljen u oracle režimu), pa su tačnost i razlaganja prazna; "
                + "izveštaj pokazuje samo pokrivenost i raspodelu statusa.\n");
        }
        out.append("- Oracle zapisi označeni kao nepouzdani (log pozivi kroz omotače, vidi `oracle.unreliable-callers`) nemaju ground truth.\n");
        out.append("- Naredba na pogrešnoj liniji istog poziva (poziv preko više linija) se poklapa ako oracle linija upada u `[line, end_line]`.\n");
        out.append("- Brojke zavise od podataka onako kako ih je ingest upisao. Posle izmene težina ili kataloga treba ponoviti "
            + "`ingester ingest --recreate-dataset`, pa `eval run`.\n");
    }

    private static void table(StringBuilder out, List<String> header, List<List<String>> rows) {
        out.append("| ").append(String.join(" | ", header)).append(" |\n");
        out.append("|").append("---|".repeat(header.size())).append("\n");
        for (List<String> row : rows) {
            out.append("| ").append(String.join(" | ", row)).append(" |\n");
        }
        out.append("\n");
    }

    private static String pct(Double value) {
        return value == null ? "—" : String.format(Locale.ROOT, "%.1f%%", value * 100);
    }

    private static String num(int value) {
        return String.valueOf(value);
    }

    private static String frac(int numerator, int denominator) {
        return numerator + " / " + denominator;
    }

    /** Keeps free text inside one Markdown table cell. */
    private static String escape(String text) {
        return text == null ? "" : text.replace("|", "\\|").replace("`", "'");
    }
}
