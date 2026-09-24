package org.log2code.ingester.parse.preset;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.log2code.core.model.Level;
import org.log2code.ingester.parse.AnsiCodes;
import org.log2code.ingester.parse.HeaderFields;
import org.log2code.ingester.parse.LineParser;

/**
 * The {@code spring-boot-default} preset (0.9, {@code docs/log-format.md}): Spring Boot's default
 * console pattern, as PetClinic's {@code logback-spring.xml} (which only includes Boot's
 * {@code base.xml}) actually produces it.
 *
 * <p>Two segments that {@code docs/log-format.md} §2 shows as always-bracketed are in fact
 * conditionally emitted by Boot's own converters and must be optional here (confirmed against
 * every header in {@code fixtures/logs/} and every recorded dataset, see {@code docs/decisions.md}
 * and T17's progress entry):
 * <ul>
 *   <li>the {@code [spring.application.name]} segment: {@code %esb} omits the brackets entirely
 *       (not just empty brackets) when the property is unset, e.g. {@code config-server}
 *       (ADR-007);</li>
 *   <li>the {@code [traceId-spanId]} correlation segment: {@code ${LOG_CORRELATION_PATTERN:-}}
 *       resolves to nothing at all (again, no brackets) on services where Micrometer Tracing
 *       never installed the property, e.g. {@code discovery-server} — as opposed to services that
 *       have tracing but no active span, where the brackets are present and filled with spaces.</li>
 * </ul>
 */
public final class SpringBootDefaultParser implements LineParser {

    private static final Pattern HEADER = Pattern.compile(
        "^(\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}(?:Z|[+-]\\d{2}:\\d{2})) +([A-Z]+) (\\d+) --- "
            + "(?:\\[([^\\]]*)\\] )?"   // app name, optional (absent entirely when unset, not just empty)
            + "\\[(.{15})\\] "         // thread, fixed width
            + "(?:\\[(.{49})\\] )?"    // correlation (traceId-spanId), optional
            + "(.{40}) : "             // logger, fixed width
            + "(.*)$");                // message (rest of line, may be empty)

    @Override
    public Optional<HeaderFields> parseHeader(String rawLine) {
        String line = AnsiCodes.strip(rawLine);
        Matcher m = HEADER.matcher(line);
        if (!m.matches()) {
            return Optional.empty();
        }
        String timestampRaw = m.group(1);
        Instant instant;
        try {
            instant = OffsetDateTime.parse(timestampRaw).toInstant();
        } catch (DateTimeParseException e) {
            return Optional.empty();
        }
        String appName = blankToNull(m.group(4));
        String thread = m.group(5).trim();
        String correlationRaw = m.group(6);
        String loggerRaw = m.group(7).trim();
        String message = m.group(8);

        return Optional.of(new HeaderFields(
            timestampRaw,
            instant,
            Level.parse(m.group(2)),
            m.group(3),
            appName,
            thread,
            correlationRaw,
            loggerRaw,
            message,
            null,
            null,
            null));
    }

    private static String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value.trim();
    }
}
