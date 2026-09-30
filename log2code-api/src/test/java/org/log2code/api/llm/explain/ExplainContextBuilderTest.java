package org.log2code.api.llm.explain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.log2code.api.llm.explain.ExplainFixtures.INDEX;
import static org.log2code.api.llm.explain.ExplainFixtures.LIBRARY;
import static org.log2code.api.llm.explain.ExplainFixtures.PROJECT;
import static org.mockito.Mockito.mock;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.log2code.api.llm.explain.ExplainContext.CallerBlock;
import org.log2code.api.llm.explain.ExplainContext.CodeBlock;
import org.log2code.api.llm.explain.ExplainFixtures.Store;
import org.log2code.api.service.LogNeighborhoodService;
import org.log2code.core.model.CallerRef;
import org.log2code.core.model.Condition;
import org.log2code.core.model.EnrichedLog;
import org.log2code.core.model.ExceptionInfo;
import org.log2code.core.model.Level;
import org.log2code.core.model.MatchResult;
import org.log2code.core.model.MethodInfo;
import org.log2code.core.model.StackFrame;

class ExplainContextBuilderTest {

    private static ExplainContext build(Store store, EnrichedLog log, ExplainLevel level) throws Exception {
        return new ExplainContextBuilder(store.reader(), INDEX, ExplainFixtures.neighbors(log)).build(log, level);
    }

    private static ExplainSection section(ExplainContext context, String id) {
        return context.sections().stream().filter(s -> s.id().equals(id)).findFirst().orElseThrow();
    }

    // ---- what each level reads ----

    @Test
    void l0HasOnlyTheLogAndItsStackTraceAndNothingElseIsRead() throws Exception {
        Store store = ExplainFixtures.petLifeStageStore();
        EnrichedLog log = ExplainFixtures.stageLog();
        LogNeighborhoodService neighborhood = mock(LogNeighborhoodService.class);
        var reader = store.reader();

        ExplainContext context = new ExplainContextBuilder(reader, INDEX, neighborhood).build(log, ExplainLevel.L0);

        assertThat(context.log().service()).isEqualTo("customers-service");
        assertThat(context.log().logger()).isEqualTo("org.springframework.samples.petclinic.customers.application.PetLifeStage");
        assertThat(context.stackTrace()).startsWith("java.lang.ArrayIndexOutOfBoundsException: Index 5");
        assertThat(context.statement()).isNull();
        assertThat(context.method()).isNull();
        assertThat(context.flow()).isNull();
        assertThat(context.stackCode()).isEmpty();
        assertThat(context.callers()).isEmpty();
        assertThat(context.neighbors()).isEmpty();
        assertThat(context.sections()).extracting(ExplainSection::id).containsExactlyElementsOf(ExplainSection.ALL_IDS);
        assertThat(context.sections()).filteredOn(ExplainSection::included).extracting(ExplainSection::id)
            .containsExactly("log", "exception");
        assertThat(section(context, "statement")).isEqualTo(ExplainSection.omitted("statement", "level"));
        org.mockito.Mockito.verifyNoInteractions(neighborhood);
        org.mockito.Mockito.verify(reader, org.mockito.Mockito.never()).get(org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.<Class<Object>>any());
    }

    @Test
    void l1AddsTheStatementAndTheWholeMethod() throws Exception {
        ExplainContext context = build(ExplainFixtures.petLifeStageStore(), ExplainFixtures.stageLog(), ExplainLevel.L1);

        assertThat(context.statement().status()).isEqualTo("matched");
        assertThat(context.statement().codeUnit()).isEqualTo("projekat spring-petclinic-microservices @ da47840");
        assertThat(context.statement().classAndMethod())
            .isEqualTo("org.springframework.samples.petclinic.customers.application.PetLifeStage#resolve(LocalDate,int)");
        assertThat(context.method().firstLine()).isEqualTo(40);
        assertThat(context.method().lines()).hasSize(11);
        assertThat(String.join("\n", context.method().lines())).contains("log.error(").doesNotContain(ExplainFixtures.STAGE_LINE);
        assertThat(context.flow()).isNull();
        assertThat(section(context, "flow").reason()).isEqualTo("level");
    }

