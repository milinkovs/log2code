package org.log2code.core.model;

/** A return/throw/continue/break that could bypass a log statement (level 2, T11). */
public record EarlyExit(String kind, String expression, int line) {
}
