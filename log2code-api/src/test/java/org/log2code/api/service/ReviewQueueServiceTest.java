package org.log2code.api.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import org.junit.jupiter.api.Test;
import org.log2code.core.opensearch.DocumentReader;
import org.log2code.core.opensearch.IndexNames;

/**
 * Pure-logic coverage for {@link ReviewQueueService}'s {@code limit} validation — resolves
 * without touching OpenSearch. The eligibility query and "already labeled" exclusion are covered
 * by {@code LabelsApiIT} against a real cluster.
 */
class ReviewQueueServiceTest {

    @Test
    void rejectsNonPositiveLimitWithoutQuerying() {
        DocumentReader documentReader = mock(DocumentReader.class);
        ReviewQueueService service = new ReviewQueueService(documentReader, new IndexNames());

        assertThatThrownBy(() -> service.reviewQueue("smoke-01", 0, 1))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("limit");
        assertThatThrownBy(() -> service.reviewQueue("smoke-01", -5, 1))
            .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(documentReader);
    }
}
