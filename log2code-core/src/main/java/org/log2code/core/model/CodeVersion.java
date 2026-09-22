package org.log2code.core.model;

/** The code version a log event was produced by, read from the dataset manifest. */
public record CodeVersion(String name, String version) {
}
