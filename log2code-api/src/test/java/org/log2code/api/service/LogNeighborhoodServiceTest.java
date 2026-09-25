package org.log2code.api.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.log2code.api.dto.TraceResponse;
import org.log2code.core.model.CodeVersion;
import org.log2code.core.model.EnrichedLog;
import org.log2code.core.model.Level;
import org.log2code.core.opensearch.IndexNames;
import org.opensearch.client.opensearch.OpenSearchClient;

/**
 * Pure-logic coverage for {@link LogNeighborhoodService}'s validation and the {@code trace}
 * no-{@code trace_id} short circuit — both resolve without touching OpenSearch, so the client is
 * mocked and asserted untouched. The search paths (AC2, scope behavior) are covered by
 * {@code CatalogGraphApiIT} against a real cluster.
 */
class LogNeighborhoodServiceTest {

    @Test
    void neighborsRejectsNegativeBefore() {
        OpenSearchClient client = mock(OpenSearchClient.class);
        LogNeighborhoodService service = new LogNeighborhoodService(client, new IndexNames());

        assertThatThrownBy(() -> service.neighbors(sampleLog(), -1, 5, "service"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("before");
        verifyNoInteractions(client);
    }

    @Test
    void neighborsRejectsNegativeAfter() {
        OpenSearchClient client = mock(OpenSearchClient.class);
        LogNeighborhoodService service = new LogNeighborhoodService(client, new IndexNames());

        assertThatThrownBy(() -> service.neighbors(sampleLog(), 5, -1, "service"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("after");
        verifyNoInteractions(client);
    }

    @Test
    void neighborsRejectsUnknownScope() {
        OpenSearchClient client = mock(OpenSearchClient.class);
        LogNeighborhoodService service = new LogNeighborhoodService(client, new IndexNames());

        assertThatThrownBy(() -> service.neighbors(sampleLog(), 5, 5, "bogus"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("scope");
        verifyNoInteractions(client);
    }

    @Test
    void traceRejectsNonPositiveLimit() {
        OpenSearchClient client = mock(OpenSearchClient.class);
        LogNeighborhoodService service = new LogNeighborhoodService(client, new IndexNames());

        assertThatThrownBy(() -> service.trace(sampleLog(), 0))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("limit");
        verifyNoInteractions(client);
    }

    @Test
    void traceShortCircuitsWhenCurrentLogHasNoTraceId() {
        OpenSearchClient client = mock(OpenSearchClient.class);
        LogNeighborhoodService service = new LogNeighborhoodService(client, new IndexNames());

        TraceResponse response = service.trace(sampleLog(), 200);

        assertThat(response.items()).isEmpty();
        assertThat(response.reason()).isEqualTo(TraceResponse.REASON_NO_TRACE_ID);
        verifyNoInteractions(client);
    }

    private static EnrichedLog sampleLog() {
        return new EnrichedLog("log-1", Instant.parse("2026-09-24T10:00:00Z"), "2026-09-24T10:00:00Z", "smoke-01",
            "logs/customers-service.log", 42, 1, 0, "customers-service", "spring-petclinic-customers-service",
            "app", "1", "thread-1", Level.INFO, "o.s.s.p.c.web.OwnerResource",
            "org.springframework.samples.petclinic.customers.web.OwnerResource", "Saving owner Owner[1]",
            "raw text", null, null, null, new CodeVersion("petclinic", "v1"), null, null, "spring-boot-default",
            "1.0.0", Instant.parse("2026-09-24T10:00:01Z"));
    }
}
