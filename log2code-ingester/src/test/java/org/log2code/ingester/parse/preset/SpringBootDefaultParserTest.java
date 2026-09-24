package org.log2code.ingester.parse.preset;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.log2code.core.model.Level;
import org.log2code.ingester.parse.HeaderFields;
import org.log2code.ingester.parse.support.GzipLines;

/**
 * T17 AC1/step3: every header in {@code fixtures/logs/} and every recorded dataset must be
 * recognized (0 misses), and no stack-trace/banner/blank line must be mistaken for one.
 */
class SpringBootDefaultParserTest {

    private static final Path FIXTURES_DIR = Path.of("..", "fixtures", "logs");
    private static final Path DATASETS_DIR = Path.of("..", "datasets");

    private final SpringBootDefaultParser parser = new SpringBootDefaultParser();

    // ---- exhaustive recognition over real logs ------------------------------------------------

    static Stream<Path> fixtureFiles() throws IOException {
        try (Stream<Path> files = Files.list(FIXTURES_DIR)) {
            return files.filter(p -> p.getFileName().toString().endsWith(".log")).sorted().toList().stream();
        }
    }

    @ParameterizedTest
    @MethodSource("fixtureFiles")
    void recognizesEveryHeaderAndNothingElse(Path fixture) throws IOException {
        assertHeaderRecognitionExact(Files.readAllLines(fixture), fixture.toString());
    }

    static Stream<String> datasetIds() {
        return Stream.of("smoke-01", "smoke-oracle-01", "tune-01", "test-01", "demo-01");
    }

    @ParameterizedTest
    @MethodSource("datasetIds")
    void recognizesEveryHeaderInRecordedDatasets(String datasetId) throws IOException {
        Path logsDir = DATASETS_DIR.resolve(datasetId).resolve("logs");
        try (Stream<Path> gzFiles = Files.list(logsDir)) {
            for (Path gz : gzFiles.filter(p -> p.getFileName().toString().endsWith(".log.gz")).sorted().toList()) {
                assertHeaderRecognitionExact(GzipLines.read(gz), datasetId + "/" + gz.getFileName());
            }
        }
    }

    /**
     * A line "looks like a header" iff it starts with an ISO-8601 timestamp (0.9's own
     * definition of where an event starts, {@code docs/log-format.md} §5) — independent of this
     * parser's regex, so the comparison actually proves something.
     */
    private void assertHeaderRecognitionExact(List<String> lines, String source) {
        int expectedHeaders = 0;
        int falseNegatives = 0;
        int falsePositives = 0;
        for (String line : lines) {
            boolean looksLikeHeader = line.matches("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}(Z|[+-]\\d{2}:\\d{2}).*");
            boolean recognized = parser.parseHeader(line).isPresent();
            if (looksLikeHeader) {
                expectedHeaders++;
                if (!recognized) {
                    falseNegatives++;
                }
            } else if (recognized) {
                falsePositives++;
            }
        }
        assertThat(expectedHeaders).as("%s: sanity check, at least one header expected", source).isPositive();
        assertThat(falseNegatives).as("%s: header lines not recognized", source).isZero();
        assertThat(falsePositives).as("%s: non-header lines mistaken for headers", source).isZero();
    }

    // ---- field extraction on known real lines ------------------------------------------------

    @Test
    void extractsFieldsForLineWithTraceId() throws IOException {
        String line = findLine("petclinic-customers-service-sample.log", l -> l.contains("Saving owner ["));
        HeaderFields fields = parser.parseHeader(line).orElseThrow();

        assertThat(fields.timestampRaw()).isEqualTo("2026-09-21T18:40:03.732Z");
        assertThat(fields.instant()).isEqualTo(Instant.parse("2026-09-21T18:40:03.732Z"));
        assertThat(fields.level()).isEqualTo(Level.INFO);
        assertThat(fields.pid()).isEqualTo("1");
        assertThat(fields.appName()).isEqualTo("customers-service");
        assertThat(fields.thread()).isEqualTo("nio-8081-exec-1");
        assertThat(fields.correlationRaw()).isEqualTo("6ab17a0317ae033034328c9faf2c8bf9-27023b9c90191af8");
        assertThat(fields.loggerRaw()).isEqualTo("o.s.s.p.customers.web.OwnerResource");
        assertThat(fields.messageFirstLine()).startsWith("Saving owner [Owner@1b7d65ae");
        assertThat(fields.stackTrace()).isNull();
        assertThat(fields.traceId()).isNull();
        assertThat(fields.spanId()).isNull();
    }

    @Test
    void correlationIsSpacesWhenTracingIsActiveButNoSpanIsOpen() throws IOException {
        String line = findLine("petclinic-customers-service-sample.log",
            l -> l.contains("main") && l.contains("Starting CustomersServiceApplication"));
        HeaderFields fields = parser.parseHeader(line).orElseThrow();

        assertThat(fields.appName()).isEqualTo("customers-service");
        assertThat(fields.thread()).isEqualTo("main");
        assertThat(fields.correlationRaw()).isNotNull().hasSize(49).isBlank();
    }

