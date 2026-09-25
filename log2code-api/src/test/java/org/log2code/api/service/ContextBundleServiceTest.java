package org.log2code.api.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.log2code.api.dto.ContextBundleDto;
import org.log2code.api.dto.LogSummary;
import org.log2code.api.dto.NeighborsResponse;
import org.log2code.api.dto.TraceResponse;
import org.log2code.core.model.CallerRef;
import org.log2code.core.model.CatalogEntry;
import org.log2code.core.model.CausedBy;
import org.log2code.core.model.CodeUnit;
import org.log2code.core.model.CodeVersion;
import org.log2code.core.model.Condition;
import org.log2code.core.model.ControlContext;
import org.log2code.core.model.EnclosingBlock;
import org.log2code.core.model.EnrichedLog;
import org.log2code.core.model.ExceptionInfo;
import org.log2code.core.model.Level;
import org.log2code.core.model.MatchResult;
import org.log2code.core.model.MethodInfo;
import org.log2code.core.model.SourceFile;
import org.log2code.core.model.StackFrame;
import org.log2code.core.opensearch.DocumentReader;
import org.log2code.core.opensearch.IndexNames;

/**
 * Pure-logic coverage for {@link ContextBundleService}: the snippet/method-source extraction
 * helpers, and assembly decisions (unmatched log, callers capped at {@link ContextBundleService#MAX_CALLERS},
 * exception snippets only for project frames) with {@link DocumentReader}/{@link LogNeighborhoodService}
 * mocked. End-to-end behavior against a real cluster (AC1: a real exception, real project frame,
 * real snippet) is covered by {@code ContextApiIT}.
 */
class ContextBundleServiceTest {

    private static final IndexNames INDEX_NAMES = new IndexNames();
    private static final CodeUnit CODE_UNIT = new CodeUnit(CodeUnit.TYPE_PROJECT, "petclinic", "v1");
    private static final String METHOD_CONTENT = String.join("\n",
        "package a;", "class Foo {", "  void bar() {", "    log.info(\"x\");", "  }", "}");

    // ---- extractRange ----

    @Test
    void extractRangeReturnsExactLineSlice() {
        assertThat(ContextBundleService.extractRange(METHOD_CONTENT, 3, 5))
            .isEqualTo("  void bar() {\n    log.info(\"x\");\n  }");
    }

    @Test
    void extractRangeClampsToFileBounds() {
        assertThat(ContextBundleService.extractRange(METHOD_CONTENT, 4, 100))
            .isEqualTo("    log.info(\"x\");\n  }\n}");
    }

    @Test
    void extractRangeReturnsNullForNullContentOrInvalidBounds() {
        assertThat(ContextBundleService.extractRange(null, 1, 3)).isNull();
        assertThat(ContextBundleService.extractRange(METHOD_CONTENT, 0, 3)).isNull();
        assertThat(ContextBundleService.extractRange(METHOD_CONTENT, 5, 3)).isNull();
    }

    // ---- extractSnippet ----

    @Test
    void extractSnippetReturnsRadiusAroundLine() {
        assertThat(ContextBundleService.extractSnippet(METHOD_CONTENT, 4, 1))
            .isEqualTo("  void bar() {\n    log.info(\"x\");\n  }");
    }

    @Test
    void extractSnippetClampsAtFileStartAndEnd() {
        assertThat(ContextBundleService.extractSnippet(METHOD_CONTENT, 1, 3))
            .isEqualTo("package a;\nclass Foo {\n  void bar() {\n    log.info(\"x\");");
        assertThat(ContextBundleService.extractSnippet(METHOD_CONTENT, 6, 3))
            .isEqualTo("  void bar() {\n    log.info(\"x\");\n  }\n}");
    }

    @Test
    void extractSnippetReturnsNullWhenLineBeyondFileOrContentMissing() {
        assertThat(ContextBundleService.extractSnippet(METHOD_CONTENT, 999, 3)).isNull();
        assertThat(ContextBundleService.extractSnippet(null, 1, 3)).isNull();
    }

    // ---- build() assembly ----

