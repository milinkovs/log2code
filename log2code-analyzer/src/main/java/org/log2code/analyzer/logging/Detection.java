package org.log2code.analyzer.logging;

/** Known values of the catalog's {@code detection} field (0.7): how the logger's type was determined. */
public final class Detection {

    /** The logger's declared type (field or local variable) resolved directly to a known logging API. */
    public static final String TYPED = "typed";

    /** The logger field is declared on a superclass within the same code unit (T08 step 3, pass 2). */
    public static final String INHERITED = "inherited";

    /** No declaration was found; the receiver's name matched the common logger-naming pattern (T08 step 3). */
    public static final String HEURISTIC = "heuristic";

    private Detection() {
    }
}
