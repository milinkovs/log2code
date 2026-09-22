package org.log2code.core.model;

import java.util.Locale;

/**
 * Normalized log level. {@link #parse(String)} accepts SLF4J/Logback, Log4j2, JBoss
 * Logging and JUL level names; anything else resolves to {@link #UNKNOWN}.
 */
public enum Level {
    TRACE,
    DEBUG,
    INFO,
    WARN,
    ERROR,
    FATAL,
    UNKNOWN;

    public static Level parse(String value) {
        if (value == null) {
            return UNKNOWN;
        }
        String upper = value.trim().toUpperCase(Locale.ROOT);
        return switch (upper) {
            case "TRACE", "FINEST" -> TRACE;
            case "DEBUG", "FINER", "FINE" -> DEBUG;
            case "INFO", "CONFIG" -> INFO;
            case "WARN", "WARNING" -> WARN;
            case "ERROR", "SEVERE" -> ERROR;
            case "FATAL" -> FATAL;
            default -> UNKNOWN;
        };
    }

    /**
     * Whether an observed level is compatible with the level recorded in the catalog:
     * equal levels, FATAL/ERROR in either direction, or an unresolved catalog level.
     */
    public static boolean compatible(Level catalog, Level observed) {
        if (catalog == UNKNOWN || catalog == observed) {
            return true;
        }
        return (catalog == FATAL && observed == ERROR) || (catalog == ERROR && observed == FATAL);
    }
}
