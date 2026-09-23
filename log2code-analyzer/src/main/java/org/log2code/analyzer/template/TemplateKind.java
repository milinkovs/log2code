package org.log2code.analyzer.template;

/** Known values of the catalog's {@code template_kind} field (0.7, T09 rules 1-9). */
public final class TemplateKind {

    public static final String LITERAL = "literal";
    public static final String PLACEHOLDERS = "placeholders";
    public static final String CONCAT = "concat";
    public static final String FORMAT = "format";
    public static final String MESSAGE_FORMAT = "message_format";
    public static final String SUPPLIER = "supplier";
    public static final String DYNAMIC = "dynamic";
    public static final String UNSUPPORTED = "unsupported";

    private TemplateKind() {
    }
}
