package org.log2code.ingester.parse;

/**
 * A user/config error in {@code config/log-formats.yml} or in a Logback pattern it references:
 * unknown format, unknown preset, unsupported conversion word, malformed pattern syntax.
 */
public final class LogFormatException extends RuntimeException {

    public LogFormatException(String message) {
        super(message);
    }

    public LogFormatException(String message, Throwable cause) {
        super(message, cause);
    }
}
