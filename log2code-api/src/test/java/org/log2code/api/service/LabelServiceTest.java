package org.log2code.api.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.log2code.api.dto.LabelRequest;
import org.log2code.core.model.CodeVersion;
import org.log2code.core.model.EnrichedLog;
import org.log2code.core.model.Level;
import org.log2code.core.opensearch.DocumentReader;
import org.log2code.core.opensearch.IndexNames;
import org.opensearch.client.opensearch.OpenSearchClient;

/**
 * Pure-logic coverage for {@link LabelService}'s {@code verdict} validation — resolves without
 * touching OpenSearch. The write/read/list paths are covered by {@code LabelsApiIT} against a
 * real cluster.
 */
class LabelServiceTest {

    @Test
    void putRejectsAnUnknownVerdictWithoutWriting() {
        OpenSearchClient client = mock(OpenSearchClient.class);
        DocumentReader documentReader = mock(DocumentReader.class);
        LabelService service = new LabelService(client, documentReader, new IndexNames());

        assertThatThrownBy(() -> service.put(sampleLog(), new LabelRequest("maybe", null, null)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("verdict");
        verifyNoInteractions(client);
    }

    @Test
    void putRejectsAMissingVerdictWithoutWriting() {
        OpenSearchClient client = mock(OpenSearchClient.class);
        DocumentReader documentReader = mock(DocumentReader.class);
        LabelService service = new LabelService(client, documentReader, new IndexNames());

        assertThatThrownBy(() -> service.put(sampleLog(), new LabelRequest(null, null, null)))
            .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(client);
    }

    private static EnrichedLog sampleLog() {
        return new EnrichedLog("log-1", Instant.parse("2026-09-25T10:00:00Z"), "2026-09-25T10:00:00Z", "smoke-01",
            "logs/customers-service.log", 42, 1, 0, "customers-service", "spring-petclinic-customers-service",
            "app", "1", "thread-1", Level.INFO, "a.Foo", "a.Foo", "boom", "raw text", null, null, null,
            new CodeVersion("petclinic", "v1"), null, null, "spring-boot-default", "1.0.0", Instant.parse("2026-09-25T10:00:01Z"));
    }
}
