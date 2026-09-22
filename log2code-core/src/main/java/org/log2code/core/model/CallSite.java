package org.log2code.core.model;

/** A method call reached before a log statement in the same method (level 2, T11). */
public record CallSite(String text, int line) {
}
