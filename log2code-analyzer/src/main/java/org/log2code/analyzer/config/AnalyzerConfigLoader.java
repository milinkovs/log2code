package org.log2code.analyzer.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.log2code.analyzer.CliUserException;

/** Loads {@link AnalyzerConfig} from a YAML file (0.11 keys are kebab-case, e.g. {@code include-modules}). */
public final class AnalyzerConfigLoader {

    private AnalyzerConfigLoader() {
    }

    public static AnalyzerConfig load(Path configFile) {
        if (!Files.isRegularFile(configFile)) {
            throw new CliUserException("config file not found: " + configFile.toAbsolutePath());
        }
        YAMLMapper mapper = YAMLMapper.builder()
            .propertyNamingStrategy(PropertyNamingStrategies.KEBAB_CASE)
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true)
            .build();
        try {
            return mapper.readValue(configFile.toFile(), AnalyzerConfig.class);
        } catch (IOException e) {
            // Also catches Jackson's ValueInstantiationException, which wraps IllegalArgumentException
            // thrown by AnalyzerConfig's compact constructors (missing required fields).
            throw new CliUserException("invalid config file " + configFile.toAbsolutePath() + ": " + e.getMessage(), e);
        }
    }
}
