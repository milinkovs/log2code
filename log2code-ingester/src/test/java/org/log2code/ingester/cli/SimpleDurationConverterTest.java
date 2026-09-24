package org.log2code.ingester.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import picocli.CommandLine.TypeConversionException;

/** T22's {@code --poll}/{@code --flush-timeout} accept simple {@code <number><unit>} durations. */
class SimpleDurationConverterTest {

    private final SimpleDurationConverter converter = new SimpleDurationConverter();

    @Test
    void parsesSeconds() {
        assertThat(converter.convert("2s")).isEqualTo(Duration.ofSeconds(2));
    }

    @Test
    void parsesMillis() {
        assertThat(converter.convert("500ms")).isEqualTo(Duration.ofMillis(500));
    }

    @Test
    void parsesMinutesAndHours() {
        assertThat(converter.convert("5m")).isEqualTo(Duration.ofMinutes(5));
        assertThat(converter.convert("1h")).isEqualTo(Duration.ofHours(1));
    }

    @Test
    void bareNumberDefaultsToSeconds() {
        assertThat(converter.convert("10")).isEqualTo(Duration.ofSeconds(10));
    }

    @Test
    void rejectsGarbage() {
        assertThatThrownBy(() -> converter.convert("soon")).isInstanceOf(TypeConversionException.class);
        assertThatThrownBy(() -> converter.convert("-5s")).isInstanceOf(TypeConversionException.class);
    }
}