    @Test
    void buildLeavesStatementCodeControlAndCallersEmptyWhenUnmatched() {
        DocumentReader documentReader = mock(DocumentReader.class);
        LogNeighborhoodService neighborhoodService = mock(LogNeighborhoodService.class);
        ContextBundleService service = new ContextBundleService(documentReader, INDEX_NAMES, neighborhoodService);
        EnrichedLog log = sampleLog(unmatched(), null);
        stubNeighborsAndTrace(neighborhoodService, log);

        ContextBundleDto bundle = service.build(log, 10);

        assertThat(bundle.schemaVersion()).isEqualTo(1);
        assertThat(bundle.match().status()).isEqualTo("unmatched");
        assertThat(bundle.match().candidates()).isEmpty();
        assertThat(bundle.statement()).isNull();
        assertThat(bundle.code()).isNull();
        assertThat(bundle.control()).isNull();
        assertThat(bundle.callers()).isEmpty();
        assertThat(bundle.exception()).isNull();
    }

    @Test
    void buildFillsCodeSectionFromCatalogAndSourceWhenMatched() throws Exception {
        DocumentReader documentReader = mock(DocumentReader.class);
        LogNeighborhoodService neighborhoodService = mock(LogNeighborhoodService.class);
        ContextBundleService service = new ContextBundleService(documentReader, INDEX_NAMES, neighborhoodService);

        CatalogEntry entry = sampleEntry("stmt-1", null, "file-1");
        when(documentReader.get(eq(INDEX_NAMES.catalog()), eq("stmt-1"), eq(CatalogEntry.class))).thenReturn(entry);
        when(documentReader.get(eq(INDEX_NAMES.sources()), eq("file-1"), eq(SourceFile.class)))
            .thenReturn(new SourceFile("file-1", CODE_UNIT, "petclinic", "Foo.java", METHOD_CONTENT, 6, "sha"));

        MatchResult match = matched("stmt-1");
        EnrichedLog log = sampleLog(match, null);
        stubNeighborsAndTrace(neighborhoodService, log);

        ContextBundleDto bundle = service.build(log, 10);

        assertThat(bundle.match().status()).isEqualTo("matched");
        assertThat(bundle.match().candidates()).extracting(c -> c.statementId()).containsExactly("stmt-1");
        assertThat(bundle.statement().statementId()).isEqualTo("stmt-1");
        assertThat(bundle.code().methodSource()).isEqualTo("  void bar() {\n    log.info(\"x\");\n  }");
        assertThat(bundle.control().conditions()).hasSize(1);
        assertThat(bundle.callers()).isEmpty(); // no methodId on this entry
    }

    @Test
    void buildCapsCallersAtTen() throws Exception {
        DocumentReader documentReader = mock(DocumentReader.class);
        LogNeighborhoodService neighborhoodService = mock(LogNeighborhoodService.class);
        ContextBundleService service = new ContextBundleService(documentReader, INDEX_NAMES, neighborhoodService);

        CatalogEntry entry = sampleEntry("stmt-1", "method-target", "file-1");
        when(documentReader.get(eq(INDEX_NAMES.catalog()), eq("stmt-1"), eq(CatalogEntry.class))).thenReturn(entry);
        when(documentReader.get(eq(INDEX_NAMES.sources()), eq("file-1"), eq(SourceFile.class)))
            .thenReturn(new SourceFile("file-1", CODE_UNIT, "petclinic", "Foo.java", METHOD_CONTENT, 6, "sha"));

        List<CallerRef> callers = java.util.stream.IntStream.range(0, 12)
            .mapToObj(i -> new CallerRef("caller-" + i, "Caller" + i, "call", "file-caller-" + i, 10))
            .toList();
        MethodInfo target = new MethodInfo("method-target", CODE_UNIT, "petclinic", "customers-service", "file-1",
            "Foo.java", "a.Foo", "Foo", "bar", "bar()", 3, 5, List.of(), true, List.of(), callers, 12);
        when(documentReader.get(eq(INDEX_NAMES.methods()), eq("method-target"), eq(MethodInfo.class))).thenReturn(target);
        for (CallerRef ref : callers) {
            when(documentReader.get(eq(INDEX_NAMES.methods()), eq(ref.methodId()), eq(MethodInfo.class))).thenReturn(null);
        }

        EnrichedLog log = sampleLog(matched("stmt-1"), null);
        stubNeighborsAndTrace(neighborhoodService, log);

        ContextBundleDto bundle = service.build(log, 10);

        assertThat(bundle.callers()).hasSize(10);
    }

