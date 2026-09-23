package org.log2code.analyzer.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.log2code.analyzer.CliUserException;
import org.log2code.core.github.CodeUnitsConfig;

/** Loads {@link CodeUnitsConfig} from a YAML file (T15, {@code config/code-units.yml}). */
public final class CodeUnitsConfigLoader {

    private CodeUnitsConfigLoader() {
    }

    public static CodeUnitsConfig load(Path configFile) {
        if (!Files.isRegularFile(configFile)) {
            throw new CliUserException("code units config file not found: " + configFile.toAbsolutePath());
        }
        YAMLMapper mapper = YAMLMapper.builder()
            .propertyNamingStrategy(PropertyNamingStrategies.KEBAB_CASE)
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true)
            .build();
        try {
            return mapper.readValue(configFile.toFile(), CodeUnitsConfig.class);
        } catch (IOException e) {
            // Also catches Jackson's ValueInstantiationException for malformed entries.
            throw new CliUserException("invalid code units config file " + configFile.toAbsolutePath() + ": " + e.getMessage(), e);
        }
    }
}
