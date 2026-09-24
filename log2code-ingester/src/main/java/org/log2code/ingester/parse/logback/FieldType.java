package org.log2code.ingester.parse.logback;

/** The HeaderFields slots a compiled Logback conversion word can capture into. */
enum FieldType {
    TIMESTAMP,
    LEVEL,
    THREAD,
    LOGGER,
    CLASS,
    METHOD,
    LINE,
    MESSAGE,
    MDC,
    PID
}
