package org.log2code.api.web;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.log2code.api.dto.LogSummary;
import org.log2code.api.service.ReviewQueueService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** {@code @WebMvcTest} for {@link ReviewQueueController} (T25 step 2). */
@WebMvcTest(ReviewQueueController.class)
class ReviewQueueControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ReviewQueueService reviewQueueService;

    @Test
    void usesDefaultLimitAndSeedWhenOmitted() throws Exception {
        when(reviewQueueService.reviewQueue(null, ReviewQueueService.DEFAULT_LIMIT, ReviewQueueService.DEFAULT_SEED))
            .thenReturn(List.of(sampleSummary()));

        mockMvc.perform(get("/api/review-queue"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].logId").value("log-1"));
    }

    @Test
    void passesParsedDatasetLimitAndSeedToService() throws Exception {
        when(reviewQueueService.reviewQueue("smoke-01", 5, 42L)).thenReturn(List.of());

        mockMvc.perform(get("/api/review-queue").param("datasetId", "smoke-01").param("limit", "5").param("seed", "42"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    void returns400ForNonPositiveLimit() throws Exception {
        when(reviewQueueService.reviewQueue(null, 0, ReviewQueueService.DEFAULT_SEED))
            .thenThrow(new IllegalArgumentException("limit must be positive: 0"));

        mockMvc.perform(get("/api/review-queue").param("limit", "0"))
            .andExpect(status().isBadRequest())
            .andExpect(content().contentType("application/problem+json"));
    }

    private static LogSummary sampleSummary() {
        return new LogSummary("log-1", Instant.parse("2026-09-25T10:00:00Z"), "customers-service", "INFO",
            "thread-1", "a.Foo", "boom", "ambiguous", 0.4, "low", false, null, "a.Foo", "bar", 4);
    }
}