    @Test
    void l2AddsFlowAndProjectStackFrameCode() throws Exception {
        ExplainContext context = build(ExplainFixtures.petLifeStageStore(), ExplainFixtures.stageLog(), ExplainLevel.L2);

        assertThat(context.flow().inside()).containsExactly("try (linija 44)", "catch (ArrayIndexOutOfBoundsException) (linija 46)");
        assertThat(context.flow().earlyExits()).containsExactly("birthDate == null (linija 41, return)");
        assertThat(context.flow().preceding()).containsExactly("stageFor(years) (linija 45)");
        assertThat(context.flow().callsBefore()).containsExactly("stageFor(years) (linija 45)");
        assertThat(context.stackCode()).extracting(ExplainContext.StackCode::title).containsExactly(
            "PetLifeStage.stageFor — PetLifeStage.java:53", "PetLifeStage.resolve — PetLifeStage.java:45", "PetProfile.describe — PetProfile.java:31");
        ExplainContext.StackCode stage = context.stackCode().get(0);
        assertThat(stage.code().firstLine()).isEqualTo(50);
        assertThat(stage.code().lines()).hasSize(7);
        assertThat(stage.code().lines()).contains(ExplainFixtures.STAGE_LINE);
        assertThat(context.callers()).isEmpty();
        assertThat(section(context, "callers").reason()).isEqualTo("level");
    }

    @Test
    void l3AddsDirectCallersOnly() throws Exception {
        ExplainContext context = build(ExplainFixtures.petLifeStageStore(), ExplainFixtures.stageLog(), ExplainLevel.L3);

        assertThat(context.callers()).hasSize(1);
        CallerBlock caller = context.callers().get(0);
        assertThat(caller.depth()).isEqualTo(1);
        assertThat(caller.title()).isEqualTo(
            "org.springframework.samples.petclinic.customers.PetProfile#describe(Pet) — customers/PetProfile.java, poziv u liniji 31");
        assertThat(caller.code().firstLine()).isEqualTo(25);
        assertThat(context.neighbors()).isEmpty();
    }

    @Test
    void l4AddsThreeCallerLevelsWithRestEntryAndTheNeighbors() throws Exception {
        ExplainContext context = build(ExplainFixtures.petLifeStageStore(), ExplainFixtures.stageLog(), ExplainLevel.L4);

        assertThat(context.callers()).extracting(CallerBlock::depth).containsExactly(1, 2, 3, 3);
        assertThat(context.callers().get(1).title()).contains("PetService#savePet(Pet)").doesNotContain("REST ulaz");
        assertThat(context.callers().get(2).title()).contains("PetResource#processCreationForm()", "poziv u liniji 64", "REST ulaz (@PostMapping)");
        assertThat(context.callers().get(3).title()).contains("PetResource#processUpdateForm()", "poziv u liniji 72", "REST ulaz (@PutMapping)");
        assertThat(context.neighbors()).extracting(ExplainContext.NeighborLine::text).containsExactly(
            "10:00:00.103 INFO o.s.s.p.c.a.X — before two",
            "10:00:00.113 INFO o.s.s.p.c.a.X — before one",
            "10:00:00.123 ERROR o.s.s.p.c.a.PetLifeStage — Failed to resolve life stage for pet Oldie (born 2001-05-01, 25 years old)",
            "10:00:00.133 ERROR o.s.s.p.c.a.X — after one second line",
            "10:00:00.143 WARN o.s.s.p.c.a.X — after two");
        assertThat(context.neighbors()).extracting(ExplainContext.NeighborLine::current).containsExactly(false, false, true, false, false);
        assertThat(context.sections()).allMatch(ExplainSection::included);
    }

    // ---- parts that do not exist ----

