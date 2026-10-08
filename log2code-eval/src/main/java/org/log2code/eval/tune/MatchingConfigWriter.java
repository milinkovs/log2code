package org.log2code.eval.tune;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.log2code.ingester.match.MatchingConfig;

/**
 * Writes a {@link MatchingConfig} in the format of {@code config/matching.yml}, so that
 * {@code MatchingConfigLoader} reads back exactly what was written. Only used for the files T34 leaves in
 * {@code docs/eval/tuning/}; {@code config/matching.yml} itself is edited by hand to keep its comments.
 */
public final class MatchingConfigWriter {

    private static final ObjectMapper MAPPER = YAMLMapper.builder()
        .disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER)
        .enable(YAMLGenerator.Feature.MINIMIZE_QUOTES)
        .build();

    private MatchingConfigWriter() {
    }

    public static String toYaml(MatchingConfig config, String headerComment) throws IOException {
        StringBuilder out = new StringBuilder();
        for (String line : headerComment.split("\n")) {
            out.append("# ").append(line).append('\n');
        }
        out.append('\n').append(MAPPER.writeValueAsString(config));
        return out.toString();
    }

    public static void write(Path file, MatchingConfig config, String headerComment) throws IOException {
        Files.createDirectories(file.toAbsolutePath().getParent());
        Files.writeString(file, toYaml(config, headerComment), StandardCharsets.UTF_8);
    }
}
