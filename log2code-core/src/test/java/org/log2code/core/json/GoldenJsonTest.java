package org.log2code.core.json;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * Serializing a fixed {@code CatalogEntry}/{@code EnrichedLog} must produce exactly the
 * field names from 0.7. Comparing the rendered JSON text (produced with the same pretty
 * printer used to record the golden file) avoids Jackson's IntNode/LongNode equality trap
 * that a parsed-tree comparison would hit on the {@code long} fields. Line endings are
 * normalized to LF before comparing: {@code .gitattributes} (0.14) normalizes text files
 * to LF on commit, so the on-disk CRLF/LF mix of a Windows-authored fixture is not significant.
 */
class GoldenJsonTest {

    private final ObjectMapper mapper = Json.mapper();

    @Test
    void catalogEntryMatchesGoldenFieldNames() throws Exception {
        String actual = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(JsonRoundTripTest.sampleCatalogEntry()) + "\n";
        assertThat(normalizeEol(actual)).isEqualTo(normalizeEol(readGolden("catalog-entry.json")));
    }

    @Test
    void enrichedLogMatchesGoldenFieldNames() throws Exception {
        String actual = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(JsonRoundTripTest.sampleEnrichedLog()) + "\n";
        assertThat(normalizeEol(actual)).isEqualTo(normalizeEol(readGolden("enriched-log.json")));
    }

    private String readGolden(String fileName) throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/golden/" + fileName)) {
            assertThat(in).as("golden/%s must exist on the test classpath", fileName).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String normalizeEol(String s) {
        return s.replace("\r\n", "\n");
    }
}