    @Test
    void unmatchedLogHasNoStatementMethodFlowOrCallers() throws Exception {
        EnrichedLog log = ExplainFixtures.log("log-un", Level.ERROR, "Something", ExplainFixtures.raw("Something", null, 0), null,
            ExplainFixtures.match(MatchResult.STATUS_UNMATCHED, null, 0.1, "low"));

        ExplainContext context = build(new Store(), log, ExplainLevel.L4);

        assertThat(context.statement()).isNull();
        assertThat(context.method()).isNull();
        assertThat(context.flow()).isNull();
        assertThat(context.callers()).isEmpty();
        assertThat(section(context, "statement").reason()).isEqualTo("unmatched");
        assertThat(section(context, "method").reason()).isEqualTo("unmatched");
        assertThat(section(context, "flow").reason()).isEqualTo("unmatched");
        assertThat(section(context, "callers").reason()).isEqualTo("unmatched");
        assertThat(section(context, "exception").reason()).isEqualTo("noException");
        assertThat(section(context, "stackCode").reason()).isEqualTo("noException");
        assertThat(section(context, "neighbors").included()).isTrue();
    }

    @Test
    void statementMissingFromTheCatalogIsUnavailable() throws Exception {
        EnrichedLog log = ExplainFixtures.stageLog();

        ExplainContext context = build(new Store(), log, ExplainLevel.L3);

        assertThat(context.statement()).isNull();
        assertThat(section(context, "statement").reason()).isEqualTo("unavailable");
        assertThat(section(context, "callers").reason()).isEqualTo("unavailable");
    }

    @Test
    void libraryStatementHasNoCallersButKeepsTheNeighbors() throws Exception {
        Store store = ExplainFixtures.petLifeStageStore();
        store.put(INDEX.catalog(), "stmt-stage", ExplainFixtures.stageEntry(LIBRARY, ExplainFixtures.stageControl()));

        ExplainContext context = build(store, ExplainFixtures.stageLog(), ExplainLevel.L4);

        assertThat(context.statement().codeUnit()).isEqualTo("biblioteka org.springframework:spring-web:6.2.0");
        assertThat(context.callers()).isEmpty();
        assertThat(section(context, "callers")).isEqualTo(ExplainSection.omitted("callers", "library"));
        assertThat(context.neighbors()).hasSize(5);
        assertThat(section(context, "neighbors").included()).isTrue();
    }

    @Test
    void projectStatementWithoutCallersIsMarkedNoCallers() throws Exception {
        Store store = ExplainFixtures.petLifeStageStore();
        store.put(INDEX.methods(), "m-resolve", ExplainFixtures.method("m-resolve", "PetLifeStage.java", "application.PetLifeStage",
            "resolve(LocalDate,int)", 40, 50, List.of(), List.of()));

        ExplainContext context = build(store, ExplainFixtures.stageLog(), ExplainLevel.L3);

        assertThat(section(context, "callers")).isEqualTo(ExplainSection.omitted("callers", "noCallers"));
    }

    @Test
    void logWithoutExceptionHasNoStackTrace() throws Exception {
        EnrichedLog log = ExplainFixtures.log("log-info", Level.INFO, "Saving pet", ExplainFixtures.raw("Saving pet", null, 0), null,
            ExplainFixtures.match(MatchResult.STATUS_MATCHED, "stmt-stage", 0.88, "high"));

        ExplainContext context = build(ExplainFixtures.petLifeStageStore(), log, ExplainLevel.L2);

        assertThat(context.stackTrace()).isNull();
        assertThat(context.stackCode()).isEmpty();
        assertThat(section(context, "exception")).isEqualTo(ExplainSection.omitted("exception", "noException"));
        assertThat(section(context, "stackCode")).isEqualTo(ExplainSection.omitted("stackCode", "noException"));
    }

    @Test
    void exceptionWithoutProjectFramesHasNoStackCode() throws Exception {
        ExceptionInfo exception = new ExceptionInfo("java.lang.IllegalStateException", "java.lang.IllegalStateException", "x",
            List.of(ExplainFixtures.libraryFrame("Foo", "bar", 3)), List.of());
        EnrichedLog log = ExplainFixtures.log("log-lib", Level.ERROR, "x", ExplainFixtures.raw("x", "java.lang.IllegalStateException: x", 1),
            exception, ExplainFixtures.match(MatchResult.STATUS_UNMATCHED, null, 0.1, "low"));

        ExplainContext context = build(new Store(), log, ExplainLevel.L2);

        assertThat(section(context, "stackCode")).isEqualTo(ExplainSection.omitted("stackCode", "noProjectFrames"));
        assertThat(section(context, "exception").included()).isTrue();
    }

