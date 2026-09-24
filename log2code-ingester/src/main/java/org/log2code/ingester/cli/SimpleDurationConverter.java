package org.log2code.ingester.cli;

import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import picocli.CommandLine.ITypeConverter;
import picocli.CommandLine.TypeConversionException;

/**
 * Parses the simple {@code <number><unit>} durations T22's {@code --poll}/{@code --flush-timeout}
 * options use (e.g. {@code 2s}, {@code 500ms}, {@code 5m}) - {@link Duration#parse(CharSequence)}
 * only accepts the ISO-8601 form ({@code PT2S}), which is not what the task text's examples show.
 */
final class SimpleDurationConverter implements ITypeConverter<Duration> {

    private static final Pattern PATTERN = Pattern.compile("(\\d+)(ms|s|m|h)?");

    @Override
    public Duration convert(String value) {
        Matcher m = PATTERN.matcher(value.trim());
        if (!m.matches()) {
            throw new TypeConversionException("invalid duration '" + value + "' (expected e.g. 2s, 500ms, 5m, 1h)");
        }
        long amount = Long.parseLong(m.group(1));
        String unit = m.group(2) == null ? "s" : m.group(2);
        return switch (unit) {
            case "ms" -> Duration.ofMillis(amount);
            case "s" -> Duration.ofSeconds(amount);
            case "m" -> Duration.ofMinutes(amount);
            case "h" -> Duration.ofHours(amount);
            default -> throw new TypeConversionException("invalid duration unit '" + unit + "'");
        };
    }
}
