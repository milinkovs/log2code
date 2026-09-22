package org.log2code.analyzer.logging;

/** A field declared with a known logger type (T08 step 2): its API and resolved logger name/kind. */
record LoggerFieldInfo(String api, String loggerName, String loggerNameKind) {
}
