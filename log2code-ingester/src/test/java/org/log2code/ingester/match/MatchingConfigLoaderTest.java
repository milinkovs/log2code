package org.log2code.ingester.match;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Loads the real {@code config/matching.yml} (0.10, T20 step 1) and checks it against 0.10's literal initial values. */
class MatchingConfigLoaderTest {

    private static final Path REAL_CONFIG = Path.of("..", "config", "matching.yml");

    @Test
    void loadsTheRealConfigWithExactly010sInitialWeights() {
        MatchingConfig config = MatchingConfigLoader.load(REAL_CONFIG);

        MatchingConfig.Weights w = config.weights();
        assertThat(w.regexFull()).isCloseTo(0.45, within(1e-9));
        assertThat(w.regexPrefix()).isCloseTo(0.20, within(1e-9));
        assertThat(w.specificityMax()).isCloseTo(0.20, within(1e-9));
        assertThat(w.specificityK()).isCloseTo(15, within(1e-9));
        assertThat(w.loggerExact()).isCloseTo(0.20, within(1e-9));
        assertThat(w.loggerHierarchy()).isCloseTo(0.15, within(1e-9));
        assertThat(w.loggerAbbrevMulti()).isCloseTo(0.12, within(1e-9));
        assertThat(w.loggerConflict()).isCloseTo(-0.25, within(1e-9));
        assertThat(w.levelEqual()).isCloseTo(0.10, within(1e-9));
        assertThat(w.levelDynamic()).isCloseTo(0.03, within(1e-9));
        assertThat(w.levelConflict()).isCloseTo(-0.30, within(1e-9));
        assertThat(w.throwableConsistent()).isCloseTo(0.05, within(1e-9));
        assertThat(w.throwableInconsistent()).isCloseTo(-0.05, within(1e-9));

        MatchingConfig.Thresholds t = config.thresholds();
        assertThat(t.minScore()).isCloseTo(0.35, within(1e-9));
        assertThat(t.ambiguityMargin()).isCloseTo(0.05, within(1e-9));
        assertThat(t.ambiguousPenalty()).isCloseTo(0.6, within(1e-9));
        assertThat(t.high()).isCloseTo(0.75, within(1e-9));
        assertThat(t.medium()).isCloseTo(0.50, within(1e-9));

        MatchingConfig.Candidates c = config.candidates();
        assertThat(c.topKTokens()).isEqualTo(50);
        assertThat(c.maxCandidates()).isEqualTo(200);
    }

    @Test
    void oracleUnreliableCallersMatchesT18StepFivePlusTheJuliAddition() {
        MatchingConfig config = MatchingConfigLoader.load(REAL_CONFIG);

        assertThat(config.oracle().unreliableCallers()).containsExactlyInAnyOrder(
            "org.springframework.core.log.LogAccessor",
            "org.springframework.core.log.LogMessage",
            "org.apache.commons.logging.*",
            "org.slf4j.bridge.*",
            "org.apache.logging.slf4j.*",
            "java.util.logging.*",
            "org.jboss.logging.*",
            "org.apache.juli.logging.*");
    }

    @Test
    void missingConfigFileFails(@TempDir Path dir) {
        assertThatThrownBy(() -> MatchingConfigLoader.load(dir.resolve("does-not-exist.yml")))
            .isInstanceOf(MatchingConfigException.class);
    }

    @Test
    void unknownTopLevelPropertyFails(@TempDir Path dir) throws Exception {
        Path configFile = dir.resolve("matching.yml");
        Files.writeString(configFile, "weights:\n  regex_full: 0.45\nmystery: true\n");

        assertThatThrownBy(() -> MatchingConfigLoader.load(configFile))
            .isInstanceOf(MatchingConfigException.class);
    }
}
