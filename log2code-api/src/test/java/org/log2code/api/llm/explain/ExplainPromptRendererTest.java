package org.log2code.api.llm.explain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.log2code.api.llm.explain.ExplainFixtures.INDEX;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.log2code.api.llm.explain.ExplainContext.CallerBlock;
import org.log2code.api.llm.explain.ExplainContext.CodeBlock;
import org.log2code.api.llm.explain.ExplainContext.LogPart;
import org.log2code.api.llm.explain.ExplainContext.NeighborLine;
import org.log2code.core.model.EnrichedLog;
import org.log2code.core.model.Level;
import org.log2code.core.model.MatchResult;

class ExplainPromptRendererTest {

    private static final String SYSTEM = "system prompt";
    private static final ExplainPromptRenderer RENDERER = new ExplainPromptRenderer();

    private static ExplainPrompt prompt(EnrichedLog log, ExplainLevel level) throws Exception {
        ExplainContext context = new ExplainContextBuilder(ExplainFixtures.petLifeStageStore().reader(), INDEX, ExplainFixtures.neighbors(log))
            .build(log, level);
        return RENDERER.render(context, SYSTEM);
    }

    private static List<String> headings(String text) {
        return text.lines().filter(l -> l.startsWith("#")).toList();
    }

    // ---- what each level contains ----

    @Test
    void everyLevelHasExactlyTheExpectedHeadingsInOrder() throws Exception {
        EnrichedLog log = ExplainFixtures.stageLog();
        List<String> l0 = List.of("# Log zapis", "## Izuzetak");
        List<String> l1 = List.of("# Mesto u kodu koje je napisalo ovaj log", "## Metoda u kojoj je log");
        List<String> l2 = List.of("# Uslovi i tok do loga", "# Kod iz stack trace-a",
            "### PetLifeStage.stageFor — PetLifeStage.java:53", "### PetLifeStage.resolve — PetLifeStage.java:45", "### PetProfile.describe — PetProfile.java:31");

        assertThat(headings(prompt(log, ExplainLevel.L0).userPrompt())).isEqualTo(l0);
        assertThat(headings(prompt(log, ExplainLevel.L1).userPrompt())).isEqualTo(concat(l0, l1));
        assertThat(headings(prompt(log, ExplainLevel.L2).userPrompt())).isEqualTo(concat(l0, l1, l2));

        List<String> l3 = headings(prompt(log, ExplainLevel.L3).userPrompt());
        assertThat(l3).startsWith(concat(l0, l1, l2).toArray(String[]::new));
        assertThat(l3.subList(l0.size() + l1.size() + l2.size(), l3.size())).hasSize(2);
        assertThat(l3).contains("# Pozivaoci").noneMatch(h -> h.startsWith("### Nivo 2")).doesNotContain("# Susedni logovi istog servisa");

        List<String> l4 = headings(prompt(log, ExplainLevel.L4).userPrompt());
        assertThat(l4).contains("# Pozivaoci", "# Susedni logovi istog servisa").anyMatch(h -> h.startsWith("### Nivo 3: "));
        assertThat(l4.get(l4.size() - 1)).isEqualTo("# Susedni logovi istog servisa");
    }

    @Test
    void theWholePromptHasTheDocumentedFormat() throws Exception {
        String text = prompt(ExplainFixtures.stageLog(), ExplainLevel.L2).userPrompt();

        assertThat(text).startsWith("""
            # Log zapis
            - Servis: customers-service
            - Vreme: 2026-09-29T10:00:00.123Z
            - Nivo: ERROR
            - Logger: org.springframework.samples.petclinic.customers.application.PetLifeStage
            - Nit: nio-8081-exec-1
            - Trace ID: trace-1
            - Poruka: Failed to resolve life stage for pet Oldie (born 2001-05-01, 25 years old)

            ## Izuzetak
            ```text
            java.lang.ArrayIndexOutOfBoundsException: Index 5 out of bounds for length 4
            \tat some.pkg.Frame0.call(Frame.java:1)
            """);
        assertThat(text).contains("""
            # Mesto u kodu koje je napisalo ovaj log
            - Povezivanje: matched, pouzdanost high (0.95)
            - Kod: projekat spring-petclinic-microservices @ da47840
            - Fajl: customers/application/PetLifeStage.java, linija 47
            - Klasa i metoda: org.springframework.samples.petclinic.customers.application.PetLifeStage#resolve(LocalDate,int)
            - Šablon poruke: Failed to resolve life stage for pet {} (born {}, {} years old)

            ## Metoda u kojoj je log
            ```java
              40 |     static String resolve(LocalDate birthDate, int years) {
              41 |         if (birthDate == null) {
            """);
        assertThat(text).contains("""
            # Uslovi i tok do loga
            - Unutar: try (linija 44)
            - Unutar: catch (ArrayIndexOutOfBoundsException) (linija 46)
            - Stiže se samo ako NIJE: birthDate == null (linija 41, return)
            - Prethodne naredbe (od najbliže):
              - stageFor(years) (linija 45)
            - Pozivi pre loga:
              - stageFor(years) (linija 45)
            """);
        assertThat(text).contains("""
            # Kod iz stack trace-a

            ### PetLifeStage.stageFor — PetLifeStage.java:53
            ```java
              50 |     }
            """);
        assertThat(text).endsWith("\n\nObjasni ovaj log zapis prema uputstvu.");
    }

