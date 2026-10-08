package org.log2code.eval.tune;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.log2code.ingester.match.MatchingConfig;
import org.log2code.ingester.match.MatchingConfigLoader;

class MatchingConfigWriterTest {

    private static final MatchingConfig CONFIG = MatchingConfigLoader.load(Path.of("..", "config", "matching.yml"));

    @Test
    void theLoaderReadsBackExactlyWhatWasWritten(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("nested").resolve("matching.yml");

        MatchingConfigWriter.write(file, CONFIG, "a header");

        assertThat(MatchingConfigLoader.load(file)).isEqualTo(CONFIG);
    }

    @Test
    void aTunedConfigurationSurvivesTheRoundTrip(@TempDir Path dir) throws IOException {
        double[] values = ParameterSpace.read(CONFIG);
        values[ParameterSpace.indexOf("logger_exact")] = 0.27;
        values[ParameterSpace.indexOf("specificity_k")] = 14;
        values[ParameterSpace.indexOf("min_score")] = 0.36;
        MatchingConfig tuned = ParameterSpace.apply(CONFIG, values);
        Path file = dir.resolve("matching.yml");

        MatchingConfigWriter.write(file, tuned, "tuned");

        MatchingConfig read = MatchingConfigLoader.load(file);
        assertThat(read).isEqualTo(tuned);
        assertThat(read.weights().loggerExact()).isEqualTo(0.27);
        assertThat(read.oracle().unreliableCallers()).isEqualTo(CONFIG.oracle().unreliableCallers());
    }

    @Test
    void usesTheKeysOfConfigMatchingYml(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("matching.yml");

        MatchingConfigWriter.write(file, CONFIG, "line one\nline two");

        String text = Files.readString(file);
        assertThat(text).startsWith("# line one\n# line two\n");
        assertThat(text).contains("weights:", "  regex_full:", "  level_dynamic:", "thresholds:", "  min_score:", "candidates:",
            "  top_k_tokens:", "oracle:", "  unreliable-callers:");
        assertThat(text).doesNotContain("---");
    }
}
