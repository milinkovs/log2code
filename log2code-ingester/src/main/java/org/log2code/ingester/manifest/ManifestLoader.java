package org.log2code.ingester.manifest;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Loads a {@link DatasetManifest} from {@code manifest.yml} (0.11: fields are snake_case). */
public final class ManifestLoader {

    private ManifestLoader() {
    }

    public static DatasetManifest load(Path manifestFile) {
        if (!Files.isRegularFile(manifestFile)) {
            throw new ManifestException("manifest not found: " + manifestFile.toAbsolutePath());
        }
        YAMLMapper mapper = YAMLMapper.builder()
            .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true)
            .build();
        try {
            return mapper.readValue(manifestFile.toFile(), DatasetManifest.class);
        } catch (IOException e) {
            throw new ManifestException("invalid manifest " + manifestFile.toAbsolutePath() + ": " + e.getMessage(), e);
        }
    }
}
