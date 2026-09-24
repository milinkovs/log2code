package org.log2code.ingester.manifest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Loads real, previously recorded dataset manifests (0.11, T16) and rejects malformed ones. */
class ManifestLoaderTest {

    @Test
    void loadsTheRealSmokeDatasetManifest() {
        DatasetManifest manifest = ManifestLoader.load(Path.of("..", "datasets", "smoke-01", "manifest.yml"));

        assertThat(manifest.datasetId()).isEqualTo("smoke-01");
        assertThat(manifest.oracle()).isFalse();
        assertThat(manifest.logFormat()).isEqualTo("spring-boot-default");
        assertThat(manifest.code().name()).isEqualTo("spring-petclinic-microservices");
        assertThat(manifest.code().version()).isEqualTo("3858f9c630cf989bb6809a86edf47c2be78dc9f1");
        assertThat(manifest.files()).hasSize(6);
        assertThat(manifest.files()).extracting(DatasetManifest.FileEntry::service)
            .containsExactlyInAnyOrder("config-server", "discovery-server", "customers-service",
                "visits-service", "vets-service", "api-gateway");
        assertThat(manifest.files().get(0).path()).isEqualTo("logs/config-server.log.gz");
        assertThat(manifest.files().get(0).module()).isEqualTo("spring-petclinic-config-server");
    }

    @Test
    void loadsTheRealOracleDatasetManifestWithOracleTrue() {
        DatasetManifest manifest = ManifestLoader.load(Path.of("..", "datasets", "smoke-oracle-01", "manifest.yml"));

        assertThat(manifest.oracle()).isTrue();
    }

    @Test
    void missingManifestFails(@TempDir Path dir) {
        assertThatThrownBy(() -> ManifestLoader.load(dir.resolve("does-not-exist.yml")))
            .isInstanceOf(ManifestException.class);
    }

    @Test
    void unknownTopLevelPropertyFails(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("manifest.yml");
        Files.writeString(file, "dataset_id: x\nmystery: true\n");

        assertThatThrownBy(() -> ManifestLoader.load(file)).isInstanceOf(ManifestException.class);
    }
}
