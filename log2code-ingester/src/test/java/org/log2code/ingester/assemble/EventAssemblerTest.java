package org.log2code.ingester.assemble;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.log2code.core.model.CodeVersion;
import org.log2code.core.model.ExceptionInfo;
import org.log2code.core.model.GroundTruth;
import org.log2code.core.model.Level;
import org.log2code.core.model.LogEvent;
import org.log2code.ingester.parse.preset.SpringBootDefaultParser;
import org.log2code.ingester.parse.support.GzipLines;

/**
 * T18 AC1: multiline assembly, orphan events, stack traces, oracle marker/ground_truth and trace
 * ID, exercised against the real fixtures ({@code fixtures/logs/}) and real recorded datasets
 * ({@code datasets/}) rather than hand-written samples, wherever a real example exists.
 */
class EventAssemblerTest {

    private static final Path FIXTURES_DIR = Path.of("..", "fixtures", "logs");
    private static final Path DATASETS_DIR = Path.of("..", "datasets");
    private static final CodeVersion CODE = new CodeVersion(
        "spring-petclinic-microservices", "3858f9c630cf989bb6809a86edf47c2be78dc9f1");

    private final EventAssembler assembler = new EventAssembler(new SpringBootDefaultParser());

    private static AssemblyContext context(String datasetId, String sourceFile, String service, boolean oracle) {
        return new AssemblyContext(datasetId, sourceFile, service, "spring-petclinic-" + service, CODE,
            "spring-boot-default", oracle);
    }

    // ---- orphan events --------------------------------------------------------------------------

    @Test
    void leadingLinesBeforeFirstHeaderFormOneOrphanEvent() {
        Path fixture = FIXTURES_DIR.resolve("petclinic-customers-service-sample.log");
        List<LogEvent> events = assembler.assemble(fixture, context("t", "x.log", "customers-service", false)).toList();

        LogEvent orphan = events.get(0);
        assertThat(orphan.level()).isEqualTo(Level.UNKNOWN);
        assertThat(orphan.lineNumber()).isEqualTo(1);
        assertThat(orphan.lineCount()).isEqualTo(11);
        assertThat(orphan.timestamp()).isNull();
        assertThat(orphan.loggerRaw()).isNull();
        assertThat(orphan.raw()).contains("Ignoring unknown property").contains(":: Spring Boot ::");
        assertThat(orphan.message()).contains("Ignoring unknown property");
        assertThat(orphan.sequence()).isEqualTo(0);
    }

    // ---- every header becomes (at least) one event, nothing is dropped --------------------------

    static Stream<Path> fixtureFiles() throws IOException {
        try (Stream<Path> files = Files.list(FIXTURES_DIR)) {
            return files.filter(p -> p.getFileName().toString().endsWith(".log")).sorted().toList().stream();
        }
    }

    @ParameterizedTest
    @MethodSource("fixtureFiles")
    void eventCountEqualsHeaderCountPlusLeadingOrphan(Path fixture) throws IOException {
        List<String> lines = Files.readAllLines(fixture);
        long headerCount = lines.stream().filter(EventAssemblerTest::looksLikeHeader).count();
        boolean hasLeadingOrphan = !looksLikeHeader(lines.get(0));

        List<LogEvent> events = assembler.assemble(fixture, context("t", "x.log", "svc", false)).toList();

        assertThat(events).hasSize((int) headerCount + (hasLeadingOrphan ? 1 : 0));
        long totalLines = events.stream().mapToLong(LogEvent::lineCount).sum();
        assertThat(totalLines).isEqualTo(lines.size());
    }

    private static boolean looksLikeHeader(String line) {
        return line.matches("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}(Z|[+-]\\d{2}:\\d{2}).*");
    }

    // ---- .gz reading matches the plain-line count --------------------------------------------------

    @Test
    void readsGzipDatasetFileWithoutLosingOrDuplicatingLines() throws IOException {
        Path gz = DATASETS_DIR.resolve("smoke-01").resolve("logs").resolve("customers-service.log.gz");
        List<String> expectedLines = GzipLines.read(gz);

        List<LogEvent> events = assembler.assemble(gz, context("smoke-01", "logs/customers-service.log.gz", "customers-service", false)).toList();

        long totalLines = events.stream().mapToLong(LogEvent::lineCount).sum();
        assertThat(totalLines).isEqualTo(expectedLines.size());
        assertThat(events).allSatisfy(e -> assertThat(e.datasetId()).isEqualTo("smoke-01"));
        assertThat(events).allSatisfy(e -> assertThat(e.sourceFile()).isEqualTo("logs/customers-service.log.gz"));
    }

