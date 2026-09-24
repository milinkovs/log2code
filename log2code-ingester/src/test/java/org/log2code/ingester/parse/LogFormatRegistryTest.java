package org.log2code.ingester.parse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.log2code.core.model.Level;

/** Loads the real {@code config/log-formats.yml} (0.11, T17 step 1/6) and builds all three formats. */
class LogFormatRegistryTest {

    private static final Path REAL_CONFIG = Path.of("..", "config", "log-formats.yml");

    @Test
    void loadsAllThreeFormatsFromTheRealConfigFile() {
        LogFormatRegistry registry = LogFormatRegistry.load(REAL_CONFIG);

        LineParser presetParser = registry.get("spring-boot-default");
        HeaderFields fields = presetParser.parseHeader(
            "2026-09-21T18:40:03.732Z  INFO 1 --- [customers-service] [nio-8081-exec-1] "
                + "[6ab17a0317ae033034328c9faf2c8bf9-27023b9c90191af8] o.s.s.p.customers.web.OwnerResource      : Saving owner test")
            .orElseThrow();
        assertThat(fields.level()).isEqualTo(Level.INFO);
        assertThat(fields.appName()).isEqualTo("customers-service");

        LineParser logbackParser = registry.get("example-logback");
        assertThat(logbackParser.parseHeader("2026-09-21 18:40:03.732 INFO  [main] com.example.Foo - hello").isPresent()).isTrue();

        LineParser jsonParser = registry.get("example-json");
        assertThat(jsonParser.parseHeader("{\"@timestamp\":\"2026-09-21T18:40:03.732Z\",\"message\":\"hello\"}").isPresent()).isTrue();
    }

    @Test
    void unknownFormatNameFails() {
        LogFormatRegistry registry = LogFormatRegistry.load(REAL_CONFIG);

        assertThatThrownBy(() -> registry.get("does-not-exist"))
            .isInstanceOf(LogFormatException.class)
            .hasMessageContaining("does-not-exist");
    }

    @Test
    void missingConfigFileFails(@org.junit.jupiter.api.io.TempDir Path dir) {
        assertThatThrownBy(() -> LogFormatRegistry.load(dir.resolve("does-not-exist.yml")))
            .isInstanceOf(LogFormatException.class);
    }

    @Test
    void unknownPresetNameFails(@org.junit.jupiter.api.io.TempDir Path dir) throws Exception {
        Path configFile = dir.resolve("log-formats.yml");
        java.nio.file.Files.writeString(configFile, "formats:\n  mystery:\n    type: preset\n");

        assertThatThrownBy(() -> LogFormatRegistry.load(configFile))
            .isInstanceOf(LogFormatException.class)
            .hasMessageContaining("preset");
    }

    @Test
    void unknownTypeFails(@org.junit.jupiter.api.io.TempDir Path dir) throws Exception {
        Path configFile = dir.resolve("log-formats.yml");
        java.nio.file.Files.writeString(configFile, "formats:\n  weird:\n    type: xml\n");

        assertThatThrownBy(() -> LogFormatRegistry.load(configFile))
            .isInstanceOf(LogFormatException.class)
            .hasMessageContaining("xml");
    }
}
