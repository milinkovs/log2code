package org.log2code.ingester.parse.logback;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoField;
import java.time.temporal.TemporalAccessor;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.log2code.core.model.Level;
import org.log2code.ingester.parse.AnsiCodes;
import org.log2code.ingester.parse.HeaderFields;
import org.log2code.ingester.parse.LineParser;
import org.log2code.ingester.parse.logback.LogbackPatternCompiler.CapturedField;

/** A {@link LineParser} built by {@link LogbackPatternCompiler} for one {@code logback-pattern} format. */
final class CompiledLineParser implements LineParser {

    private final Pattern pattern;
    private final List<CapturedField> fields;
    private final DateTimeFormatter dateFormatter;
    private final ZoneId defaultZone;

    CompiledLineParser(Pattern pattern, List<CapturedField> fields, DateTimeFormatter dateFormatter, ZoneId defaultZone) {
        this.pattern = pattern;
        this.fields = fields;
        this.dateFormatter = dateFormatter;
        this.defaultZone = defaultZone;
    }

    @Override
    public Optional<HeaderFields> parseHeader(String rawLine) {
        String line = AnsiCodes.strip(rawLine);
        Matcher m = pattern.matcher(line);
        if (!m.matches()) {
            return Optional.empty();
        }

        String timestampRaw = null;
        Instant instant = null;
        Level level = Level.UNKNOWN;
        String pid = null;
        String thread = null;
        String loggerRaw = null;
        String message = null;

        for (CapturedField f : fields) {
            String raw = m.group(f.group());
            String value = (f.exactWidth() && raw != null) ? raw.trim() : raw;
            switch (f.type()) {
                case TIMESTAMP -> {
                    timestampRaw = value;
                    instant = parseInstant(value);
                }
                case LEVEL -> level = Level.parse(value);
                case PID -> pid = value;
                case THREAD -> thread = value;
                case LOGGER -> loggerRaw = value;
                case MESSAGE -> message = value;
                case CLASS, METHOD, LINE, MDC -> {
                    // Matched for regex correctness; HeaderFields has no slot for these (see class javadoc).
                }
            }
        }
        if (instant == null || message == null) {
            return Optional.empty();
        }
        return Optional.of(new HeaderFields(
            timestampRaw, instant, level, pid, null, thread, null, loggerRaw, message, null, null, null));
    }

    private Instant parseInstant(String text) {
        try {
            TemporalAccessor parsed = dateFormatter.parse(text);
            if (parsed.isSupported(ChronoField.OFFSET_SECONDS)) {
                return OffsetDateTime.from(parsed).toInstant();
            }
            if (parsed.isSupported(ChronoField.INSTANT_SECONDS)) {
                return Instant.from(parsed);
            }
            return LocalDateTime.from(parsed).atZone(defaultZone).toInstant();
        } catch (DateTimeException e) {
            return null;
        }
    }
}
