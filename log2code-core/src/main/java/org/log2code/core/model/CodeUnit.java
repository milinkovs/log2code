package org.log2code.core.model;

/**
 * A unit of code at a specific version: either the analyzed project or one of its
 * dependencies. {@code type} is {@code "project"} or {@code "dependency"}.
 */
public record CodeUnit(String type, String name, String version) {

    public static final String TYPE_PROJECT = "project";
    public static final String TYPE_DEPENDENCY = "dependency";
}
