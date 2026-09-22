package org.log2code.analyzer.logging;

/** Known values of the catalog's {@code logger_name_kind} field (0.7). */
public final class LoggerNameKind {

    /** {@code LoggerFactory.getLogger(X.class)} and similar: {@code logger_name} is the FQN of {@code X}. */
    public static final String CLASS_LITERAL = "class_literal";

    /** {@code getLogger("name")} or a same-class {@code static final String} constant. */
    public static final String STRING = "string";

    /** {@code getClass()} / {@code this.getClass()}: {@code logger_name} is the FQN of the declaring class. */
    public static final String GET_CLASS = "get_class";

    public static final String UNKNOWN = "unknown";

    private LoggerNameKind() {
    }
}
