package org.log2code.core.json;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.log2code.core.model.AnalysisRun;
import org.log2code.core.model.Candidate;
import org.log2code.core.model.CallEdge;
import org.log2code.core.model.CallSite;
import org.log2code.core.model.CallerRef;
import org.log2code.core.model.CatalogEntry;
import org.log2code.core.model.CausedBy;
import org.log2code.core.model.CodeUnit;
import org.log2code.core.model.CodeVersion;
import org.log2code.core.model.Condition;
import org.log2code.core.model.ControlContext;
import org.log2code.core.model.EarlyExit;
import org.log2code.core.model.EnclosingBlock;
import org.log2code.core.model.EnrichedLog;
import org.log2code.core.model.ExceptionInfo;
import org.log2code.core.model.GroundTruth;
import org.log2code.core.model.Label;
import org.log2code.core.model.Level;
import org.log2code.core.model.LogEvent;
import org.log2code.core.model.MatchResult;
import org.log2code.core.model.MethodInfo;
import org.log2code.core.model.ModuleInfo;
import org.log2code.core.model.SourceFile;
import org.log2code.core.model.StackFrame;
import org.log2code.core.model.TypeInfo;

/** Every record model must survive a serialize/deserialize cycle unchanged. */
class JsonRoundTripTest {

    private final ObjectMapper mapper = Json.mapper();

    private <T> void roundTrip(T instance, Class<T> type) throws Exception {
        String json = mapper.writeValueAsString(instance);
        T parsed = mapper.readValue(json, type);
        assertThat(parsed).isEqualTo(instance);
    }

    @Test
    void codeUnit() throws Exception {
        roundTrip(new CodeUnit("project", "spring-petclinic-microservices", "3858f9c"), CodeUnit.class);
    }

    @Test
    void codeVersion() throws Exception {
        roundTrip(new CodeVersion("spring-petclinic-microservices", "3858f9c"), CodeVersion.class);
    }

    @Test
    void enclosingBlock() throws Exception {
        roundTrip(new EnclosingBlock("if", "owner != null", "then", 85, 92), EnclosingBlock.class);
    }

    @Test
    void condition() throws Exception {
        roundTrip(new Condition("owner != null", false, 85), Condition.class);
    }

    @Test
    void earlyExit() throws Exception {
        roundTrip(new EarlyExit("return", "return null", 80), EarlyExit.class);
    }

    @Test
    void precedingStatement() throws Exception {
        roundTrip(new org.log2code.core.model.PrecedingStatement("call", "owner.setId(id)", 86), org.log2code.core.model.PrecedingStatement.class);
    }

    @Test
    void callSite() throws Exception {
        roundTrip(new CallSite("ownerRepository.save(owner)", 88), CallSite.class);
    }

    @Test
    void controlContext() throws Exception {
        ControlContext control = new ControlContext(
            List.of(new Condition("owner != null", false, 85)),
            List.of(new EarlyExit("return", "return null", 80)),
            List.of(new org.log2code.core.model.PrecedingStatement("call", "owner.setId(id)", 86)),
            List.of(new CallSite("ownerRepository.save(owner)", 88))
        );
        roundTrip(control, ControlContext.class);
    }

    @Test
    void catalogEntry() throws Exception {
        roundTrip(sampleCatalogEntry(), CatalogEntry.class);
    }

    @Test
    void sourceFile() throws Exception {
        roundTrip(new SourceFile(
            "8f6318c0ec6d7fb45e24182a52b53c9c",
            new CodeUnit("project", "spring-petclinic-microservices", "3858f9c"),
            "spring-petclinic-customers-service",
            "src/main/java/org/springframework/samples/petclinic/customers/web/OwnerResource.java",
            "package org.springframework.samples.petclinic.customers.web;\n",
            120,
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b85"
        ), SourceFile.class);
    }

    @Test
    void typeInfo() throws Exception {
        roundTrip(new TypeInfo(
            "3f7edfd4d6909576a5bf472efdf23d8e",
            new CodeUnit("project", "spring-petclinic-microservices", "3858f9c"),
            "spring-petclinic-customers-service",
            "src/main/java/org/springframework/samples/petclinic/customers/web/OwnerResource.java",
            "org.springframework.samples.petclinic.customers.web.OwnerResource",
            "org.springframework.samples.petclinic.customers.web.OwnerResource",
            "java.lang.Object",
            List.of("java.io.Serializable"),
            "class"
        ), TypeInfo.class);
    }

    @Test
    void callEdge() throws Exception {
        roundTrip(new CallEdge(88, "ownerRepository.save(owner)", "7673562226865ee709711fe839d97602", "OwnerRepository.save(Owner)", true, true), CallEdge.class);
    }

    @Test
    void callerRef() throws Exception {
        roundTrip(new CallerRef("7673562226865ee709711fe839d97602", "org.springframework.samples.petclinic.customers.web.OwnerResource", "save", "8f6318c0ec6d7fb45e24182a52b53c9c", 88), CallerRef.class);
    }

