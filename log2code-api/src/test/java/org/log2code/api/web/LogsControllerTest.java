package org.log2code.api.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.log2code.api.dto.LogSearchResponse;
import org.log2code.api.dto.LogSummary;
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

/** {@code @WebMvcTest} for {@link LogsController}: collaborators are mocked (T23 "Testovi"). */
@WebMvcTest(LogsController.class)
class LogsControllerTest {

    private static final String LOGS_INDEX = "log2code-logs";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private LogSearchService searchService;
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
