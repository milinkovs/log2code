package org.log2code.core.model;

/** A statement executed before a log statement in the same block (level 2, T11). */
public record PrecedingStatement(String kind, String text, int line) {
}