    // ---- stack traces on real fixture data -------------------------------------------------------

    @Test
    void stackTraceWithNoClassLineIsAssembledFromRealFixture() {
        // vets-service fixture, line 623: RedirectingEurekaHttpClient — message already embeds the
        // exception description, frames start directly with no separate class line, then one
        // "Caused by:" ending in "... 25 more" (docs/log-format.md §4).
        Path fixture = FIXTURES_DIR.resolve("petclinic-vets-service-sample.log");
        List<LogEvent> events = assembler.assemble(fixture, context("t", "x.log", "vets-service", false)).toList();

        LogEvent event = events.stream()
            .filter(e -> e.lineNumber() == 623)
            .findFirst()
            .orElseThrow(() -> new AssertionError("no event starting at line 623"));

        assertThat(event.message()).startsWith("Request execution error. endpoint=DefaultEndpoint");
        assertThat(event.message()).doesNotContain("\n");
        ExceptionInfo exception = event.exception();
        assertThat(exception).isNotNull();
        assertThat(exception.className()).isNull();
        assertThat(exception.message()).isNull();
        assertThat(exception.frames()).hasSize(27);
        assertThat(exception.causedBy()).hasSize(1);
        assertThat(exception.causedBy().get(0).className()).isEqualTo("org.apache.hc.client5.http.HttpHostConnectException");
        assertThat(exception.causedBy().get(0).frames()).hasSize(35);
        assertThat(exception.rootClass()).isEqualTo("org.apache.hc.client5.http.HttpHostConnectException");
        assertThat(event.level()).isEqualTo(Level.INFO);
    }

    @Test
    void stackTraceWithCommonFramesOmittedIsAssembledFromRealFixture() {
        // discovery-server fixture, line 67: class line present, "... N common frames omitted".
        Path fixture = FIXTURES_DIR.resolve("petclinic-discovery-server-sample.log");
        List<LogEvent> events = assembler.assemble(fixture, context("t", "x.log", "discovery-server", false)).toList();

        LogEvent event = events.stream()
            .filter(e -> e.lineNumber() == 67)
            .findFirst()
            .orElseThrow(() -> new AssertionError("no event starting at line 67"));

        assertThat(event.message()).isEqualTo("It seems to be a socket read timeout exception, it will retry later. "
            + "if it continues to happen and some eureka node occupied all the cpu time, you should set property "
            + "'eureka.server.peer-node-read-timeout-ms' to a bigger value");
        ExceptionInfo exception = event.exception();
        assertThat(exception.className()).isEqualTo("jakarta.ws.rs.ProcessingException");
        assertThat(exception.message()).isEqualTo("java.net.SocketTimeoutException: Read timed out");
        assertThat(exception.frames()).hasSize(17);
        assertThat(exception.causedBy()).hasSize(1);
        assertThat(exception.causedBy().get(0).className()).isEqualTo("java.net.SocketTimeoutException");
        assertThat(exception.causedBy().get(0).frames()).hasSize(22);
        assertThat(exception.rootClass()).isEqualTo("java.net.SocketTimeoutException");
    }

    @Test
    void simpleEventWithoutExceptionHasNullException() {
        Path fixture = FIXTURES_DIR.resolve("petclinic-customers-service-sample.log");
        List<LogEvent> events = assembler.assemble(fixture, context("t", "x.log", "customers-service", false)).toList();

        LogEvent saving = events.stream().filter(e -> e.message() != null && e.message().startsWith("Saving owner ["))
            .findFirst().orElseThrow();

        assertThat(saving.exception()).isNull();
        assertThat(saving.lineCount()).isEqualTo(1);
        assertThat(saving.logger()).isNull(); // resolved logger FQN is T19/T20's job, not T18's
    }

    // ---- trace ID / span ID (T18 step 4) ----------------------------------------------------------

    @Test
    void traceAndSpanIdAreParsedFromCorrelationSegment() {
        Path fixture = FIXTURES_DIR.resolve("petclinic-customers-service-sample.log");
        List<LogEvent> events = assembler.assemble(fixture, context("t", "x.log", "customers-service", false)).toList();

        LogEvent saving = events.stream().filter(e -> e.message() != null && e.message().startsWith("Saving owner ["))
            .findFirst().orElseThrow();

        assertThat(saving.traceId()).isEqualTo("6ab17a0317ae033034328c9faf2c8bf9");
        assertThat(saving.spanId()).isEqualTo("27023b9c90191af8");
    }

