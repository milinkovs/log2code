package org.log2code.ingester.catalog;

import java.io.IOException;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.log2code.core.opensearch.OpenSearchClientFactory;
import org.log2code.core.opensearch.OpenSearchConfig;
import org.opensearch.client.opensearch.OpenSearchClient;

/**
 * T19 AC2 one-off manual measurement against the REAL running infra (localhost:9200, the actual
 * PetClinic project + all 23 selected dependencies from T12/T14) - not part of the automated suite
 * (needs live infra with real data), run once by hand and the printed line recorded in progress.md.
 */
@Disabled("manual AC2 measurement against live infra; see docs/progress.md T19 entry for the recorded output")
class ManualCatalogIndexLoadCheck {

    @Test
    void loadRealCatalogAndPrintTimingAndMemory() throws IOException {
        OpenSearchClient client = OpenSearchClientFactory.create(OpenSearchConfig.of("http://localhost:9200"));
        try {
            CatalogIndex.load(client, "spring-petclinic-microservices", "3858f9c630cf989bb6809a86edf47c2be78dc9f1");
        } finally {
            OpenSearchClientFactory.close(client);
        }
    }
}