    @Test
    void controlWithoutAnyItemIsMarkedNoControl() throws Exception {
        Store store = ExplainFixtures.petLifeStageStore();
        store.put(INDEX.catalog(), "stmt-stage", ExplainFixtures.stageEntry(PROJECT,
            new org.log2code.core.model.ControlContext(List.of(), List.of(), List.of(), List.of())));

        ExplainContext context = build(store, ExplainFixtures.stageLog(), ExplainLevel.L2);

        assertThat(context.flow()).isNull();
        assertThat(section(context, "flow")).isEqualTo(ExplainSection.omitted("flow", "noControl"));
    }

    // ---- flow text ----

    @Test
    void conditionsAreWrittenPerKind() {
        assertThat(ExplainContextBuilder.conditionText(new Condition("if", "a > 1", 3, false))).isEqualTo("if (a > 1)");
        assertThat(ExplainContextBuilder.conditionText(new Condition("else", "a > 1", 5, true))).isEqualTo("NE: a > 1");
        assertThat(ExplainContextBuilder.conditionText(new Condition("catch", "IOException", 5, false))).isEqualTo("catch (IOException)");
        assertThat(ExplainContextBuilder.conditionText(new Condition("loop", "Pet other : owner.getPets()", 5, false)))
            .isEqualTo("loop (Pet other : owner.getPets())");
        assertThat(ExplainContextBuilder.conditionText(new Condition("finally", "", 5, false))).isEqualTo("finally");
        assertThat(ExplainContextBuilder.conditionText(new Condition("switch_case", "\"a\"", 5, false))).isEqualTo("case \"a\"");
    }

    // ---- stack trace text ----

    @Test
    void stackTraceOfMoreThanSixtyLinesIsCutWithARemark() {
        EnrichedLog log = ExplainFixtures.log("l", Level.ERROR, "m", ExplainFixtures.raw("m", "java.lang.IllegalStateException: m", 100),
            ExplainFixtures.stageException(), null);

        List<String> lines = ExplainContextBuilder.stackTrace(log).lines().toList();

        assertThat(lines).hasSize(61);
        assertThat(lines.get(0)).isEqualTo("java.lang.IllegalStateException: m");
        assertThat(lines.get(59)).isEqualTo("\tat some.pkg.Frame58.call(Frame.java:59)");
        assertThat(lines.get(60)).isEqualTo("… (još 41 linija)");
    }

    @Test
    void stackTraceStartsAtTheExceptionLineEvenWhenTheMessageHasSeveralLines() {
        String raw = "2026-09-29T10:00:00Z ERROR x : first line\nsecond message line\njava.lang.ArrayIndexOutOfBoundsException: Index 5\n\tat a.B.c(B.java:1)";
        EnrichedLog log = ExplainFixtures.log("l", Level.ERROR, "first line\nsecond message line", raw, ExplainFixtures.stageException(), null);

        assertThat(ExplainContextBuilder.stackTrace(log))
            .isEqualTo("java.lang.ArrayIndexOutOfBoundsException: Index 5\n\tat a.B.c(B.java:1)");
    }

    @Test
    void stackTraceFallsBackToEverythingAfterTheFirstLine() {
        String raw = "2026-09-29T10:00:00Z ERROR x : m\nCustom failure\n\tat a.B.c(B.java:1)\n";
        EnrichedLog log = ExplainFixtures.log("l", Level.ERROR, "m", raw, ExplainFixtures.stageException(), null);

        assertThat(ExplainContextBuilder.stackTrace(log)).isEqualTo("Custom failure\n\tat a.B.c(B.java:1)");
    }

    // ---- stack frame code ----