    @Test
    void methodInfo() throws Exception {
        MethodInfo methodInfo = new MethodInfo(
            "7673562226865ee709711fe839d97602",
            new CodeUnit("project", "spring-petclinic-microservices", "3858f9c"),
            "spring-petclinic-customers-service",
            "customers-service",
            "8f6318c0ec6d7fb45e24182a52b53c9c",
            "src/main/java/org/springframework/samples/petclinic/customers/web/OwnerResource.java",
            "org.springframework.samples.petclinic.customers.web.OwnerResource",
            "org.springframework.samples.petclinic.customers.web.OwnerResource",
            "save",
            "save(Owner)",
            80, 92,
            List.of("PostMapping"),
            true,
            List.of(new CallEdge(88, "ownerRepository.save(owner)", "abc", "OwnerRepository.save(Owner)", true, true)),
            List.of(new CallerRef("def", "org.springframework.samples.petclinic.customers.web.OwnerController", "createOwner", "8f6318c0ec6d7fb45e24182a52b53c9c", 45)),
            3
        );
        roundTrip(methodInfo, MethodInfo.class);
    }

    @Test
    void moduleInfo() throws Exception {
        roundTrip(new ModuleInfo(
            "spring-petclinic-customers-service",
            "customers-service",
            List.of("src/main/java"),
            List.of("org.springframework:spring-web:6.2.1"),
            List.of("org.springframework:spring-web:6.2.1")
        ), ModuleInfo.class);
    }

    @Test
    void analysisRun() throws Exception {
        AnalysisRun run = new AnalysisRun(
            "b32b0699da81541160771d501bfd7649",
            "project",
            new CodeUnit("project", "spring-petclinic-microservices", "3858f9c"),
            "https://github.com/spring-petclinic/spring-petclinic-microservices",
            "0.1.0-SNAPSHOT",
            Instant.parse("2026-09-22T10:00:00Z"),
            Instant.parse("2026-09-22T10:00:05Z"),
            5000L,
            Map.of("statements_found", 13),
            List.of(new ModuleInfo("spring-petclinic-customers-service", "customers-service", List.of("src/main/java"), List.of(), List.of()))
        );
        roundTrip(run, AnalysisRun.class);
    }

    @Test
    void stackFrame() throws Exception {
        roundTrip(new StackFrame(
            "java.lang.IllegalStateException", "save", "OwnerResource.java", 89, true,
            "spring-petclinic-microservices", "8f6318c0ec6d7fb45e24182a52b53c9c",
            "https://github.com/spring-petclinic/spring-petclinic-microservices/blob/3858f9c/.../OwnerResource.java#L89"
        ), StackFrame.class);
    }

    @Test
    void causedBy() throws Exception {
        roundTrip(new CausedBy("java.lang.NullPointerException", "owner is null", List.of()), CausedBy.class);
    }

    @Test
    void exceptionInfo() throws Exception {
        ExceptionInfo exception = new ExceptionInfo(
            "java.lang.IllegalStateException",
            "java.lang.NullPointerException",
            "could not save owner",
            List.of(new StackFrame("java.lang.IllegalStateException", "save", "OwnerResource.java", 89, true, "spring-petclinic-microservices", "8f6318c0ec6d7fb45e24182a52b53c9c", null)),
            List.of(new CausedBy("java.lang.NullPointerException", "owner is null", List.of()))
        );
        roundTrip(exception, ExceptionInfo.class);
    }

    @Test
    void groundTruth() throws Exception {
        roundTrip(new GroundTruth("org.springframework.samples.petclinic.customers.web.OwnerResource", "save", 89, true), GroundTruth.class);
    }

    @Test
    void logEvent() throws Exception {
        LogEvent event = new LogEvent(
            "smoke-01", "logs/customers-service.log.gz", 120, 1, 42L,
            Instant.parse("2026-09-21T13:34:00.123Z"), "2026-09-21T13:34:00.123Z",
            "customers-service", "spring-petclinic-customers-service",
            "customers-service", "1", "nio-8081-exec-1",
            Level.INFO, "o.s.s.p.c.web.OwnerResource", "org.springframework.samples.petclinic.customers.web.OwnerResource",
            "Saving owner Owner[id=1]",
            "2026-09-21T13:34:00.123Z  INFO 1 --- [customers-service] ... Saving owner Owner[id=1]",
            null, null, null,
            new CodeVersion("spring-petclinic-microservices", "3858f9c"),
            null,
            "spring-boot-default"
        );
        roundTrip(event, LogEvent.class);
    }

    @Test
    void candidate() throws Exception {
        roundTrip(new Candidate("da2bdae0c0f227916731aec370770514", 0.92), Candidate.class);
    }

    @Test
    void matchResult() throws Exception {
        roundTrip(sampleMatchResult(), MatchResult.class);
    }

    @Test
    void enrichedLog() throws Exception {
        roundTrip(sampleEnrichedLog(), EnrichedLog.class);
    }