    @Test
    void buildAddsSnippetOnlyForProjectExceptionFrames() throws Exception {
        DocumentReader documentReader = mock(DocumentReader.class);
        LogNeighborhoodService neighborhoodService = mock(LogNeighborhoodService.class);
        ContextBundleService service = new ContextBundleService(documentReader, INDEX_NAMES, neighborhoodService);

        when(documentReader.get(eq(INDEX_NAMES.sources()), eq("file-1"), eq(SourceFile.class)))
            .thenReturn(new SourceFile("file-1", CODE_UNIT, "petclinic", "Foo.java", METHOD_CONTENT, 6, "sha"));

        StackFrame projectFrame = new StackFrame("a.Foo", "bar", "Foo.java", 4, true, "petclinic:v1", "file-1", "https://github.com/x");
        StackFrame libraryFrame = new StackFrame("org.springframework.X", "y", "X.java", 10, false, "spring:1", null, null);
        ExceptionInfo exception = new ExceptionInfo("java.lang.RuntimeException", "java.lang.RuntimeException",
            "boom", List.of(projectFrame, libraryFrame), List.of(new CausedBy("java.lang.IllegalStateException", "cause", List.of(projectFrame))));

        EnrichedLog log = sampleLog(unmatched(), exception);
        stubNeighborsAndTrace(neighborhoodService, log);

        ContextBundleDto bundle = service.build(log, 10);

        assertThat(bundle.exception().frames()).hasSize(2);
        assertThat(bundle.exception().frames().get(0).snippet()).isEqualTo(METHOD_CONTENT);
        assertThat(bundle.exception().frames().get(1).snippet()).isNull();
        assertThat(bundle.exception().causedBy()).hasSize(1);
        assertThat(bundle.exception().causedBy().get(0).frames().get(0).snippet()).isNotNull();
    }

    private static void stubNeighborsAndTrace(LogNeighborhoodService neighborhoodService, EnrichedLog log) {
        when(neighborhoodService.neighbors(log, 10, 10, null))
            .thenReturn(new NeighborsResponse(List.of(), LogMapper.toSummary(log), List.of()));
        when(neighborhoodService.trace(log, ContextBundleService.MAX_TRACE)).thenReturn(new TraceResponse(List.of(), TraceResponse.REASON_NO_TRACE_ID));
    }

    private static EnrichedLog sampleLog(MatchResult match, ExceptionInfo exception) {
        return new EnrichedLog("log-1", Instant.parse("2026-09-25T10:00:00Z"), "2026-09-25T10:00:00Z", "smoke-01",
            "logs/customers-service.log", 42, 1, 0, "customers-service", "spring-petclinic-customers-service",
            "app", "1", "thread-1", Level.INFO, "a.Foo", "a.Foo", "boom", "raw text", null, null, exception,
            new CodeVersion("petclinic", "v1"), match, null, "spring-boot-default", "1.0.0", Instant.parse("2026-09-25T10:00:01Z"));
    }

    private static MatchResult matched(String statementId) {
        return new MatchResult(MatchResult.STATUS_MATCHED, statementId, 0.9, MatchResult.CONFIDENCE_HIGH,
            List.of(new org.log2code.core.model.Candidate(statementId, 0.9)), Map.of(), List.of(),
            null, null, null, null, null, null, null, null, null, null);
    }

    private static MatchResult unmatched() {
        return new MatchResult(MatchResult.STATUS_UNMATCHED, null, null, null, List.of(), Map.of(), List.of(),
            null, null, null, null, null, null, null, null, null, null);
    }

    private static CatalogEntry sampleEntry(String statementId, String methodId, String fileId) {
        ControlContext control = new ControlContext(List.of(new Condition("if", "x > 0", 3, false)), List.of(), List.of(), List.of());
        return new CatalogEntry(
            statementId, "logical-" + statementId, CODE_UNIT, "spring-petclinic-customers-service", "customers-service",
            "Foo.java", fileId, "a", "a.Foo", "Foo", "bar", "bar()", methodId, false, 4, 4, 4, 3, 5,
            "slf4j", "typed", "log", "Foo", "class_literal", Level.INFO, false, "\"x\"", "x", "literal", null,
            "^x$", List.of("x"), 1, 0, false, new EnclosingBlock("method", null, null, 3, 5), control,
            "log.info(\"x\");", 4, "https://github.com/example/petclinic/blob/v1/Foo.java#L4", "0.1.0-SNAPSHOT",
            Instant.parse("2026-09-25T09:00:00Z"));
    }
}
