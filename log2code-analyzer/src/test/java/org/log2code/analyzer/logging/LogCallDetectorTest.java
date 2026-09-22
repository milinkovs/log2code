package org.log2code.analyzer.logging;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.javaparser.ast.CompilationUnit;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.log2code.analyzer.ast.JavaSources;
import org.log2code.core.model.Level;

/**
 * Detection over the shared fixtures in {@code fixtures/java/logging/} (T08 Testovi): every fixture
 * file is one code unit's worth of sources, parsed and detected together so the JCL inherited-logger
 * fixture (spread across two classes in one file) exercises the real pass-1/pass-2 flow.
 */
class LogCallDetectorTest {

    private static final Path FIXTURES_DIR = Path.of("..", "fixtures", "java", "logging");

    private static Map<String, List<LogCall>> callsByFile;

    @BeforeAll
    static void parseAndDetect() {
        JavaSources.Result parsed = JavaSources.parseAll(FIXTURES_DIR);
        assertThat(parsed.failures()).as("fixture files that failed to parse").isEmpty();
        assertThat(parsed.files()).as("fixture files found under " + FIXTURES_DIR.toAbsolutePath()).isNotEmpty();

        List<CompilationUnit> units = parsed.files().stream().map(JavaSources.ParsedFile::unit).toList();
        List<List<LogCall>> perFile = LogCallDetector.detectAll(units);

        callsByFile = new LinkedHashMap<>();
        for (int i = 0; i < parsed.files().size(); i++) {
            String name = parsed.files().get(i).relativePath().toString().replace('\\', '/');
            callsByFile.put(name, perFile.get(i));
        }
    }

    private static List<LogCall> callsIn(String fileName) {
        List<LogCall> calls = callsByFile.get(fileName);
        assertThat(calls).as("calls detected in " + fileName).isNotNull();
        return calls;
    }

    @Test
    void slf4jPlainApi() {
        List<LogCall> calls = callsIn("Slf4jFixture.java");
        assertThat(calls).hasSize(7);
        assertThat(calls).allMatch(c -> c.api().equals(LoggingApi.SLF4J));
        assertThat(calls).allMatch(c -> c.detection().equals(Detection.TYPED));
        assertThat(calls).allMatch(c -> c.loggerName().equals("org.log2code.fixture.logging.Slf4jFixture"));
        assertThat(calls).allMatch(c -> c.loggerNameKind().equals(LoggerNameKind.CLASS_LITERAL));

        assertThat(calls.get(0).level()).isEqualTo(Level.INFO);
        assertThat(calls.get(0).throwableArg()).isNull();

        assertThat(calls.get(1).level()).isEqualTo(Level.DEBUG);
        assertThat(calls.get(2).level()).isEqualTo(Level.WARN);

        LogCall caughtException = calls.get(3);
        assertThat(caughtException.level()).isEqualTo(Level.ERROR);
        assertThat(caughtException.throwableArg()).isNotNull();
        assertThat(caughtException.throwableArg().toString()).isEqualTo("e");

        LogCall newException = calls.get(4);
        assertThat(newException.level()).isEqualTo(Level.ERROR);
        assertThat(newException.throwableArg()).isNotNull();
        assertThat(newException.throwableArg().toString()).contains("new IllegalArgumentException");

        LogCall notConfused = calls.get(5);
        assertThat(notConfused.level()).isEqualTo(Level.INFO);
        assertThat(notConfused.throwableArg()).as("1 hole matches the 1 extra arg: not a throwable").isNull();
        assertThat(notConfused.messageArgs()).hasSize(2);

        LogCall guardedDebug = calls.get(6);
        assertThat(guardedDebug.level()).isEqualTo(Level.DEBUG);
        assertThat(calls).noneMatch(c -> c.level() == Level.TRACE || "fatal".equals(c.node().getNameAsString()));
    }

