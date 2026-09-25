package org.log2code.api.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.log2code.api.dto.CandidateDetailDto;
import org.log2code.api.dto.ContextBundleDto;
import org.log2code.api.dto.ContextMatchDto;
import org.log2code.api.dto.ContextNeighborsDto;
import org.log2code.api.dto.LogSearchResponse;
import org.log2code.api.dto.LogSummary;
import org.log2code.api.dto.NeighborsResponse;
import org.log2code.api.dto.TraceResponse;
import org.log2code.api.service.CandidateService;
import org.log2code.api.service.ContextBundleService;
import org.log2code.api.service.LogMapper;
import org.log2code.api.service.LogNeighborhoodService;
import org.log2code.api.service.LogSearchParams;
import org.log2code.api.service.LogSearchService;
import org.log2code.core.model.CodeVersion;
import org.log2code.core.model.EnrichedLog;
import org.log2code.core.model.Level;
import org.log2code.core.model.MatchResult;
import org.log2code.core.opensearch.DocumentReader;
import org.log2code.core.opensearch.IndexNames;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** {@code @WebMvcTest} for {@link LogsController}: collaborators are mocked (T23 "Testovi", T24 step 1). */
@WebMvcTest(LogsController.class)
class LogsControllerTest {

    private static final String LOGS_INDEX = "log2code-logs";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private LogSearchService searchService;
    @MockitoBean
    private CandidateService candidateService;
    @MockitoBean
    private LogNeighborhoodService neighborhoodService;
    @MockitoBean
    private ContextBundleService contextBundleService;
    @MockitoBean
    private DocumentReader documentReader;
    @MockitoBean
    private IndexNames indexNames;