    @Test
    void stackCodeShowsEachFileAndLineOnceAndAtMostTenFrames() throws Exception {
        Store store = ExplainFixtures.petLifeStageStore();
        List<StackFrame> frames = new ArrayList<>();
        frames.add(ExplainFixtures.projectFrame("PetLifeStage", "stageFor", "f-PetLifeStage.java", 20));
        frames.add(ExplainFixtures.projectFrame("PetLifeStage", "stageFor", "f-PetLifeStage.java", 20));
        for (int i = 21; i < 40; i++) {
            frames.add(ExplainFixtures.projectFrame("PetLifeStage", "m" + i, "f-PetLifeStage.java", i));
        }
        ExceptionInfo exception = new ExceptionInfo("java.lang.IllegalStateException", "java.lang.IllegalStateException", "x", frames, List.of());
        EnrichedLog log = ExplainFixtures.log("l", Level.ERROR, "x", ExplainFixtures.raw("x", "java.lang.IllegalStateException: x", 1),
            exception, ExplainFixtures.match(MatchResult.STATUS_UNMATCHED, null, 0.1, "low"));

        ExplainContext context = build(store, log, ExplainLevel.L2);

        assertThat(context.stackCode()).hasSize(10);
        assertThat(context.stackCode().get(0).title()).isEqualTo("PetLifeStage.stageFor — PetLifeStage.java:20");
        assertThat(context.stackCode().get(1).title()).isEqualTo("PetLifeStage.m21 — PetLifeStage.java:21");
    }

    @Test
    void stackCodeIncludesProjectFramesOfCausedByAndClampsToTheFileStart() throws Exception {
        ExceptionInfo exception = new ExceptionInfo("java.lang.RuntimeException", "java.lang.IllegalStateException", "x",
            List.of(ExplainFixtures.libraryFrame("Foo", "bar", 3)),
            List.of(new org.log2code.core.model.CausedBy("java.lang.IllegalStateException", "c",
                List.of(ExplainFixtures.projectFrame("PetLifeStage", "top", "f-PetLifeStage.java", 2)))));
        EnrichedLog log = ExplainFixtures.log("l", Level.ERROR, "x", ExplainFixtures.raw("x", "java.lang.RuntimeException: x", 1),
            exception, ExplainFixtures.match(MatchResult.STATUS_UNMATCHED, null, 0.1, "low"));

        ExplainContext context = build(ExplainFixtures.petLifeStageStore(), log, ExplainLevel.L2);

        assertThat(context.stackCode()).hasSize(1);
        CodeBlock code = context.stackCode().get(0).code();
        assertThat(code.firstLine()).isEqualTo(1);
        assertThat(code.lines()).hasSize(5);
    }

    // ---- method window ----

    @Test
    void methodOfMoreThanOneHundredFiftyLinesIsShownAsAWindowAroundTheLog() {
        String content = ExplainFixtures.source("Big", 400, Map.of());

        CodeBlock window = ExplainContextBuilder.window(content, 10, 300, 100, 150, 60);

        assertThat(window.firstLine()).isEqualTo(40);
        assertThat(window.lines()).hasSize(121);
        assertThat(window.cutBefore()).isTrue();
        assertThat(window.cutAfter()).isTrue();
        assertThat(window.lines().get(0)).isEqualTo("// Big line 40");
        assertThat(window.lines().get(120)).isEqualTo("// Big line 160");
    }

    @Test
    void methodOfUpToOneHundredFiftyLinesIsShownWhole() {
        String content = ExplainFixtures.source("Big", 400, Map.of());

        CodeBlock whole = ExplainContextBuilder.window(content, 11, 160, 100, 150, 60);

        assertThat(whole.firstLine()).isEqualTo(11);
        assertThat(whole.lines()).hasSize(150);
        assertThat(whole.cutBefore()).isFalse();
        assertThat(whole.cutAfter()).isFalse();
    }

    @Test
    void windowStaysInsideTheMethodNearItsEdges() {
        String content = ExplainFixtures.source("Big", 400, Map.of());

        CodeBlock window = ExplainContextBuilder.window(content, 10, 300, 15, 150, 60);

        assertThat(window.firstLine()).isEqualTo(10);
        assertThat(window.cutBefore()).isFalse();
        assertThat(window.cutAfter()).isTrue();
        assertThat(window.lines()).hasSize(66);
    }

    // ---- the clock of the neighbor times ----

    private static final java.time.Instant NOON_UTC = java.time.Instant.parse("2026-09-30T12:10:05.123Z");

