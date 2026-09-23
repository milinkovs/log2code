package org.log2code.analyzer.logging;

/** Known values of the catalog's {@code kind} field on {@code log2code-types} documents (0.7). */
public final class TypeKind {

    public static final String CLASS = "class";
    public static final String INTERFACE = "interface";
    public static final String ENUM = "enum";
    public static final String RECORD = "record";
    public static final String ANNOTATION = "annotation";

    private TypeKind() {
    }
}
