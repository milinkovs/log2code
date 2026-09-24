package org.log2code.ingester.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.log2code.core.github.CodeUnitsConfig;
import org.log2code.ingester.IngesterUserException;

/**
 * Loads {@link CodeUnitsConfig} from a YAML file (T15's {@code config/code-units.yml}) for the
 * ingester's own {@code GithubLinker} use (T21 step 4: {@code exception.frames[].github_url}) - a
 * small ingester-local copy of the analyzer's loader of the same name, since the ingester module does
 * not (and should not) depend on {@code log2code-analyzer}.
 */
public final class CodeUnitsConfigLoader {

    private CodeUnitsConfigLoader() {
    }

    public static CodeUnitsConfig load(Path configFile) {
        if (!Files.isRegularFile(configFile)) {
            throw new IngesterUserException("code units config file not found: " + configFile.toAbsolutePath());
        }
        YAMLMapper mapper = YAMLMapper.builder()
            .propertyNamingStrategy(PropertyNamingStrategies.KEBAB_CASE)
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true)
            .build();
        try {
            return mapper.readValue(configFile.toFile(), CodeUnitsConfig.class);
        } catch (IOException e) {
            throw new IngesterUserException("invalid code units config file " + configFile.toAbsolutePath() + ": " + e.getMessage(), e);
        }
    }
}