    @Test
    void label() throws Exception {
        roundTrip(new Label(
            "dc37f71b0143613a6549a96547a324ad", "smoke-01", "correct",
            "da2bdae0c0f227916731aec370770514", "da2bdae0c0f227916731aec370770514",
            "obviously correct", Instant.parse("2026-09-22T11:00:00Z")
        ), Label.class);
    }

    static CatalogEntry sampleCatalogEntry() {
        return new CatalogEntry(
            "da2bdae0c0f227916731aec370770514",
            "fa93107b1a1e59803ffb55d40ecbf78c",
            new CodeUnit("project", "spring-petclinic-microservices", "3858f9c630cf989bb6809a86edf47c2be78dc9f1"),
            "spring-petclinic-customers-service",
            "customers-service",
            "spring-petclinic-customers-service/src/main/java/org/springframework/samples/petclinic/customers/web/OwnerResource.java",
            "8f6318c0ec6d7fb45e24182a52b53c9c",
            "org.springframework.samples.petclinic.customers.web",
            "org.springframework.samples.petclinic.customers.web.OwnerResource",
            "org.springframework.samples.petclinic.customers.web.OwnerResource",
            "save",
            "save(Owner)",
            "7673562226865ee709711fe839d97602",
            false,
            89, 89, 8,
            80, 92,
            "slf4j",
            "typed",
            "log",
            "org.springframework.samples.petclinic.customers.web.OwnerResource",
            "class_literal",
            Level.INFO,
            false,
            "\"Saving owner {}\", owner",
            "Saving owner {}",
            "placeholders",
            null,
            "^\\QSaving owner \\E(.*?)$",
            List.of("saving", "owner"),
            12, 1,
            false,
            new EnclosingBlock("method_body", null, null, 80, 92),
            null,
            "    public Owner save(Owner owner) {\n        log.info(\"Saving owner {}\", owner);\n",
            85,
            "https://github.com/spring-petclinic/spring-petclinic-microservices/blob/3858f9c630cf989bb6809a86edf47c2be78dc9f1/spring-petclinic-customers-service/src/main/java/org/springframework/samples/petclinic/customers/web/OwnerResource.java#L89",
            "0.1.0-SNAPSHOT",
            Instant.parse("2026-09-22T10:00:05Z")
        );
    }

    static MatchResult sampleMatchResult() {
        // LinkedHashMap, not Map.of: Map.of's iteration order is salted per JVM run and would
        // make the golden JSON comparison flaky across processes.
        Map<String, Double> scoreBreakdown = new LinkedHashMap<>();
        scoreBreakdown.put("regex_full", 0.45);
        scoreBreakdown.put("specificity", 0.17);
        scoreBreakdown.put("logger_exact", 0.20);
        scoreBreakdown.put("level_equal", 0.10);
        return new MatchResult(
            "matched",
            "da2bdae0c0f227916731aec370770514",
            0.92,
            "high",
            List.of(new Candidate("da2bdae0c0f227916731aec370770514", 0.92), new Candidate("other-statement-id", 0.4)),
            scoreBreakdown,
            List.of("Owner[id=1]"),
            "spring-petclinic-microservices",
            "spring-petclinic-customers-service",
            "org.springframework.samples.petclinic.customers.web.OwnerResource",
            "save",
            "spring-petclinic-customers-service/src/main/java/org/springframework/samples/petclinic/customers/web/OwnerResource.java",
            "slf4j",
            "placeholders",
            89,
            "Saving owner {}",
            "https://github.com/spring-petclinic/spring-petclinic-microservices/blob/3858f9c630cf989bb6809a86edf47c2be78dc9f1/.../OwnerResource.java#L89"
        );
    }

    static EnrichedLog sampleEnrichedLog() {
        return new EnrichedLog(
            "dc37f71b0143613a6549a96547a324ad",
            Instant.parse("2026-09-21T13:34:00.123Z"),
            "2026-09-21T13:34:00.123Z",
            "smoke-01",
            "logs/customers-service.log.gz",
            120, 1, 42L,
            "customers-service", "spring-petclinic-customers-service",
            "customers-service", "1", "nio-8081-exec-1",
            Level.INFO,
            "o.s.s.p.c.web.OwnerResource",
            "org.springframework.samples.petclinic.customers.web.OwnerResource",
            "Saving owner Owner[id=1]",
            "2026-09-21T13:34:00.123Z  INFO 1 --- [customers-service] [nio-8081-exec-1] o.s.s.p.c.web.OwnerResource : Saving owner Owner[id=1]",
            null, null,
            null,
            new CodeVersion("spring-petclinic-microservices", "3858f9c630cf989bb6809a86edf47c2be78dc9f1"),
            sampleMatchResult(),
            null,
            "spring-boot-default",
            "0.1.0-SNAPSHOT",
            Instant.parse("2026-09-22T11:00:00Z")
        );
    }
}
