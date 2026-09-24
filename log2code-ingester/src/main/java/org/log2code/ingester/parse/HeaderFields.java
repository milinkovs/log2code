package org.log2code.ingester.parse;

import java.time.Instant;
import org.log2code.core.model.Level;

/**
 * Fields extracted from the line (or, for JSON, the single line) that starts a log event.
 * All fields except {@code messageFirstLine} may be {@code null} when the active format does not
 * capture them (e.g. a {@code logback-pattern} without {@code %thread}, or a {@code json} format
 * whose {@code fields} mapping omits that key). {@code stackTrace}, {@code traceId} and
 * {@code spanId} are populated only by the JSON parser; text-based formats leave them {@code null}
 * and rely on {@code EventAssembler} (T18) to assemble stack traces and trace IDs from later lines.
 */
public record HeaderFields(
    String timestampRaw,
    Instant instant,
    Level level,
    String pid,
    String appName,
    String thread,
    String correlationRaw,
    String loggerRaw,
    String messageFirstLine,
    String stackTrace,
    String traceId,
    String spanId) {

    public HeaderFields {
        if (messageFirstLine == null) {
            throw new IllegalArgumentException("messageFirstLine must not be null (use \"\" for an empty message)");
        }
    }
}