    @Test
    void theMethodIsInL1ButItsCalleeLineOnlyComesWithTheStackTraceInL2() throws Exception {
        EnrichedLog log = ExplainFixtures.stageLog();

        assertThat(prompt(log, ExplainLevel.L1).userPrompt()).contains("resolve(LocalDate birthDate").doesNotContain(ExplainFixtures.STAGE_LINE.strip());
        assertThat(prompt(log, ExplainLevel.L2).userPrompt()).contains("  53 | " + ExplainFixtures.STAGE_LINE);
    }

    @Test
    void l3HasThePetProfileCallerAndL4TheRestEntryAndTheMarkedLog() throws Exception {
        EnrichedLog log = ExplainFixtures.stageLog();

        assertThat(prompt(log, ExplainLevel.L3).userPrompt()).contains("PetProfile#describe(Pet)")
            .doesNotContain("REST ulaz");
        String l4 = prompt(log, ExplainLevel.L4).userPrompt();
        assertThat(l4).contains("### Nivo 3: org.springframework.samples.petclinic.customers.PetResource#processCreationForm() — customers/PetResource.java, "
            + "poziv u liniji 64, REST ulaz (@PostMapping)");
        assertThat(l4).contains("""
            # Susedni logovi istog servisa
            ```text
            10:00:00.103 INFO o.s.s.p.c.a.X — before two
            10:00:00.113 INFO o.s.s.p.c.a.X — before one
            ▶ 10:00:00.123 ERROR o.s.s.p.c.a.PetLifeStage — Failed to resolve life stage for pet Oldie (born 2001-05-01, 25 years old)
            10:00:00.133 ERROR o.s.s.p.c.a.X — after one second line
            10:00:00.143 WARN o.s.s.p.c.a.X — after two
            ```
            """);
    }

    @Test
    void promptCarriesVersionLevelSystemPromptAndLength() throws Exception {
        ExplainPrompt prompt = prompt(ExplainFixtures.stageLog(), ExplainLevel.L3);

        assertThat(prompt.promptVersion()).isEqualTo(1);
        assertThat(prompt.level()).isEqualTo(ExplainLevel.L3);
        assertThat(prompt.systemPrompt()).isEqualTo(SYSTEM);
        assertThat(prompt.promptChars()).isEqualTo(prompt.userPrompt().length());
        assertThat(prompt.sections()).hasSize(8);
    }

    // ---- omitted parts ----

    @Test
    void unmatchedLogOnL2HasOnlyTheLogSectionAndDoesNotMentionWhatIsMissing() throws Exception {
        EnrichedLog log = ExplainFixtures.log("log-un", Level.WARN, "Something happened", ExplainFixtures.raw("Something happened", null, 0), null,
            ExplainFixtures.match(MatchResult.STATUS_UNMATCHED, null, 0.1, "low"));

        ExplainPrompt prompt = prompt(log, ExplainLevel.L2);

        assertThat(headings(prompt.userPrompt())).containsExactly("# Log zapis");
        assertThat(prompt.userPrompt()).doesNotContainIgnoringCase("unmatched").doesNotContain("noException").doesNotContain("nije dat")
            .doesNotContain("nedostaje").doesNotContain("Izuzetak");
        assertThat(prompt.userPrompt()).endsWith("- Poruka: Something happened\n\nObjasni ovaj log zapis prema uputstvu.");
        assertThat(prompt.sections()).filteredOn(s -> s.reason() != null && s.reason().equals("unmatched")).extracting(ExplainSection::id)
            .containsExactly("statement", "method", "flow");
    }

    @Test
    void logWithoutExceptionHasNoExceptionSectionAndAnOptionalTraceIdIsLeftOut() throws Exception {
        EnrichedLog withoutTrace = new EnrichedLog("l", null, "2026-09-29T10:00:00Z", "demo-02", "f", 1, 1, 1, "svc", "mod", null, null, null,
            Level.INFO, "a.B", null, "Saving pet", "raw", null, null, null, null, null, null, null, null, null);

        ExplainPrompt prompt = prompt(withoutTrace, ExplainLevel.L0);

        assertThat(prompt.userPrompt()).isEqualTo("""
            # Log zapis
            - Servis: svc
            - Vreme: 2026-09-29T10:00:00Z
            - Nivo: INFO
            - Logger: a.B
            - Poruka: Saving pet

            Objasni ovaj log zapis prema uputstvu.""");
        assertThat(prompt.sections().get(1)).isEqualTo(ExplainSection.omitted("exception", "noException"));
    }

    // ---- code windows ----

    @Test
    void aCutMethodShowsTheMarkerOnEachCutSide() {
        CodeBlock block = new CodeBlock(40, List.of("a", "b"), true, true);

        assertThat(ExplainPromptRenderer.numbered(block)).isEqualTo("// … (skraćeno)\n  40 | a\n  41 | b\n// … (skraćeno)");
        assertThat(ExplainPromptRenderer.numbered(new CodeBlock(1, List.of("a"), false, true))).isEqualTo("   1 | a\n// … (skraćeno)");
    }

