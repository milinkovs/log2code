package org.log2code.ingester.match;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Loads {@link MatchingConfig} from a YAML file ({@code config/matching.yml}, 0.10/0.11, T20 step 1).
 * No {@code PropertyNamingStrategy} is configured (unlike {@code LogFormatsConfigLoader}'s kebab-case):
 * {@link MatchingConfig}'s fields already carry an explicit {@code @JsonProperty} wherever the YAML key
 * differs from the Java name, since the file itself mixes snake_case and one hyphenated key.
 */
public final class MatchingConfigLoader {

    private MatchingConfigLoader() {
    }

    public static MatchingConfig load(Path configFile) {
        if (!Files.isRegularFile(configFile)) {
            throw new MatchingConfigException("config file not found: " + configFile.toAbsolutePath());
        }
        YAMLMapper mapper = YAMLMapper.builder()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true)
            .build();
        try {
            return mapper.readValue(configFile.toFile(), MatchingConfig.class);
        } catch (IOException e) {
            throw new MatchingConfigException("invalid config file " + configFile.toAbsolutePath() + ": " + e.getMessage(), e);
        }
    }
}