    @Test
    void slf4jFluentApi() {
        List<LogCall> calls = callsIn("Slf4jFluentFixture.java");
        assertThat(calls).hasSize(3);
        assertThat(calls).allMatch(c -> c.api().equals(LoggingApi.SLF4J));
        assertThat(calls).allMatch(c -> c.detection().equals(Detection.TYPED));

        assertThat(calls.get(0).level()).isEqualTo(Level.INFO);
        assertThat(calls.get(0).throwableArg()).isNull();
        assertThat(calls.get(0).messageArgs()).hasSize(2); // format string + the one addArgument-equivalent .log() arg

        LogCall setMessage = calls.get(1);
        assertThat(setMessage.level()).isEqualTo(Level.WARN);
        assertThat(setMessage.messageArgs()).hasSize(1);
        assertThat(setMessage.throwableArg()).isNull();

        LogCall causeAndArgument = calls.get(2);
        assertThat(causeAndArgument.level()).isEqualTo(Level.ERROR);
        assertThat(causeAndArgument.messageArgs()).hasSize(2); // setMessage(...) + addArgument(...)
        assertThat(causeAndArgument.throwableArg()).isNotNull();
        assertThat(causeAndArgument.throwableArg().toString()).isEqualTo("cause");
    }

    @Test
    void jclApiAndInheritedLogger() {
        List<LogCall> calls = callsIn("JclFixture.java");
        assertThat(calls).hasSize(5);
        assertThat(calls).allMatch(c -> c.api().equals(LoggingApi.JCL));

        assertThat(calls.get(0).level()).isEqualTo(Level.INFO);
        assertThat(calls.get(0).detection()).isEqualTo(Detection.TYPED);

        LogCall withThrowable = calls.get(1);
        assertThat(withThrowable.level()).isEqualTo(Level.ERROR);
        assertThat(withThrowable.throwableArg()).isNotNull();
        assertThat(withThrowable.throwableArg().toString()).isEqualTo("ex");

        assertThat(calls.get(2).level()).isEqualTo(Level.FATAL);

        LogCall baseOwnField = calls.get(3);
        assertThat(baseOwnField.detection()).as("logged directly in the declaring class").isEqualTo(Detection.TYPED);
        assertThat(baseOwnField.loggerNameKind()).isEqualTo(LoggerNameKind.GET_CLASS);
        assertThat(baseOwnField.loggerName()).isEqualTo("org.log2code.fixture.logging.JclAbstractBase");
        assertThat(baseOwnField.level()).isEqualTo(Level.DEBUG);

        LogCall subclassInherited = calls.get(4);
        assertThat(subclassInherited.detection()).as("logger field is declared on the superclass").isEqualTo(Detection.INHERITED);
        assertThat(subclassInherited.loggerNameKind()).isEqualTo(LoggerNameKind.GET_CLASS);
        assertThat(subclassInherited.loggerName())
            .as("get_class: FQN of the class the field is *declared* in, not the caller")
            .isEqualTo("org.log2code.fixture.logging.JclAbstractBase");
        assertThat(subclassInherited.level()).isEqualTo(Level.INFO);
    }

    @Test
    void tomcatJuliApi() {
        List<LogCall> calls = callsIn("TomcatJuliFixture.java");
        assertThat(calls).hasSize(3);
        assertThat(calls).allMatch(c -> c.api().equals(LoggingApi.TOMCAT_JULI));
        assertThat(calls.get(0).level()).isEqualTo(Level.WARN);
        assertThat(calls.get(1).level()).isEqualTo(Level.ERROR);
        assertThat(calls.get(1).throwableArg()).isNotNull();
        assertThat(calls.get(2).level()).isEqualTo(Level.FATAL);
    }

    @Test
    void springLogAccessorApi() {
        List<LogCall> calls = callsIn("LogAccessorFixture.java");
        assertThat(calls).hasSize(3);
        assertThat(calls).allMatch(c -> c.api().equals(LoggingApi.SPRING_LOG_ACCESSOR));

        assertThat(calls.get(0).level()).isEqualTo(Level.INFO);
        assertThat(calls.get(0).throwableArg()).isNull();

        LogCall throwableFirst = calls.get(1);
        assertThat(throwableFirst.level()).isEqualTo(Level.ERROR);
        assertThat(throwableFirst.throwableArg()).as("LogAccessor: throwable is the FIRST argument").isNotNull();
        assertThat(throwableFirst.throwableArg().toString()).isEqualTo("cause");
        assertThat(throwableFirst.messageArgs().get(0).toString()).isEqualTo("\"log accessor failure\"");

        assertThat(calls.get(2).level()).isEqualTo(Level.FATAL);
    }

