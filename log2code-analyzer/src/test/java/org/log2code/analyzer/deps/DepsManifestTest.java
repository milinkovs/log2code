package org.log2code.analyzer.deps;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.log2code.core.json.Json;

/** Locks in the T12 step 4 manifest JSON shape (snake_case, via {@code Json.mapper()}'s naming strategy). */
class DepsManifestTest {

    @Test
    void serializesToTheDocumentedSnakeCaseShape() throws Exception {
        DepsManifest manifest = new DepsManifest("3858f9c630cf989bb6809a86edf47c2be78dc9f1", List.of(
            new DepsManifest.Module("spring-petclinic-customers-service", "customers-service", List.of(
                new DepsManifest.ManifestArtifact("com.zaxxer:HikariCP:7.0.2", "C:\\jars\\HikariCP-7.0.2.jar",
                    "C:\\jars\\HikariCP-7.0.2-sources.jar", true, "seen-in-logs", 3, false),
                new DepsManifest.ManifestArtifact("org.webjars:webjars-locator-core:0.59", "C:\\jars\\wl.jar",
                    null, false, null, 0, false)
            ))
        ));

        String json = Json.mapper().writeValueAsString(manifest);

        assertThat(json).contains("\"project_commit\"", "\"module\"", "\"service\"", "\"artifacts\"",
            "\"gav\"", "\"jar\"", "\"sources_jar\"", "\"selected\"", "\"reason\"", "\"logger_hits\"", "\"sources_missing\"");
        // NON_NULL: a non-selected artifact's null reason/sources_jar are omitted, not written as null.
        assertThat(json).doesNotContain("\"reason\":null", "\"sources_jar\":null");

        DepsManifest roundTrip = Json.mapper().readValue(json, DepsManifest.class);
        assertThat(roundTrip).isEqualTo(manifest);
    }
}