    @Test
    void searchReturnsItemsFromService() throws Exception {
        LogSearchResponse response = new LogSearchResponse(List.of(sampleSummary()), "cursor-1", 1, 5);
        when(searchService.search(any(LogSearchParams.class))).thenReturn(response);

        mockMvc.perform(get("/api/logs").param("datasetId", "smoke-01").param("size", "5"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.total").value(1))
            .andExpect(jsonPath("$.tookMs").value(5))
            .andExpect(jsonPath("$.nextSearchAfter").value("cursor-1"))
            .andExpect(jsonPath("$.items[0].logId").value("log-1"))
            .andExpect(jsonPath("$.items[0].message").value("Saving owner Owner[1]"));
    }

    @Test
    void searchWithInvalidOrderIsBadRequest() throws Exception {
        mockMvc.perform(get("/api/logs").param("order", "sideways"))
            .andExpect(status().isBadRequest())
            .andExpect(content().contentType("application/problem+json"));
    }

    @Test
    void searchWithNonPositiveSizeIsBadRequest() throws Exception {
        mockMvc.perform(get("/api/logs").param("size", "0"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void searchWithInvalidFromIsBadRequest() throws Exception {
        mockMvc.perform(get("/api/logs").param("from", "not-a-date"))
            .andExpect(status().isBadRequest())
            .andExpect(content().contentType("application/problem+json"));
    }

    @Test
    void getReturnsDetailWhenFound() throws Exception {
        when(indexNames.logs()).thenReturn(LOGS_INDEX);
        when(documentReader.get(eq(LOGS_INDEX), eq("log-1"), eq(EnrichedLog.class))).thenReturn(sampleLog());

        mockMvc.perform(get("/api/logs/log-1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.logId").value("log-1"))
            .andExpect(jsonPath("$.match.statementId").value("stmt-1"));
    }

    @Test
    void getReturns404WhenMissing() throws Exception {
        when(indexNames.logs()).thenReturn(LOGS_INDEX);
        when(documentReader.get(eq(LOGS_INDEX), eq("missing"), eq(EnrichedLog.class))).thenReturn(null);

        mockMvc.perform(get("/api/logs/missing"))
            .andExpect(status().isNotFound())
            .andExpect(content().contentType("application/problem+json"))
            .andExpect(jsonPath("$.detail").value("log not found: missing"));
    }

    @Test
    void candidatesDelegatesToServiceWithFetchedLog() throws Exception {
        EnrichedLog log = sampleLog();
        when(indexNames.logs()).thenReturn(LOGS_INDEX);
        when(documentReader.get(eq(LOGS_INDEX), eq("log-1"), eq(EnrichedLog.class))).thenReturn(log);
        when(candidateService.candidates(log)).thenReturn(List.of(
            new CandidateDetailDto("stmt-1", 0.9, "OwnerResource", "updateOwner", "path", 89, "Saving owner {}")));

        mockMvc.perform(get("/api/logs/log-1/candidates"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].statementId").value("stmt-1"))
            .andExpect(jsonPath("$[0].score").value(0.9))
            .andExpect(jsonPath("$[0].classFqn").value("OwnerResource"));
    }

    @Test
    void candidatesReturns404WhenLogMissing() throws Exception {
        when(indexNames.logs()).thenReturn(LOGS_INDEX);
        when(documentReader.get(eq(LOGS_INDEX), eq("missing"), eq(EnrichedLog.class))).thenReturn(null);

        mockMvc.perform(get("/api/logs/missing/candidates"))
            .andExpect(status().isNotFound())
            .andExpect(content().contentType("application/problem+json"))
            .andExpect(jsonPath("$.detail").value("log not found: missing"));
    }

    @Test
    void neighborsPassesParsedParamsToService() throws Exception {
        EnrichedLog log = sampleLog();
        when(indexNames.logs()).thenReturn(LOGS_INDEX);
        when(documentReader.get(eq(LOGS_INDEX), eq("log-1"), eq(EnrichedLog.class))).thenReturn(log);
        NeighborsResponse response = new NeighborsResponse(List.of(), sampleSummary(), List.of());
        when(neighborhoodService.neighbors(log, 5, 10, "thread")).thenReturn(response);

        mockMvc.perform(get("/api/logs/log-1/neighbors")
                .param("before", "5").param("after", "10").param("scope", "thread"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.current.logId").value("log-1"));
    }

    @Test
    void neighborsUsesDefaultsWhenParamsOmitted() throws Exception {
        EnrichedLog log = sampleLog();
        when(indexNames.logs()).thenReturn(LOGS_INDEX);
        when(documentReader.get(eq(LOGS_INDEX), eq("log-1"), eq(EnrichedLog.class))).thenReturn(log);
        when(neighborhoodService.neighbors(log, LogNeighborhoodService.DEFAULT_BEFORE, LogNeighborhoodService.DEFAULT_AFTER, null))
            .thenReturn(new NeighborsResponse(List.of(), sampleSummary(), List.of()));

        mockMvc.perform(get("/api/logs/log-1/neighbors"))
            .andExpect(status().isOk());
    }

    @Test
    void neighborsWithInvalidScopeIsBadRequest() throws Exception {
        EnrichedLog log = sampleLog();
        when(indexNames.logs()).thenReturn(LOGS_INDEX);
        when(documentReader.get(eq(LOGS_INDEX), eq("log-1"), eq(EnrichedLog.class))).thenReturn(log);
        when(neighborhoodService.neighbors(eq(log), anyInt(), anyInt(), eq("bogus")))
            .thenThrow(new IllegalArgumentException("scope must be 'service', 'thread', or 'dataset': bogus"));

        mockMvc.perform(get("/api/logs/log-1/neighbors").param("scope", "bogus"))
            .andExpect(status().isBadRequest())
            .andExpect(content().contentType("application/problem+json"));
    }

    @Test
    void traceWithNonPositiveLimitIsBadRequest() throws Exception {
        EnrichedLog log = sampleLog();
        when(indexNames.logs()).thenReturn(LOGS_INDEX);
        when(documentReader.get(eq(LOGS_INDEX), eq("log-1"), eq(EnrichedLog.class))).thenReturn(log);
        when(neighborhoodService.trace(log, 0)).thenThrow(new IllegalArgumentException("limit must be positive: 0"));

        mockMvc.perform(get("/api/logs/log-1/trace").param("limit", "0"))
            .andExpect(status().isBadRequest())
            .andExpect(content().contentType("application/problem+json"));
    }

    @Test
    void traceReturnsNoTraceIdReasonUnchanged() throws Exception {
        EnrichedLog log = sampleLog();
        when(indexNames.logs()).thenReturn(LOGS_INDEX);
        when(documentReader.get(eq(LOGS_INDEX), eq("log-1"), eq(EnrichedLog.class))).thenReturn(log);
        when(neighborhoodService.trace(log, LogNeighborhoodService.DEFAULT_TRACE_LIMIT))
            .thenReturn(new TraceResponse(List.of(), TraceResponse.REASON_NO_TRACE_ID));

        mockMvc.perform(get("/api/logs/log-1/trace"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items").isEmpty())
            .andExpect(jsonPath("$.reason").value("no-trace-id"));
    }

    @Test
    void contextDelegatesToServiceWithFetchedLogAndParsedNeighbors() throws Exception {
        EnrichedLog log = sampleLog();
        when(indexNames.logs()).thenReturn(LOGS_INDEX);
        when(documentReader.get(eq(LOGS_INDEX), eq("log-1"), eq(EnrichedLog.class))).thenReturn(log);
        ContextBundleDto bundle = new ContextBundleDto(1, LogMapper.toDetail(log),
            new ContextMatchDto("matched", 0.9, "high", List.of()), null, null, null, List.of(), null,
            new ContextNeighborsDto(List.of(), List.of()), new TraceResponse(List.of(), TraceResponse.REASON_NO_TRACE_ID));
        when(contextBundleService.build(log, 5)).thenReturn(bundle);

        mockMvc.perform(get("/api/logs/log-1/context").param("neighbors", "5"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.schemaVersion").value(1))
            .andExpect(jsonPath("$.log.logId").value("log-1"))
            .andExpect(jsonPath("$.match.status").value("matched"));
    }

    @Test
    void contextUsesDefaultNeighborsWhenParamOmitted() throws Exception {
        EnrichedLog log = sampleLog();
        when(indexNames.logs()).thenReturn(LOGS_INDEX);
        when(documentReader.get(eq(LOGS_INDEX), eq("log-1"), eq(EnrichedLog.class))).thenReturn(log);
        ContextBundleDto bundle = new ContextBundleDto(1, LogMapper.toDetail(log), null, null, null, null, List.of(),
            null, new ContextNeighborsDto(List.of(), List.of()), new TraceResponse(List.of(), TraceResponse.REASON_NO_TRACE_ID));
        when(contextBundleService.build(log, ContextBundleService.DEFAULT_NEIGHBORS)).thenReturn(bundle);

        mockMvc.perform(get("/api/logs/log-1/context"))
            .andExpect(status().isOk());
    }

    @Test
    void contextReturns404WhenLogMissing() throws Exception {
        when(indexNames.logs()).thenReturn(LOGS_INDEX);
        when(documentReader.get(eq(LOGS_INDEX), eq("missing"), eq(EnrichedLog.class))).thenReturn(null);

        mockMvc.perform(get("/api/logs/missing/context"))
            .andExpect(status().isNotFound())
            .andExpect(content().contentType("application/problem+json"))
            .andExpect(jsonPath("$.detail").value("log not found: missing"));
    }

    private static LogSummary sampleSummary() {
        return new LogSummary("log-1", Instant.parse("2026-09-24T10:00:00Z"), "customers-service", "INFO",
            "thread-1", "o.s.s.p.c.web.OwnerResource", "Saving owner Owner[1]", "matched", 0.9, "high",
            false, null, "org.springframework.samples.petclinic.customers.web.OwnerResource", "updateOwner", 89);
    }

    private static EnrichedLog sampleLog() {
        MatchResult match = new MatchResult("matched", "stmt-1", 0.9, "high", List.of(), java.util.Map.of(),
            List.of(), "petclinic", "customers-service", "org.example.OwnerResource", "updateOwner", "path",
            "slf4j", "placeholders", 89, "Saving owner {}", null);
        return new EnrichedLog("log-1", Instant.parse("2026-09-24T10:00:00Z"), "2026-09-24T10:00:00Z", "smoke-01",
            "logs/customers-service.log", 42, 1, 0, "customers-service", "spring-petclinic-customers-service",
            "app", "1", "thread-1", Level.INFO, "o.s.s.p.c.web.OwnerResource",
            "org.springframework.samples.petclinic.customers.web.OwnerResource", "Saving owner Owner[1]",
            "raw text", null, null, null, new CodeVersion("petclinic", "v1"), match, null, "spring-boot-default",
            "1.0.0", Instant.parse("2026-09-24T10:00:01Z"));
    }
}