    @Test
    void blankCorrelationSegmentGivesNullTraceAndSpanId() {
        Path fixture = FIXTURES_DIR.resolve("petclinic-customers-service-sample.log");
        List<LogEvent> events = assembler.assemble(fixture, context("t", "x.log", "customers-service", false)).toList();

        LogEvent starting = events.stream()
            .filter(e -> "main".equals(e.thread()) && e.message() != null && e.message().startsWith("Starting CustomersServiceApplication"))
            .findFirst().orElseThrow();

        assertThat(starting.traceId()).isNull();
        assertThat(starting.spanId()).isNull();
    }

    @Test
    void absentCorrelationSegmentGivesNullTraceAndSpanId() {
        Path fixture = FIXTURES_DIR.resolve("petclinic-config-server-sample.log");
        List<LogEvent> events = assembler.assemble(fixture, context("t", "x.log", "config-server", false)).toList();

        assertThat(events).allSatisfy(e -> {
            assertThat(e.traceId()).isNull();
            assertThat(e.spanId()).isNull();
        });
    }

    // ---- oracle mode: marker removal + ground_truth (T18 step 5), real recorded oracle data ----

    @Test
    void oracleMarkerIsStrippedAndGroundTruthPopulatedFromRealDataset() throws IOException {
        Path gz = DATASETS_DIR.resolve("smoke-oracle-01").resolve("logs").resolve("customers-service.log.gz");
        List<LogEvent> events = assembler.assemble(
            gz, context("smoke-oracle-01", "logs/customers-service.log.gz", "customers-service", true)).toList();

        LogEvent event = events.stream()
            .filter(e -> e.message() != null && e.message().contains("Owner@30e8988b"))
            .findFirst().orElseThrow();

        assertThat(event.message()).isEqualTo(
            "Saving owner [Owner@30e8988b id = 11, lastName = 'Tester', firstName = 'Smoke', "
                + "address = '2 Test St.', city = 'Testville', telephone = '5559876543']");
        assertThat(event.message()).doesNotContain("@@L2C");
        GroundTruth gt = event.groundTruth();
        assertThat(gt).isNotNull();
        assertThat(gt.className()).isEqualTo("org.springframework.samples.petclinic.customers.web.OwnerResource");
        assertThat(gt.method()).isEqualTo("updateOwner");
        assertThat(gt.line()).isEqualTo(89);
        assertThat(gt.reliable()).isTrue();
    }

    @Test
    void unreliableCallerInRealOracleDataIsMarkedUnreliable() throws IOException {
        // org.apache.juli.logging.DirectJDKLog is Tomcat's own JCL bridge: %C/%M name the bridge,
        // not the code that actually logged, so ground_truth must come back unreliable.
        Path gz = DATASETS_DIR.resolve("smoke-oracle-01").resolve("logs").resolve("customers-service.log.gz");
        List<LogEvent> events = assembler.assemble(
            gz, context("smoke-oracle-01", "logs/customers-service.log.gz", "customers-service", true)).toList();

        List<LogEvent> juliEvents = events.stream()
            .filter(e -> e.groundTruth() != null && "org.apache.juli.logging.DirectJDKLog".equals(e.groundTruth().className()))
            .toList();

        assertThat(juliEvents).isNotEmpty();
        assertThat(juliEvents).allSatisfy(e -> assertThat(e.groundTruth().reliable()).isFalse());
    }

    @Test
    void nonOracleContextNeverPopulatesGroundTruth() throws IOException {
        Path gz = DATASETS_DIR.resolve("smoke-01").resolve("logs").resolve("customers-service.log.gz");
        List<LogEvent> events = assembler.assemble(
            gz, context("smoke-01", "logs/customers-service.log.gz", "customers-service", false)).toList();

        assertThat(events).allSatisfy(e -> assertThat(e.groundTruth()).isNull());
    }

    // ---- sequence and code version are stamped from the context -----------------------------------

    @Test
    void sequenceIsAssignedInFileOrderAndCodeVersionComesFromContext() {
        Path fixture = FIXTURES_DIR.resolve("petclinic-customers-service-sample.log");
        List<LogEvent> events = assembler.assemble(fixture, context("t", "x.log", "customers-service", false)).toList();

        for (int i = 0; i < events.size(); i++) {
            assertThat(events.get(i).sequence()).isEqualTo(i);
            assertThat(events.get(i).code()).isEqualTo(CODE);
        }
    }
}