    @Test
    void julApi() {
        List<LogCall> calls = callsIn("JulFixture.java");
        assertThat(calls).hasSize(5);
        assertThat(calls).allMatch(c -> c.api().equals(LoggingApi.JUL));

        assertThat(calls.get(0).level()).isEqualTo(Level.INFO); // info(String) convenience method

        assertThat(calls.get(1).level()).isEqualTo(Level.WARN); // Level.WARNING -> WARN
        assertThat(calls.get(1).levelDynamic()).isFalse();

        LogCall withThrowable = calls.get(2);
        assertThat(withThrowable.level()).isEqualTo(Level.ERROR); // Level.SEVERE -> ERROR
        assertThat(withThrowable.throwableArg()).isNotNull();
        assertThat(withThrowable.throwableArg().toString()).isEqualTo("t");

        LogCall withPlaceholderParam = calls.get(3);
        assertThat(withPlaceholderParam.throwableArg()).as("a non-throwable-shaped Object param is not a throwable").isNull();
        assertThat(withPlaceholderParam.messageArgs()).hasSize(2);

        assertThat(calls.get(4).level()).isEqualTo(Level.DEBUG); // logp(Level.FINE, ...) -> FINE -> DEBUG
    }

    @Test
    void log4j2Api() {
        List<LogCall> calls = callsIn("Log4j2Fixture.java");
        assertThat(calls).hasSize(5);
        assertThat(calls).allMatch(c -> c.api().equals(LoggingApi.LOG4J2));

        assertThat(calls.get(0).level()).isEqualTo(Level.INFO);
        assertThat(calls.get(1).level()).isEqualTo(Level.DEBUG);

        LogCall withThrowable = calls.get(2);
        assertThat(withThrowable.level()).isEqualTo(Level.ERROR);
        assertThat(withThrowable.throwableArg()).isNotNull();
        assertThat(withThrowable.throwableArg().toString()).isEqualTo("exc");

        assertThat(calls.get(3).level()).isEqualTo(Level.FATAL);
        assertThat(calls.get(4).level()).isEqualTo(Level.WARN); // log(Level.WARN, ...) general form
    }

    @Test
    void systemLoggerApi() {
        List<LogCall> calls = callsIn("SystemLoggerFixture.java");
        assertThat(calls).hasSize(3);
        assertThat(calls).allMatch(c -> c.api().equals(LoggingApi.SYSTEM_LOGGER));

        assertThat(calls.get(0).level()).isEqualTo(Level.INFO);
        assertThat(calls.get(0).levelDynamic()).isFalse();

        LogCall withThrowable = calls.get(1);
        assertThat(withThrowable.level()).isEqualTo(Level.ERROR);
        assertThat(withThrowable.throwableArg()).isNotNull();

        LogCall dynamicLevel = calls.get(2);
        assertThat(dynamicLevel.levelDynamic()).as("a variable level is not statically known (step 4)").isTrue();
        assertThat(dynamicLevel.level()).isEqualTo(Level.UNKNOWN);
    }

