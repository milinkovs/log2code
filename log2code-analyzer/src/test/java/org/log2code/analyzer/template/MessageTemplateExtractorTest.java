package org.log2code.analyzer.template;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.log2code.analyzer.ast.JavaSources;
import org.log2code.analyzer.logging.LogCall;
import org.log2code.analyzer.logging.LogCallDetector;

/**
 * T09: for each Java expression, the expected normalized template (0.9/T06 form) and
 * {@link TemplateKind}. Covers rules 1-9 (literal/placeholders, concatenation, constants, format,
 * message_format, supplier, StringBuilder chain, dynamic, unsupported), text blocks, escapes, and the
 * realistic Spring patterns named in the task text.
 */
class MessageTemplateExtractorTest {

    static {
        StaticJavaParser.getConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21);
    }

    private static ExtractedTemplate extractOnly(String source) {
        CompilationUnit unit = StaticJavaParser.parse(source);
        List<LogCall> calls = LogCallDetector.detectInSingleUnit(unit);
        assertThat(calls).as("exactly one detected log call in:%n%s", source).hasSize(1);
        ConstantIndex constants = ConstantIndex.build(List.of(unit));
        return MessageTemplateExtractor.extract(calls.get(0), constants);
    }

    // --- Wrapping helpers: one minimal, compilable class per logging API -------------------------

    private static String slf4j(String stmt) {
        return slf4jImport("", stmt);
    }

    /** {@code extraImport}: an additional top-level {@code import ...;} line (or empty). */
    private static String slf4jImport(String extraImport, String stmt) {
        return """
            package org.log2code.fixture.template;
            import org.slf4j.Logger;
            import org.slf4j.LoggerFactory;
            %s
            class Fixture {
                private static final Logger log = LoggerFactory.getLogger(Fixture.class);
                void run(Object x, Object y, Throwable t) {
                    %s
                }
            }
            """.formatted(extraImport, stmt);
    }

    /** {@code extraField}: an additional field declaration inside the class body. */
    private static String slf4jField(String extraField, String stmt) {
        return """
            package org.log2code.fixture.template;
            import org.slf4j.Logger;
            import org.slf4j.LoggerFactory;
            class Fixture {
                private static final Logger log = LoggerFactory.getLogger(Fixture.class);
                %s
                void run(Object x, Object y, Throwable t) {
                    %s
                }
            }
            """.formatted(extraField, stmt);
    }

    private static String log4j2(String stmt) {
        return """
            package org.log2code.fixture.template;
            import org.apache.logging.log4j.Logger;
            import org.apache.logging.log4j.LogManager;
            class Fixture {
                private static final Logger log = LogManager.getLogger(Fixture.class);
                void run(Object x, Object y, Throwable t) {
                    %s
                }
            }
            """.formatted(stmt);
    }

    private static String jboss(String stmt) {
        return """
            package org.log2code.fixture.template;
            import org.jboss.logging.Logger;
            class Fixture {
                private static final Logger log = Logger.getLogger(Fixture.class);
                void run(Object x, Object y, Throwable t) {
                    %s
                }
            }
            """.formatted(stmt);
    }

    private static String jul(String stmt) {
        return """
            package org.log2code.fixture.template;
            import java.util.logging.Level;
            import java.util.logging.Logger;
            class Fixture {
                private static final Logger log = Logger.getLogger(Fixture.class.getName());
                void run(Object x, Object y, Throwable t) {
                    %s
                }
            }
            """.formatted(stmt);
    }

    private static String jcl(String stmt) {
        return """
            package org.log2code.fixture.template;
            import org.apache.commons.logging.Log;
            import org.apache.commons.logging.LogFactory;
            class Fixture {
                private static final Log log = LogFactory.getLog(Fixture.class);
                void run(Object x, Object y, Throwable t) {
                    %s
                }
            }
            """.formatted(stmt);
    }

    private static String tomcatJuli(String stmt) {
        return """
            package org.log2code.fixture.template;
            import org.apache.juli.logging.Log;
            import org.apache.juli.logging.LogFactory;
            class Fixture {
                private static final Log log = LogFactory.getLog(Fixture.class);
                void run() {
                    %s
                }
            }
            """.formatted(stmt);
    }

    private static String logAccessor(String stmt) {
        return """
            package org.log2code.fixture.template;
            import org.springframework.core.log.LogAccessor;
            class Fixture {
                private static final LogAccessor log = new LogAccessor(Fixture.class);
                void run(Throwable t) {
                    %s
                }
            }
            """.formatted(stmt);
    }

    private static String systemLogger(String stmt) {
        return """
            package org.log2code.fixture.template;
            import java.lang.System.Logger.Level;
            class Fixture {
                private final System.Logger log = System.getLogger(Fixture.class.getName());
                void run() {
                    %s
                }
            }
            """.formatted(stmt);
    }

    // --- Group 1: rule 1 - string literal / text block, SLF4J-family "{}" holes and escapes ------

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("bracesLiteralCases")
    void rule1BracesLiteral(String label, String source, String expectedKind, String expectedTemplate) {
        ExtractedTemplate result = extractOnly(source);
        assertThat(result.templateKind()).as(label).isEqualTo(expectedKind);
        assertThat(result.template().toNormalized()).as(label).isEqualTo(expectedTemplate);
    }

    static Stream<Arguments> bracesLiteralCases() {
        return Stream.of(
            Arguments.of("slf4j plain literal, no holes",
                slf4j("log.info(\"starting up\");"), TemplateKind.LITERAL, "starting up"),
            Arguments.of("slf4j one placeholder",
                slf4j("log.debug(\"processing {}\", x);"), TemplateKind.PLACEHOLDERS, "processing {}"),
            Arguments.of("slf4j two placeholders",
                slf4j("log.info(\"{} and {}\", x, y);"), TemplateKind.PLACEHOLDERS, "{} and {}"),
            Arguments.of("slf4j escaped brace stays literal (SLF4J \\{} escape, T06 rule)",
                slf4j("log.info(\"literal \\\\{} brace\");"), TemplateKind.LITERAL, "literal \\{} brace"),
            Arguments.of("slf4j text block literal",
                slf4j("log.info(\"\"\"\n                        multi\n                        line\"\"\");"),
                TemplateKind.LITERAL, "multi\nline"),
            Arguments.of("slf4j fluent api with addArgument",
                slf4j("log.atInfo().setMessage(\"fluent {} msg\").addArgument(x).log();"),
                TemplateKind.PLACEHOLDERS, "fluent {} msg"),
            Arguments.of("slf4j fluent api terminal call with placeholder",
                slf4j("log.atDebug().log(\"terminal {}\", x);"), TemplateKind.PLACEHOLDERS, "terminal {}"),
            Arguments.of("log4j2 plain literal",
                log4j2("log.info(\"log4j2 info\");"), TemplateKind.LITERAL, "log4j2 info"),
            Arguments.of("log4j2 placeholder",
                log4j2("log.debug(\"log4j2 processing {}\", x);"), TemplateKind.PLACEHOLDERS, "log4j2 processing {}"),
            Arguments.of("jboss plain (no f/v suffix) literal",
                jboss("log.info(\"jboss plain info\");"), TemplateKind.LITERAL, "jboss plain info"),
            Arguments.of("jboss plain placeholder",
                jboss("log.debug(\"jboss {} debug\", x);"), TemplateKind.PLACEHOLDERS, "jboss {} debug")
        );
    }

    // --- Group 2: other APIs - no "{}" placeholder syntax at all (NONE style) --------------------

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("noneStyleCases")
    void nonBracesApisTreatBracesAsLiteralText(String label, String source, String expectedTemplate) {
        ExtractedTemplate result = extractOnly(source);
        assertThat(result.templateKind()).as(label).isEqualTo(TemplateKind.LITERAL);
        assertThat(result.template().toNormalized()).as(label).isEqualTo(expectedTemplate);
    }

    static Stream<Arguments> noneStyleCases() {
        return Stream.of(
            Arguments.of("jcl message", jcl("log.info(\"starting\");"), "starting"),
            Arguments.of("jcl message containing braces stays literal (no SLF4J holes)",
                jcl("log.warn(\"value is {} literally\");"), "value is \\{} literally"),
            Arguments.of("tomcat juli message", tomcatJuli("log.warn(\"tomcat juli warning\");"), "tomcat juli warning"),
            Arguments.of("spring LogAccessor message", logAccessor("log.info(\"log accessor message\");"),
                "log accessor message"),
            Arguments.of("system logger message", systemLogger("log.log(Level.INFO, \"system logger info\");"),
                "system logger info"),
            Arguments.of("jul convenience method, no params: {0} stays literal",
                jul("log.info(\"value is {0}\");"), "value is \\{0}")
        );
    }

    // --- Group 3: rule 2 - concatenation -----------------------------------------------------------

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("concatCases")
    void rule2Concatenation(String label, String source, String expectedTemplate) {
        ExtractedTemplate result = extractOnly(source);
        assertThat(result.templateKind()).as(label).isEqualTo(TemplateKind.CONCAT);
        assertThat(result.template().toNormalized()).as(label).isEqualTo(expectedTemplate);
    }

    static Stream<Arguments> concatCases() {
        return Stream.of(
            Arguments.of("logger.debug(\"Mapped to \" + handler) style",
                slf4j("log.debug(\"Mapped to \" + x);"), "Mapped to {}"),
            Arguments.of("logger.info(\"Started \" + name + \" in \" + t + \" seconds\") style",
                slf4j("log.info(\"Started \" + x + \" in \" + y + \" seconds\");"), "Started {} in {} seconds"),
            Arguments.of("two adjacent literals concatenated",
                slf4j("log.info(\"a\" + \"b\");"), "ab"),
            Arguments.of("numeric literal term becomes literal text",
                slf4j("log.info(\"count: \" + 5);"), "count: 5"),
            Arguments.of("char literal term becomes literal text",
                slf4j("log.info(\"sep=\" + 'x');"), "sep=x"),
            Arguments.of("non-literal term becomes a hole even under NONE style",
                jcl("log.info(\"value=\" + x);"), "value={}"),
            Arguments.of("literal fragment inside concat still gets brace-hole scanning",
                slf4j("log.info(\"pre {} mid\" + x);"), "pre {} mid{}")
        );
    }

    @Test
    void concatWithNoLiteralAtAllIsStillConcatNotDynamic() {
        // rule 2 always yields "concat", even when literalLength() ends up 0 - "dynamic" is reserved
        // for rule 8's own single-hole fallback (0.10 relies on this: "dynamic (samo {})"). The two
        // holes merge into one in the NORMALIZED template (adjacent holes always merge, T06), so "{}"
        // alone does not distinguish this from a true "dynamic" result - only templateKind() does.
        ExtractedTemplate result = extractOnly(slf4j("log.info(x + y);"));
        assertThat(result.templateKind()).isEqualTo(TemplateKind.CONCAT);
        assertThat(result.template().toNormalized()).isEqualTo("{}");
    }

    // --- Group 4: rule 3 - static final String constants -------------------------------------------

    @Test
    void sameClassConstantIsSubstituted() {
        String source = slf4jField(
            "private static final String PREFIX = \"Saving \";",
            "log.info(PREFIX + x);");
        ExtractedTemplate result = extractOnly(source);
        assertThat(result.templateKind()).isEqualTo(TemplateKind.CONCAT);
        assertThat(result.template().toNormalized()).isEqualTo("Saving {}");
    }

    @Test
    void transitiveConstantOfConstantIsSubstituted() {
        String source = slf4jField(
            """
            private static final String BASE = "root";
            private static final String PREFIX = BASE + "/child ";
            """,
            "log.info(PREFIX + x);");
        ExtractedTemplate result = extractOnly(source);
        assertThat(result.template().toNormalized()).isEqualTo("root/child {}");
    }

    @Test
    void constantReferencedAsSoleTopLevelMessage() {
        String source = slf4jField(
            "private static final String MSG = \"fixed message\";",
            "log.info(MSG);");
        ExtractedTemplate result = extractOnly(source);
        assertThat(result.templateKind()).isEqualTo(TemplateKind.LITERAL);
        assertThat(result.template().toNormalized()).isEqualTo("fixed message");
    }

    @Test
    void nonLiteralConstantInitializerBecomesHoleNotPartialSubstitution() {
        // PREFIX's own initializer is not literal-or-concat-of-literal (it calls a method), so the
        // whole reference becomes ONE hole - not a partial substitution.
        String source = slf4jField(
            "private static final String PREFIX = String.valueOf(System.currentTimeMillis());",
            "log.info(PREFIX + \" done\");");
        ExtractedTemplate result = extractOnly(source);
        assertThat(result.template().toNormalized()).isEqualTo("{} done");
    }

    @Test
    void unresolvedNameFallsBackToHoleLikeAnyOtherVariable() {
        String source = slf4j("log.info(SOME_UNDECLARED_NAME + \" x\");");
        ExtractedTemplate result = extractOnly(source);
        assertThat(result.template().toNormalized()).isEqualTo("{} x");
    }

    @Test
    void importedClassConstantInSameCodeUnitIsSubstituted() {
        String other = """
            package org.log2code.fixture.template;
            class Constants {
                static final String OWNER_PREFIX = "Owner: ";
            }
            """;
        String main = slf4jImport(
            "import org.log2code.fixture.template.Constants;",
            "log.info(Constants.OWNER_PREFIX + x);");
        CompilationUnit mainUnit = StaticJavaParser.parse(main);
        CompilationUnit otherUnit = StaticJavaParser.parse(other);
        List<LogCall> calls = LogCallDetector.detectAll(List.of(mainUnit, otherUnit)).get(0);
        assertThat(calls).hasSize(1);
        ConstantIndex constants = ConstantIndex.build(List.of(mainUnit, otherUnit));
        ExtractedTemplate result = MessageTemplateExtractor.extract(calls.get(0), constants);
        assertThat(result.template().toNormalized()).isEqualTo("Owner: {}");
    }

    @Test
    void staticImportedConstantIsSubstituted() {
        // OWNER_PREFIX is declared only in "other" (not as a field of Fixture itself), so resolution
        // must go through the static-import branch, not the enclosing-class search.
        String other = """
            package org.log2code.fixture.template;
            class Constants {
                static final String OWNER_PREFIX = "Owner: ";
            }
            """;
        String main = slf4jImport(
            "import static org.log2code.fixture.template.Constants.OWNER_PREFIX;",
            "log.info(OWNER_PREFIX + x);");
        CompilationUnit mainUnit = StaticJavaParser.parse(main);
        CompilationUnit otherUnit = StaticJavaParser.parse(other);
        List<LogCall> calls = LogCallDetector.detectAll(List.of(mainUnit, otherUnit)).get(0);
        assertThat(calls).hasSize(1);
        ConstantIndex constants = ConstantIndex.build(List.of(mainUnit, otherUnit));
        ExtractedTemplate result = MessageTemplateExtractor.extract(calls.get(0), constants);
        assertThat(result.template().toNormalized()).isEqualTo("Owner: {}");
    }

    // --- Group 5: rule 4 - String.format / .formatted / LogMessage.format / JBoss *f --------------

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("formatCases")
    void rule4Format(String label, String source, String expectedTemplate) {
        ExtractedTemplate result = extractOnly(source);
        assertThat(result.templateKind()).as(label).isEqualTo(TemplateKind.FORMAT);
        assertThat(result.template().toNormalized()).as(label).isEqualTo(expectedTemplate);
    }

    static Stream<Arguments> formatCases() {
        return Stream.of(
            Arguments.of("String.format with %s twice",
                slf4j("log.info(String.format(\"Bean '%s' of type [%s] is not eligible\", x, y));"),
                "Bean '{}' of type [{}] is not eligible"),
            Arguments.of("String.format with width/precision/conversion",
                slf4j("log.info(String.format(\"value=%5.2f\", x));"), "value={}"),
            Arguments.of("String.format with indexed argument",
                slf4j("log.info(String.format(\"%1$s repeated\", x));"), "{} repeated"),
            Arguments.of("String.format %% and %n",
                // %n is trailing whitespace in the final template, so it is trimmed by normalization
                // (T06) just like a literal trailing space would be - text after it proves the
                // conversion still happened (a literal "%n" would leave "n" as a visible letter).
                slf4j("log.info(String.format(\"100%% done%n more\"));"), "100% done\n more"),
            Arguments.of(".formatted() on a string literal",
                slf4j("log.info(\"count=%d\".formatted(x));"), "count={}"),
            Arguments.of("LogMessage.format style",
                slf4jImport("import org.springframework.core.log.LogMessage;",
                    "log.trace(LogMessage.format(\"Bean '%s' of type [%s] is not eligible for getting processed by all BeanPostProcessors\", x, y));"),
                "Bean '{}' of type [{}] is not eligible for getting processed by all BeanPostProcessors"),
            Arguments.of("jboss *f format style",
                jboss("log.infof(\"jboss format style: %s\", x);"), "jboss format style: {}"),
            Arguments.of("jboss *f with no specifiers is still kind=format",
                jboss("log.errorf(\"plain jboss errorf text\");"), "plain jboss errorf text")
        );
    }

    // --- Group 6: rule 5 - MessageFormat (JUL with params, JBoss *v) -------------------------------

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("messageFormatCases")
    void rule5MessageFormat(String label, String source, String expectedTemplate) {
        ExtractedTemplate result = extractOnly(source);
        assertThat(result.templateKind()).as(label).isEqualTo(TemplateKind.MESSAGE_FORMAT);
        assertThat(result.template().toNormalized()).as(label).isEqualTo(expectedTemplate);
    }

    static Stream<Arguments> messageFormatCases() {
        return Stream.of(
            Arguments.of("jul log(Level, String, Object) with one param",
                jul("log.log(Level.INFO, \"value is {0}\", x);"), "value is {}"),
            Arguments.of("jul log with {1,number} style param (real JUL takes an Object[] for >1 param)",
                jul("log.log(Level.WARNING, \"count {0} of {1,number}\", new Object[] {x, y});"), "count {} of {}"),
            Arguments.of("jul logp with 5 args (message + one param)",
                jul("log.logp(Level.INFO, \"C\", \"m\", \"value {0}\", x);"), "value {}"),
            Arguments.of("jboss *v message_format style",
                jboss("log.infov(\"jboss {0} value\", x);"), "jboss {} value"),
            Arguments.of("MessageFormat apostrophe escape",
                jul("log.log(Level.INFO, \"it''s {0}\", x);"), "it's {}")
        );
    }

    @Test
    void julWithoutExtraParamKeepsBraceTextLiteralEvenWithLogMethod() {
        // log(Level, String) with no third argument: no MessageFormat substitution happens at
        // runtime, so this must be "literal", not "message_format".
        ExtractedTemplate result = extractOnly(jul("log.log(Level.INFO, \"value is {0}\");"));
        assertThat(result.templateKind()).isEqualTo(TemplateKind.LITERAL);
        assertThat(result.template().toNormalized()).isEqualTo("value is \\{0}");
    }

    // --- Group 7: rule 6 - Supplier / lambda --------------------------------------------------------

    @Test
    void bareLambdaExpressionBodySupplier() {
        ExtractedTemplate result = extractOnly(slf4j("log.info(() -> \"computed \" + x);"));
        assertThat(result.templateKind()).isEqualTo(TemplateKind.SUPPLIER);
        assertThat(result.template().toNormalized()).isEqualTo("computed {}");
    }

    @Test
    void lambdaWithBlockBodySingleReturnSupplier() {
        ExtractedTemplate result = extractOnly(slf4j("log.info(() -> { return \"block body msg\"; });"));
        assertThat(result.templateKind()).isEqualTo(TemplateKind.SUPPLIER);
        assertThat(result.template().toNormalized()).isEqualTo("block body msg");
    }

    @Test
    void logMessageOfWrappingLambdaIsSupplier() {
        // logger.trace(LogMessage.of(() -> "..." + x)) - the exact pattern named in the task text.
        String source = slf4jImport("import org.springframework.core.log.LogMessage;",
            "log.trace(LogMessage.of(() -> \"lazy \" + x));");
        ExtractedTemplate result = extractOnly(source);
        assertThat(result.templateKind()).isEqualTo(TemplateKind.SUPPLIER);
        assertThat(result.template().toNormalized()).isEqualTo("lazy {}");
    }

    @Test
    void julSupplierUsesNoneStyleInsideToo() {
        ExtractedTemplate result = extractOnly(jul("log.info(() -> \"value {0} literal\");"));
        assertThat(result.templateKind()).isEqualTo(TemplateKind.SUPPLIER);
        assertThat(result.template().toNormalized()).isEqualTo("value \\{0} literal");
    }

    @Test
    void lambdaWithMultiStatementBlockBodyFallsBackToDynamic() {
        // not "return expr" or a single expression: rule 6's pattern doesn't apply, so the whole
        // lambda becomes rule 8's fallback hole rather than a partially-understood supplier.
        String source = slf4j(
            "log.info(() -> { String s = \"a\"; System.out.println(s); return s; });");
        ExtractedTemplate result = extractOnly(source);
        assertThat(result.templateKind()).isEqualTo(TemplateKind.DYNAMIC);
        assertThat(result.template().toNormalized()).isEqualTo("{}");
    }

    // --- Group 8: rule 7 - StringBuilder chain --------------------------------------------------------

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("stringBuilderCases")
    void rule7StringBuilderChain(String label, String source, String expectedTemplate) {
        ExtractedTemplate result = extractOnly(source);
        assertThat(result.templateKind()).as(label).isEqualTo(TemplateKind.CONCAT);
        assertThat(result.template().toNormalized()).as(label).isEqualTo(expectedTemplate);
    }

    static Stream<Arguments> stringBuilderCases() {
        return Stream.of(
            Arguments.of("new StringBuilder(\"a\").append(x).append(\"b\").toString()",
                slf4j("log.info(new StringBuilder(\"a\").append(x).append(\"b\").toString());"), "a{}b"),
            Arguments.of("new StringBuilder().append(\"only\").toString()",
                slf4j("log.info(new StringBuilder().append(\"only\").toString());"), "only"),
            Arguments.of("int-capacity constructor arg is not treated as content",
                slf4j("log.info(new StringBuilder(64).append(\"cap\").append(x).toString());"), "cap{}"),
            Arguments.of("StringBuilder append with brace-holding literal still brace-scanned",
                slf4j("log.info(new StringBuilder(\"a {} b\").append(x).toString());"), "a {} b{}")
        );
    }

    // --- Group 9: rule 8 - ternary and everything else -> dynamic -------------------------------------

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("dynamicCases")
    void rule8Dynamic(String label, String source) {
        ExtractedTemplate result = extractOnly(source);
        assertThat(result.templateKind()).as(label).isEqualTo(TemplateKind.DYNAMIC);
        assertThat(result.template().toNormalized()).as(label).isEqualTo("{}");
        assertThat(result.template().isDynamicOnly()).as(label).isTrue();
    }

    static Stream<Arguments> dynamicCases() {
        return Stream.of(
            Arguments.of("bare variable as the whole message", slf4j("log.info(x.toString());")),
            Arguments.of("ternary expression", slf4j("log.info(x != null ? \"a\" : \"b\");")),
            Arguments.of("method call result used directly as message", slf4j("log.info(String.valueOf(x));"))
        );
    }

    // --- Group 10: rule 9 - unsupported ----------------------------------------------------------------

    @Test
    void tomcatStringManagerIsUnsupported() {
        String source = """
            package org.log2code.fixture.template;
            import org.apache.juli.logging.Log;
            import org.apache.juli.logging.LogFactory;
            import org.apache.tomcat.util.res.StringManager;
            class Fixture {
                private static final Log log = LogFactory.getLog(Fixture.class);
                private static final StringManager sm = StringManager.getManager(Fixture.class);
                void run() {
                    log.warn(sm.getString("fixture.key"));
                }
            }
            """;
        ExtractedTemplate result = extractOnly(source);
        assertThat(result.templateKind()).isEqualTo(TemplateKind.UNSUPPORTED);
        assertThat(result.unsupportedReason()).isEqualTo(UnsupportedReason.TOMCAT_STRING_MANAGER);
        assertThat(result.template()).isNull();
        assertThat(result.templateRaw()).contains("sm.getString");
    }

    // --- Group 11: throwable arguments are never part of the template (rule 10) --------------------

    @Test
    void throwableArgumentIsNeverPartOfTheTemplate() {
        ExtractedTemplate result = extractOnly(slf4j("log.error(\"failure processing {}\", x, t);"));
        assertThat(result.templateKind()).isEqualTo(TemplateKind.PLACEHOLDERS);
        assertThat(result.template().toNormalized()).isEqualTo("failure processing {}");
        assertThat(result.template().holeCount()).isEqualTo(1);
    }

    // --- Group 12: placeholderCount / templateRaw sanity -------------------------------------------

    @Test
    void placeholderCountMatchesHoleCount() {
        ExtractedTemplate result = extractOnly(slf4j("log.info(\"{} and {} and {}\", x, y, x);"));
        assertThat(result.placeholderCount()).isEqualTo(3);
    }

    @Test
    void templateRawIsTheMessageArgumentSourceText() {
        ExtractedTemplate result = extractOnly(slf4j("log.info(\"Started \" + x + \" in \" + y + \" seconds\");"));
        assertThat(result.templateRaw()).isEqualTo("\"Started \" + x + \" in \" + y + \" seconds\"");
    }

    // --- Group 13: AC3 - a parse error in one file does not stop extraction for the rest -----------

    @Test
    void parseErrorInOneFileDoesNotStopExtractionForTheOthers(@TempDir Path sourceRoot) throws IOException {
        Files.writeString(sourceRoot.resolve("Good1.java"), """
            package org.log2code.fixture.template;
            import org.slf4j.Logger;
            import org.slf4j.LoggerFactory;
            class Good1 {
                private static final Logger log = LoggerFactory.getLogger(Good1.class);
                void run() {
                    log.info("first ok");
                }
            }
            """);
        Files.writeString(sourceRoot.resolve("Good2.java"), """
            package org.log2code.fixture.template;
            import org.slf4j.Logger;
            import org.slf4j.LoggerFactory;
            class Good2 {
                private static final Logger log = LoggerFactory.getLogger(Good2.class);
                void run(Object x) {
                    log.info("second {}", x);
                }
            }
            """);
        Files.writeString(sourceRoot.resolve("Broken.java"), "package org.log2code.fixture.template; class {{{ not valid java");

        JavaSources.Result parsed = JavaSources.parseAll(sourceRoot);
        assertThat(parsed.files()).hasSize(2);
        assertThat(parsed.failures()).hasSize(1);
        assertThat(parsed.failures().get(0).relativePath().toString()).isEqualTo("Broken.java");

        List<CompilationUnit> units = new ArrayList<>();
        for (JavaSources.ParsedFile file : parsed.files()) {
            units.add(file.unit());
        }
        List<List<LogCall>> perFile = LogCallDetector.detectAll(units);
        ConstantIndex constants = ConstantIndex.build(units);

        List<ExtractedTemplate> extracted = new ArrayList<>();
        for (List<LogCall> calls : perFile) {
            for (LogCall call : calls) {
                extracted.add(MessageTemplateExtractor.extract(call, constants));
            }
        }
        assertThat(extracted).hasSize(2);
        assertThat(extracted.stream().map(e -> e.template().toNormalized()))
            .containsExactlyInAnyOrder("first ok", "second {}");
    }
}