    @Test
    void configServerHasNoAppNameAndNoCorrelationSegmentAtAll() throws IOException {
        // ADR-007: config-server has no spring.application.name, so %esb omits the [app-name]
        // bracket entirely; it also has no active tracing, so the correlation bracket is absent too.
        String line = findLine("petclinic-config-server-sample.log", l -> l.contains("Starting ConfigServerApplication"));
        HeaderFields fields = parser.parseHeader(line).orElseThrow();

        assertThat(fields.appName()).isNull();
        assertThat(fields.thread()).isEqualTo("main");
        assertThat(fields.correlationRaw()).isNull();
        assertThat(fields.loggerRaw()).isEqualTo("o.s.s.p.config.ConfigServerApplication");
    }

    @Test
    void discoveryServerHasAppNameButNoCorrelationSegment() throws IOException {
        String line = findLine("petclinic-discovery-server-sample.log", l -> l.contains("Starting DiscoveryServerApplication"));
        HeaderFields fields = parser.parseHeader(line).orElseThrow();

        assertThat(fields.appName()).isEqualTo("discovery-server");
        assertThat(fields.correlationRaw()).isNull();
    }

    @Test
    void emptyMessageIsEmptyStringNotNull() throws IOException {
        String line = findLine("petclinic-customers-service-sample.log", l -> l.endsWith("ChaosMonkeyConfiguration   : "));
        HeaderFields fields = parser.parseHeader(line).orElseThrow();

        assertThat(fields.messageFirstLine()).isEmpty();
        assertThat(fields.loggerRaw()).isEqualTo("d.c.s.b.c.m.c.ChaosMonkeyConfiguration");
    }

    @Test
    void errorLineWithReactorCheckpointHeaderIsRecognized() throws IOException {
        String line = findLine("petclinic-api-gateway-sample.log", l -> l.contains("AbstractErrorWebExceptionHandler"));
        HeaderFields fields = parser.parseHeader(line).orElseThrow();

        assertThat(fields.level()).isEqualTo(Level.ERROR);
        assertThat(fields.appName()).isEqualTo("api-gateway");
        assertThat(fields.messageFirstLine()).contains("500 Server Error for HTTP GET");
    }

    // ---- non-header lines must not match -------------------------------------------------------

    @Test
    void stackTraceLinesAreNotHeaders() {
        assertThat(parser.parseHeader("\tat java.base/java.lang.Thread.run(Thread.java:840)")).isEmpty();
        assertThat(parser.parseHeader("Caused by: org.apache.hc.client5.http.HttpHostConnectException: Connect to http://discovery-server:8761 failed")).isEmpty();
        assertThat(parser.parseHeader("\t... 25 more")).isEmpty();
        assertThat(parser.parseHeader("")).isEmpty();
        assertThat(parser.parseHeader("  :: Spring Boot ::                (v4.0.1)")).isEmpty();
    }

    // ---- timezone offsets and ANSI codes (0.9's "T17 must tolerate both") ---------------------

    @Test
    void acceptsNonUtcOffsetInsteadOfZ() {
        HeaderFields fields = parser.parseHeader(
            "2026-09-21T20:40:03.732+02:00  INFO 1 --- [customers-service] [nio-8081-exec-1] "
                + "[                                                 ] o.s.s.p.c.web.OwnerResource              : Saving owner test")
            .orElseThrow();

        assertThat(fields.timestampRaw()).isEqualTo("2026-09-21T20:40:03.732+02:00");
        assertThat(fields.instant()).isEqualTo(Instant.parse("2026-09-21T18:40:03.732Z"));
    }

    @Test
    void stripsAnsiColorCodesBeforeMatching() {
        String ansiLine = "\u001B[0;39m2026-09-21T18:40:03.732Z\u001B[0;39m \u001B[32m INFO\u001B[0;39m 1 --- [customers-service] "
            + "\u001B[35m[nio-8081-exec-1]\u001B[0;39m [                                                 ] "
            + "\u001B[36mo.s.s.p.c.web.OwnerResource             \u001B[0;39m : Saving owner test";

        HeaderFields fields = parser.parseHeader(ansiLine).orElseThrow();

        assertThat(fields.level()).isEqualTo(Level.INFO);
        assertThat(fields.appName()).isEqualTo("customers-service");
        assertThat(fields.thread()).isEqualTo("nio-8081-exec-1");
        assertThat(fields.loggerRaw()).isEqualTo("o.s.s.p.c.web.OwnerResource");
        assertThat(fields.messageFirstLine()).isEqualTo("Saving owner test");
    }

    // ---- test helper ----------------------------------------------------------------------------

    private static String findLine(String fixtureFileName, java.util.function.Predicate<String> match) throws IOException {
        List<String> lines = Files.readAllLines(FIXTURES_DIR.resolve(fixtureFileName));
        return lines.stream().filter(match).findFirst()
            .orElseThrow(() -> new UncheckedIOException(new IOException("no matching line in " + fixtureFileName)));
    }
}
