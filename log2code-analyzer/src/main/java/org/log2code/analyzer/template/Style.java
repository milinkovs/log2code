package org.log2code.analyzer.template;

/**
 * How to scan a literal fragment's text for placeholders (T09 rules 1, 4, 5): which characters are
 * special, and which {@link TemplateKind} a plain-literal fragment resolves to.
 */
enum Style {

    /** No placeholder syntax at all (JCL, Tomcat juli, Spring {@code LogAccessor}, System.Logger, JUL without params): the whole text is literal. */
    NONE,

    /** SLF4J, Log4j2, JBoss plain (no {@code f}/{@code v} suffix): {@code {}} is a hole, SLF4J escapes apply (rule 1). */
    BRACES,

    /** {@code String.format}/{@code .formatted}/{@code LogMessage.format}, JBoss {@code *f}: {@code %}-conversions are holes (rule 4). */
    FORMAT,

    /** JUL with parameters, JBoss {@code *v}: {@code {n}}-style placeholders are holes (rule 5). */
    MESSAGE_FORMAT
}