    @Test
    void neighborTimesUseTheOffsetOfTheLogsOwnTimestampRaw() {
        assertThat(ExplainContextBuilder.clockOffset("2026-09-30T14:10:05.123+02:00", NOON_UTC)).isEqualTo(java.time.ZoneOffset.ofHours(2));
        assertThat(ExplainContextBuilder.clockOffset("2026-09-30T12:10:05.123Z", NOON_UTC)).isEqualTo(java.time.ZoneOffset.UTC);
    }

    @Test
    void aRawTimeWithoutAZoneIsReadAsTheLocalClockOfTheLog() {
        assertThat(ExplainContextBuilder.clockOffset("2026-09-30 14:10:05.123", NOON_UTC)).isEqualTo(java.time.ZoneOffset.ofHours(2));
        assertThat(ExplainContextBuilder.clockOffset("2026-09-30 14:10:05,123", NOON_UTC)).isEqualTo(java.time.ZoneOffset.ofHours(2));
        assertThat(ExplainContextBuilder.clockOffset("2026-09-30T07:10:05.123", NOON_UTC)).isEqualTo(java.time.ZoneOffset.ofHours(-5));
    }

    @Test
    void whenTheClockCannotBeDeterminedNeighborTimesAreUtc() {
        assertThat(ExplainContextBuilder.clockOffset(null, NOON_UTC)).isEqualTo(java.time.ZoneOffset.UTC);
        assertThat(ExplainContextBuilder.clockOffset("not a time", NOON_UTC)).isEqualTo(java.time.ZoneOffset.UTC);
        assertThat(ExplainContextBuilder.clockOffset("2026-09-30 14:10:05.123", null)).isEqualTo(java.time.ZoneOffset.UTC);
        assertThat(ExplainContextBuilder.clockOffset("2026-09-30 23:59:59.000", NOON_UTC)).isEqualTo(java.time.ZoneOffset.UTC); // +11h49m59s: not a whole minute
    }

    @Test
    void theMarkedNeighborLineShowsTheSameClockTimeAsTheHeader() throws Exception {
        EnrichedLog utc = ExplainFixtures.stageLog();
        EnrichedLog local = new EnrichedLog(utc.logId(), utc.timestamp(), "2026-09-29 12:00:00.123", utc.datasetId(), utc.sourceFile(),
            utc.lineNumber(), utc.lineCount(), utc.sequence(), utc.service(), utc.module(), utc.appName(), utc.pid(), utc.thread(),
            utc.level(), utc.loggerRaw(), utc.logger(), utc.message(), utc.raw(), utc.traceId(), utc.spanId(), utc.exception(),
            utc.code(), utc.match(), null, utc.parserFormat(), utc.ingesterVersion(), utc.ingestedAt());

        ExplainContext context = build(ExplainFixtures.petLifeStageStore(), local, ExplainLevel.L4);

        // the fixture's instant is 10:00:00.123Z and the raw text says 12:00:00.123, so the clock is UTC+2
        assertThat(context.neighbors()).extracting(ExplainContext.NeighborLine::text)
            .anyMatch(t -> t.startsWith("12:00:00.123 ERROR")).anyMatch(t -> t.startsWith("12:00:00.103 INFO"));
    }

    // ---- callers (breadth-first search) ----

