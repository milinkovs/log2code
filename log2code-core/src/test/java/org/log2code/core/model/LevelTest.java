package org.log2code.core.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class LevelTest {

    static Stream<Arguments> parseCases() {
        return Stream.of(
            // SLF4J / Logback / Log4j2 / JBoss Logging names
            Arguments.of("TRACE", Level.TRACE),
            Arguments.of("DEBUG", Level.DEBUG),
            Arguments.of("INFO", Level.INFO),
            Arguments.of("WARN", Level.WARN),
            Arguments.of("ERROR", Level.ERROR),
            Arguments.of("FATAL", Level.FATAL),
            // case-insensitivity
            Arguments.of("trace", Level.TRACE),
            Arguments.of("Debug", Level.DEBUG),
            Arguments.of("info", Level.INFO),
            Arguments.of("wArN", Level.WARN),
            Arguments.of("error", Level.ERROR),
            Arguments.of("fatal", Level.FATAL),
            // surrounding whitespace
            Arguments.of("  INFO  ", Level.INFO),
            Arguments.of(" WARN", Level.WARN),
            // Log4j2's alternate WARN spelling
            Arguments.of("WARNING", Level.WARN),
            Arguments.of("warning", Level.WARN),
            // JUL names
            Arguments.of("FINEST", Level.TRACE),
            Arguments.of("FINER", Level.DEBUG),
            Arguments.of("FINE", Level.DEBUG),
            Arguments.of("CONFIG", Level.INFO),
            Arguments.of("SEVERE", Level.ERROR),
            // already-normalized value
            Arguments.of("UNKNOWN", Level.UNKNOWN),
            // unknown / garbage input
            Arguments.of("VERBOSE", Level.UNKNOWN),
            Arguments.of("NOTICE", Level.UNKNOWN),
            Arguments.of("", Level.UNKNOWN),
            Arguments.of("   ", Level.UNKNOWN),
            Arguments.of(null, Level.UNKNOWN)
        );
    }

    @ParameterizedTest(name = "parse(\"{0}\") = {1}")
    @MethodSource("parseCases")
    void parse(String input, Level expected) {
        assertThat(Level.parse(input)).isEqualTo(expected);
    }

    static Stream<Arguments> compatibleCases() {
        return Stream.of(
            Arguments.of(Level.INFO, Level.INFO, true),
            Arguments.of(Level.ERROR, Level.ERROR, true),
            Arguments.of(Level.FATAL, Level.ERROR, true),
            Arguments.of(Level.ERROR, Level.FATAL, true),
            Arguments.of(Level.UNKNOWN, Level.INFO, true),
            Arguments.of(Level.UNKNOWN, Level.ERROR, true),
            Arguments.of(Level.UNKNOWN, Level.UNKNOWN, true),
            Arguments.of(Level.INFO, Level.DEBUG, false),
            Arguments.of(Level.WARN, Level.ERROR, false),
            Arguments.of(Level.ERROR, Level.UNKNOWN, false),
            Arguments.of(Level.DEBUG, Level.TRACE, false)
        );
    }

    @ParameterizedTest(name = "compatible({0}, {1}) = {2}")
    @MethodSource("compatibleCases")
    void compatible(Level catalog, Level observed, boolean expected) {
        assertThat(Level.compatible(catalog, observed)).isEqualTo(expected);
    }
}