    // ---- 60 000 characters ----

    private static ExplainContext bigContext(List<CallerBlock> callers, List<NeighborLine> neighbors) {
        List<ExplainSection> sections = ExplainSection.ALL_IDS.stream().map(ExplainSection::included).toList();
        return new ExplainContext(ExplainLevel.L4, new LogPart("svc", "t", "ERROR", "a.B", "th", null, "m"), null, null,
            null, null, List.of(), callers, neighbors, sections);
    }

    private static CodeBlock lines(int count, int width) {
        return new CodeBlock(1, java.util.Collections.nCopies(count, "x".repeat(width)), false, false);
    }

    private static List<CallerBlock> callers(int levels, int linesEach) {
        List<CallerBlock> callers = new ArrayList<>();
        for (int level = 1; level <= levels; level++) {
            callers.add(new CallerBlock(level, "a.C" + level + "#m() — C.java, poziv u liniji 1", lines(linesEach, 100)));
        }
        return callers;
    }

    private static List<NeighborLine> neighbors() {
        List<NeighborLine> lines = new ArrayList<>();
        for (int i = 0; i < 11; i++) {
            lines.add(new NeighborLine("n".repeat(300), i == 5));
        }
        return lines;
    }

    @Test
    void aPromptOverTheLimitDropsTheNeighborsFirst() {
        // 3 callers x 190 lines x ~107 chars ~ 61 000 chars; the neighbors (~3 400) tip a prompt that would otherwise fit
        ExplainContext context = bigContext(callers(3, 180), neighbors());
        int withoutNeighbors = ExplainPromptRenderer.userPrompt(context.withoutNeighbors()).length();
        int withNeighbors = ExplainPromptRenderer.userPrompt(context).length();
        assertThat(withoutNeighbors).isLessThanOrEqualTo(ExplainPromptRenderer.MAX_USER_PROMPT_CHARS);
        assertThat(withNeighbors).isGreaterThan(ExplainPromptRenderer.MAX_USER_PROMPT_CHARS);

        ExplainPrompt prompt = RENDERER.render(context, SYSTEM);

        assertThat(prompt.userPrompt()).doesNotContain("# Susedni logovi").contains("### Nivo 3: ");
        assertThat(prompt.promptChars()).isEqualTo(withoutNeighbors);
        assertThat(prompt.sections()).contains(ExplainSection.omitted("neighbors", "truncated"))
            .contains(ExplainSection.included("callers"));
    }

    @Test
    void thenTheDeepestCallerLevelAndThenTheNextOne() {
        ExplainContext three = bigContext(callers(3, 250), neighbors());
        ExplainPrompt dropsOne = RENDERER.render(three, SYSTEM);
        assertThat(dropsOne.userPrompt()).doesNotContain("# Susedni logovi").doesNotContain("### Nivo 3: ").contains("### Nivo 2: ");
        assertThat(dropsOne.promptChars()).isLessThanOrEqualTo(ExplainPromptRenderer.MAX_USER_PROMPT_CHARS);
        assertThat(dropsOne.sections()).contains(ExplainSection.omitted("neighbors", "truncated"))
            .contains(new ExplainSection("callers", true, "truncated"));

        ExplainContext heavy = bigContext(callers(3, 450), List.of());
        ExplainPrompt dropsTwo = RENDERER.render(heavy, SYSTEM);
        assertThat(dropsTwo.userPrompt()).doesNotContain("### Nivo 3: ").doesNotContain("### Nivo 2: ").contains("### Nivo 1: ");
        assertThat(dropsTwo.promptChars()).isLessThanOrEqualTo(ExplainPromptRenderer.MAX_USER_PROMPT_CHARS);
        assertThat(dropsTwo.sections()).contains(new ExplainSection("callers", true, "truncated"));
    }

    @Test
    void whenNoCallerLevelIsLeftTheSectionIsOmittedAsTruncated() {
        ExplainContext context = bigContext(callers(1, 700), neighbors());

        ExplainPrompt prompt = RENDERER.render(context, SYSTEM);

        assertThat(prompt.userPrompt()).doesNotContain("# Pozivaoci").doesNotContain("# Susedni logovi");
        assertThat(prompt.sections()).contains(ExplainSection.omitted("callers", "truncated"), ExplainSection.omitted("neighbors", "truncated"));
    }

    @Test
    void aPromptWithinTheLimitIsLeftAlone() throws Exception {
        ExplainPrompt prompt = prompt(ExplainFixtures.stageLog(), ExplainLevel.L4);

        assertThat(prompt.promptChars()).isLessThan(ExplainPromptRenderer.MAX_USER_PROMPT_CHARS);
        assertThat(prompt.sections()).allMatch(s -> s.included() && s.reason() == null);
    }

    @SafeVarargs
    private static List<String> concat(List<String>... parts) {
        List<String> all = new ArrayList<>();
        for (List<String> part : parts) {
            all.addAll(part);
        }
        return all;
    }
}