    @Test
    void jbossLoggingApi() {
        List<LogCall> calls = callsIn("JbossLoggingFixture.java");
        assertThat(calls).hasSize(5);
        assertThat(calls).allMatch(c -> c.api().equals(LoggingApi.JBOSS_LOGGING));

        assertThat(calls.get(0).level()).isEqualTo(Level.INFO); // plain

        LogCall plainThrowable = calls.get(1);
        assertThat(plainThrowable.level()).isEqualTo(Level.ERROR);
        assertThat(plainThrowable.throwableArg()).isNotNull(); // fixed LAST position, unconditional

        LogCall formatStyle = calls.get(2); // infof(...), no throwable
        assertThat(formatStyle.level()).isEqualTo(Level.INFO);
        assertThat(formatStyle.throwableArg()).isNull();
        assertThat(formatStyle.messageArgs()).hasSize(2);

        LogCall formatStyleThrowable = calls.get(3); // errorf(t, ...)
        assertThat(formatStyleThrowable.level()).isEqualTo(Level.ERROR);
        assertThat(formatStyleThrowable.throwableArg()).isNotNull();
        assertThat(formatStyleThrowable.throwableArg().toString()).isEqualTo("t");

        assertThat(calls.get(4).level()).isEqualTo(Level.TRACE); // tracev(...), MessageFormat style
    }

    @Test
    void staticImportFactoryAndHeuristicFallback() {
        List<LogCall> calls = callsIn("StaticImportFixture.java");
        assertThat(calls).hasSize(2);

        LogCall viaStaticFactory = calls.get(0);
        assertThat(viaStaticFactory.api()).isEqualTo(LoggingApi.SLF4J);
        assertThat(viaStaticFactory.detection()).isEqualTo(Detection.TYPED);
        assertThat(viaStaticFactory.loggerNameKind()).isEqualTo(LoggerNameKind.CLASS_LITERAL);
        assertThat(viaStaticFactory.loggerName()).isEqualTo("org.log2code.fixture.logging.StaticImportFixture");

        LogCall heuristic = calls.get(1);
        assertThat(heuristic.api()).isEqualTo(LoggingApi.UNKNOWN);
        assertThat(heuristic.detection()).isEqualTo(Detection.HEURISTIC);
        assertThat(heuristic.loggerName()).isNull();
        assertThat(heuristic.loggerNameKind()).isEqualTo(LoggerNameKind.UNKNOWN);
        assertThat(heuristic.level()).isEqualTo(Level.INFO);
    }

    @Test
    void structuralVariety() {
        List<LogCall> calls = callsIn("StructuralVarietyFixture.java");
        assertThat(calls).hasSize(6);
        assertThat(calls).allMatch(c -> c.api().equals(LoggingApi.SLF4J));

        LogCall nested = calls.get(0);
        assertThat(nested.detection()).isEqualTo(Detection.TYPED);
        assertThat(nested.loggerName()).isEqualTo("org.log2code.fixture.logging.StructuralVarietyFixture.NestedStatic");
        assertThat(nested.inLambda()).isFalse();

        LogCall anonymousClassBody = calls.get(1);
        assertThat(anonymousClassBody.detection())
            .as("anonymous class body reaches the enclosing class's field").isEqualTo(Detection.TYPED);
        assertThat(anonymousClassBody.loggerName()).isEqualTo("org.log2code.fixture.logging.StructuralVarietyFixture");
        assertThat(anonymousClassBody.inLambda()).as("an overridden method, not a lambda").isFalse();

        LogCall lambdaBody = calls.get(2);
        assertThat(lambdaBody.inLambda()).isTrue();
        assertThat(lambdaBody.detection()).isEqualTo(Detection.TYPED);
        assertThat(lambdaBody.loggerName()).isEqualTo("org.log2code.fixture.logging.StructuralVarietyFixture");

        LogCall varLocal = calls.get(3);
        assertThat(varLocal.detection()).isEqualTo(Detection.TYPED);
        assertThat(varLocal.loggerName()).isEqualTo("org.log2code.fixture.logging.StructuralVarietyFixture");
        assertThat(varLocal.loggerNameKind()).isEqualTo(LoggerNameKind.CLASS_LITERAL);

        LogCall enumBody = calls.get(4);
        assertThat(enumBody.loggerName()).isEqualTo("org.log2code.fixture.logging.StructuralVarietyFixture.Status");

        LogCall recordBody = calls.get(5);
        assertThat(recordBody.loggerName()).isEqualTo("org.log2code.fixture.logging.StructuralVarietyFixture.Summary");
    }
}
