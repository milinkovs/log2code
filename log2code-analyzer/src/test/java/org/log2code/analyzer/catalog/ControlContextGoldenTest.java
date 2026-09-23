package org.log2code.analyzer.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.javaparser.ast.CompilationUnit;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.log2code.analyzer.ast.JavaSources;
import org.log2code.analyzer.logging.LogCall;
import org.log2code.analyzer.logging.LogCallDetector;
import org.log2code.core.json.Json;
import org.log2code.core.model.ControlContext;

/**
 * T11 AC1: golden tests over the real fixture methods in
 * {@code fixtures/java/control/ControlContextFixture.java} (one log call per method), each covering one
 * of the required scenarios: nested if/else, a guard clause, a log in {@code catch}, a loop with
 * {@code break}, a {@code switch} (a matched case and the {@code default} case), a log in a lambda, and
 * a log at the very start of a method (empty {@code preceding}). Compares the full serialized {@link
 * ControlContext} against {@code src/test/resources/golden/control/<method>.json}, same pattern as
 * {@code GoldenJsonTest} in log2code-core.
 */
class ControlContextGoldenTest {

    private static final Path FIXTURES_DIR = Path.of("..", "fixtures", "java", "control");
    private static final int MAX_PRECEDING_STATEMENTS = 10;

    private static Map<String, LogCall> callsByMethod;
    private final ObjectMapper mapper = Json.mapper();

    @BeforeAll
    static void parseAndDetect() {
        JavaSources.Result parsed = JavaSources.parseAll(FIXTURES_DIR);
        assertThat(parsed.failures()).as("fixture files that failed to parse").isEmpty();
        List<CompilationUnit> units = parsed.files().stream().map(JavaSources.ParsedFile::unit).toList();
        List<List<LogCall>> perFile = LogCallDetector.detectAll(units);

        callsByMethod = new LinkedHashMap<>();
        for (List<LogCall> calls : perFile) {
            for (LogCall call : calls) {
                String methodName = MethodContextResolver.resolve(call.node()).methodName();
                LogCall previous = callsByMethod.put(methodName, call);
                assertThat(previous).as("more than one log call in method " + methodName).isNull();
            }
        }
    }

    @Test
    void logAtMethodStart() throws Exception {
        assertGolden("logAtMethodStart");
    }

    @Test
    void guardClause() throws Exception {
        assertGolden("guardClause");
    }

    @Test
    void nestedIfElse() throws Exception {
        assertGolden("nestedIfElse");
    }

    @Test
    void logInCatch() throws Exception {
        assertGolden("logInCatch");
    }

    @Test
    void loopWithBreak() throws Exception {
        assertGolden("loopWithBreak");
    }

    @Test
    void switchCase() throws Exception {
        assertGolden("switchCase");
    }

    @Test
    void switchDefaultCase() throws Exception {
        assertGolden("switchDefaultCase");
    }

    @Test
    void logInLambda() throws Exception {
        assertGolden("logInLambda");
    }

    private void assertGolden(String methodName) throws Exception {
        LogCall call = callsByMethod.get(methodName);
        assertThat(call).as("log call in fixture method " + methodName).isNotNull();
        MethodContext methodContext = MethodContextResolver.resolve(call.node());
        ControlContext control = ControlContextExtractor.extract(call.node(), methodContext, MAX_PRECEDING_STATEMENTS);

        String actual = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(control) + "\n";
        assertThat(normalizeEol(actual)).isEqualTo(normalizeEol(readGolden(methodName + ".json")));
    }

    private String readGolden(String fileName) throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/golden/control/" + fileName)) {
            assertThat(in).as("golden/control/%s must exist on the test classpath", fileName).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String normalizeEol(String s) {
        return s.replace("\r\n", "\n");
    }
}