    @Test
    void callerSearchGoesThreeLevelsWithoutDuplicatesAndKeepsFiveAndTwelveLimits() throws Exception {
        Store store = ExplainFixtures.petLifeStageStore();
        store.put(INDEX.methods(), "m-resolve", method("m-resolve", "Target", "resolve", callers("a", 7, 0)));
        // level 1 is a1..a7: only the first five (by class name) are kept
        for (int i = 1; i <= 7; i++) {
            List<CallerRef> up = new ArrayList<>();
            if (i == 1) {
                up.addAll(callers("b", 6, 0));          // b1..b6, only b1..b5 are kept
                up.add(ref("m-resolve", "Target", "resolve", 1)); // the statement's own method: a cycle, skipped
            }
            if (i == 2) {
                up.add(ref("m-a1", "a1", "call", 1));   // already on level 1: skipped
                up.add(ref("m-b1", "b1", "call", 1));   // also reached from a1: listed once
            }
            store.put(INDEX.methods(), "m-a" + i, method("m-a" + i, "a" + i, "call", up));
        }
        for (int i = 1; i <= 6; i++) {
            store.put(INDEX.methods(), "m-b" + i, method("m-b" + i, "b" + i, "call", i == 1 ? callers("c", 4, 0) : List.of()));
        }
        for (int i = 1; i <= 4; i++) {
            store.put(INDEX.methods(), "m-c" + i, method("m-c" + i, "c" + i, "call", List.of(ref("m-a1", "a1", "call", 1))));
        }
        EnrichedLog log = ExplainFixtures.stageLog();

        ExplainContext context = build(store, log, ExplainLevel.L4);

        List<CallerBlock> callers = context.callers();
        assertThat(callers).extracting(CallerBlock::depth).containsExactly(1, 1, 1, 1, 1, 2, 2, 2, 2, 2, 3, 3);
        assertThat(callers.stream().filter(c -> c.depth() == 1).map(c -> c.title().substring(0, c.title().indexOf('#'))))
            .extracting(t -> t.substring(t.lastIndexOf('.') + 1)).containsExactly("a1", "a2", "a3", "a4", "a5");
        assertThat(callers.stream().filter(c -> c.depth() == 3).map(c -> c.title().substring(0, c.title().indexOf('#'))))
            .extracting(t -> t.substring(t.lastIndexOf('.') + 1)).containsExactly("c1", "c2");
        assertThat(callers).extracting(CallerBlock::title).doesNotHaveDuplicates();
    }

    @Test
    void l3ShowsOnlyTheFirstCallerLevel() throws Exception {
        Store store = ExplainFixtures.petLifeStageStore();

        ExplainContext context = build(store, ExplainFixtures.stageLog(), ExplainLevel.L3);

        assertThat(context.callers()).extracting(CallerBlock::depth).containsOnly(1);
    }

    @Test
    void callerMethodOfMoreThanEightyLinesIsShownAsAWindowAroundTheCallLine() throws Exception {
        Store store = ExplainFixtures.petLifeStageStore();
        store.put(INDEX.sources(), "f-PetProfile.java", ExplainFixtures.sourceFile("f-PetProfile.java", "customers/PetProfile.java",
            ExplainFixtures.source("PetProfile", 400, Map.of())));
        store.put(INDEX.methods(), "m-describe", ExplainFixtures.method("m-describe", "PetProfile.java", "PetProfile", "describe(Pet)", 100, 250,
            List.of(), List.of(ExplainFixtures.ref("m-save", "PetService", "savePet", 50))));
        store.put(INDEX.methods(), "m-resolve", ExplainFixtures.method("m-resolve", "PetLifeStage.java", "application.PetLifeStage",
            "resolve(LocalDate,int)", 40, 50, List.of(), List.of(ExplainFixtures.ref("m-describe", "PetProfile", "describe", 200))));

        ExplainContext context = build(store, ExplainFixtures.stageLog(), ExplainLevel.L3);

        CodeBlock code = context.callers().get(0).code();
        assertThat(code.firstLine()).isEqualTo(160);
        assertThat(code.lines()).hasSize(81);
        assertThat(code.cutBefore()).isTrue();
        assertThat(code.cutAfter()).isTrue();
    }

    // ---- helpers for the caller search ----

    private static MethodInfo method(String id, String cls, String name, List<CallerRef> calledBy) {
        return new MethodInfo(id, PROJECT, "m", "customers-service", null, cls + ".java", "x." + cls, cls, name, name + "()", 1, 5,
            List.of(), false, List.of(), calledBy, calledBy.size());
    }

    private static CallerRef ref(String methodId, String cls, String name, int line) {
        return new CallerRef(methodId, "x." + cls, name, null, line);
    }

    /** {@code count} callers named {@code prefix1..prefixN}, method ids {@code m-prefixN}. */
    private static List<CallerRef> callers(String prefix, int count, int line) {
        List<CallerRef> refs = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            refs.add(ref("m-" + prefix + i, prefix + i, "call", line));
        }
        return refs;
    }
}
