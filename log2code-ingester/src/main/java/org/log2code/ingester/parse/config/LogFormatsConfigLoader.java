package org.log2code.ingester.parse.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.log2code.ingester.parse.LogFormatException;

/** Loads {@link LogFormatsConfig} from a YAML file ({@code config/log-formats.yml}, 0.11). */
public final class LogFormatsConfigLoader {

    private LogFormatsConfigLoader() {
    }

    public static LogFormatsConfig load(Path configFile) {
        if (!Files.isRegularFile(configFile)) {
            throw new LogFormatException("config file not found: " + configFile.toAbsolutePath());
        }
        YAMLMapper mapper = YAMLMapper.builder()
            .propertyNamingStrategy(PropertyNamingStrategies.KEBAB_CASE)
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true)
            .build();
        try {
            return mapper.readValue(configFile.toFile(), LogFormatsConfig.class);
        } catch (IOException e) {
            throw new LogFormatException("invalid config file " + configFile.toAbsolutePath() + ": " + e.getMessage(), e);
        }
    }
}
