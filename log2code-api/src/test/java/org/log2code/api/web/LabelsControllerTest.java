package org.log2code.api.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.log2code.api.dto.LabelDto;
import org.log2code.api.dto.LabelSearchResponse;
import org.log2code.api.service.LabelListParams;
import org.log2code.api.service.LabelService;
import org.log2code.core.model.CodeVersion;
import org.log2code.core.model.EnrichedLog;
import org.log2code.core.model.Level;
import org.log2code.core.opensearch.DocumentReader;
import org.log2code.core.opensearch.IndexNames;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** {@code @WebMvcTest} for {@link LabelsController} (T25 step 2). */
@WebMvcTest(LabelsController.class)
class LabelsControllerTest {

    private static final String LOGS_INDEX = "log2code-logs";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private LabelService labelService;
    @MockitoBean
    private DocumentReader documentReader;
    @MockitoBean
    private IndexNames indexNames;

    @Test
    void putFetchesLogAndDelegatesToService() throws Exception {
        EnrichedLog log = sampleLog();
        when(indexNames.logs()).thenReturn(LOGS_INDEX);
        when(documentReader.get(eq(LOGS_INDEX), eq("log-1"), eq(EnrichedLog.class))).thenReturn(log);
        when(labelService.put(eq(log), any())).thenReturn(sampleLabel());

        mockMvc.perform(put("/api/labels/log-1")
                .contentType("application/json")
                .content("{\"verdict\":\"correct\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.logId").value("log-1"))
            .andExpect(jsonPath("$.verdict").value("correct"));
    }

    @Test
    void putReturns404WhenLogMissing() throws Exception {
        when(indexNames.logs()).thenReturn(LOGS_INDEX);
        when(documentReader.get(eq(LOGS_INDEX), eq("missing"), eq(EnrichedLog.class))).thenReturn(null);

        mockMvc.perform(put("/api/labels/missing")
                .contentType("application/json")
                .content("{\"verdict\":\"correct\"}"))
            .andExpect(status().isNotFound())
            .andExpect(content().contentType("application/problem+json"))
            .andExpect(jsonPath("$.detail").value("log not found: missing"));
    }

    @Test
    void putReturns400ForUnknownVerdict() throws Exception {
        EnrichedLog log = sampleLog();
        when(indexNames.logs()).thenReturn(LOGS_INDEX);
        when(documentReader.get(eq(LOGS_INDEX), eq("log-1"), eq(EnrichedLog.class))).thenReturn(log);
        when(labelService.put(eq(log), any())).thenThrow(new IllegalArgumentException("verdict must be 'correct', 'incorrect', or 'not_in_catalog': maybe"));

        mockMvc.perform(put("/api/labels/log-1")
                .contentType("application/json")
                .content("{\"verdict\":\"maybe\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(content().contentType("application/problem+json"));
    }

    @Test
    void getReturnsLabelWhenFound() throws Exception {
        when(labelService.get("log-1")).thenReturn(sampleLabel());

        mockMvc.perform(get("/api/labels/log-1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.verdict").value("correct"));
    }

    @Test
    void getReturns404WhenMissing() throws Exception {
        when(labelService.get("missing")).thenReturn(null);

        mockMvc.perform(get("/api/labels/missing"))
            .andExpect(status().isNotFound())
            .andExpect(content().contentType("application/problem+json"))
            .andExpect(jsonPath("$.detail").value("label not found: missing"));
    }

    @Test
    void deleteReturns204RegardlessOfPriorExistence() throws Exception {
        mockMvc.perform(delete("/api/labels/log-1")).andExpect(status().isNoContent());
        verify(labelService).delete("log-1");
    }

    @Test
    void listReturnsItemsFromService() throws Exception {
        LabelSearchResponse response = new LabelSearchResponse(List.of(sampleLabel()), "cursor-1", 1);
        when(labelService.list(any(LabelListParams.class))).thenReturn(response);

        mockMvc.perform(get("/api/labels").param("datasetId", "smoke-01").param("verdict", "correct"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.total").value(1))
            .andExpect(jsonPath("$.items[0].logId").value("log-1"));
    }

    @Test
    void listReturns400ForUnknownVerdict() throws Exception {
        mockMvc.perform(get("/api/labels").param("verdict", "maybe"))
            .andExpect(status().isBadRequest())
            .andExpect(content().contentType("application/problem+json"));
    }

    private static LabelDto sampleLabel() {
        return new LabelDto("log-1", "smoke-01", "correct", null, "stmt-1", "looks right", Instant.parse("2026-09-25T10:00:00Z"));
    }

    private static EnrichedLog sampleLog() {
        return new EnrichedLog("log-1", Instant.parse("2026-09-25T10:00:00Z"), "2026-09-25T10:00:00Z", "smoke-01",
            "logs/customers-service.log", 42, 1, 0, "customers-service", "spring-petclinic-customers-service",
            "app", "1", "thread-1", Level.INFO, "a.Foo", "a.Foo", "Saving owner Owner[1]", "raw text", null, null,
            null, new CodeVersion("petclinic", "v1"), null, null, "spring-boot-default", "1.0.0", Instant.parse("2026-09-25T10:00:01Z"));
    }
}
