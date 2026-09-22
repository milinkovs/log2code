package org.log2code.analyzer.logging;

/** A known logger type: its {@link LoggingApi} constant and how to classify calls on it. */
record LoggerTypeSpec(String api, LogMethodMatcher matcher) {
}
