package org.log2code.api.llm.explain;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.log2code.api.dto.LogSummary;
import org.log2code.api.dto.NeighborsResponse;
import org.log2code.api.service.LogMapper;
import org.log2code.api.service.LogNeighborhoodService;
import org.log2code.core.model.CallSite;
import org.log2code.core.model.CallerRef;
import org.log2code.core.model.CatalogEntry;
import org.log2code.core.model.Candidate;
import org.log2code.core.model.CodeUnit;
import org.log2code.core.model.CodeVersion;
import org.log2code.core.model.Condition;
import org.log2code.core.model.ControlContext;
import org.log2code.core.model.EarlyExit;
import org.log2code.core.model.EnclosingBlock;
import org.log2code.core.model.EnrichedLog;
import org.log2code.core.model.ExceptionInfo;
import org.log2code.core.model.Level;
import org.log2code.core.model.MatchResult;
import org.log2code.core.model.MethodInfo;
import org.log2code.core.model.PrecedingStatement;
import org.log2code.core.model.SourceFile;
import org.log2code.core.model.StackFrame;
import org.log2code.core.opensearch.DocumentReader;
import org.log2code.core.opensearch.IndexNames;
import org.mockito.ArgumentMatchers;

/**
 * Test data for the prompt tests: the T38 "S1" scenario in miniature (a project ERROR in
 * {@code PetLifeStage.resolve}, called from {@code PetProfile.describe} ← {@code PetService.savePet} ←
 * {@code PetResource.processCreationForm}/{@code processUpdateForm}) held in an in-memory {@link Store}.
 */
final class ExplainFixtures {

    static final IndexNames INDEX = new IndexNames();
    static final CodeUnit PROJECT = new CodeUnit(CodeUnit.TYPE_PROJECT, "spring-petclinic-microservices",
        "da4784072d5f7e5ea268dc62fd0237ba31da5094");
    static final CodeUnit LIBRARY = new CodeUnit(CodeUnit.TYPE_DEPENDENCY, "org.springframework:spring-web", "6.2.0");

    static final String STAGE_FILE = "customers/application/PetLifeStage.java";
    static final String STAGE_LINE = "        return STAGES[years / YEARS_PER_STAGE];";

    private ExplainFixtures() {
    }

    /** In-memory documents keyed by index and id, served through a mocked {@link DocumentReader}. */
    static final class Store {
        private final Map<String, Object> docs = new HashMap<>();

        void put(String index, String id, Object doc) {
            docs.put(index + "/" + id, doc);
        }

        DocumentReader reader() throws IOException {
            DocumentReader reader = mock(DocumentReader.class);
            when(reader.get(anyString(), anyString(), ArgumentMatchers.<Class<Object>>any()))
                .thenAnswer(invocation -> docs.get(invocation.getArgument(0) + "/" + invocation.getArgument(1)));
            return reader;
        }
    }

    /** {@code count} lines {@code "// <name> line N"}, with {@code overrides} (1-based line to text) replacing some of them. */
    static String source(String name, int count, Map<Integer, String> overrides) {
        List<String> lines = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            lines.add(overrides.getOrDefault(i, "// " + name + " line " + i));
        }
        return String.join("\n", lines);
    }

    static SourceFile sourceFile(String fileId, String path, String content) {
        return new SourceFile(fileId, PROJECT, "spring-petclinic-customers-service", path, content,
            (int) content.lines().count(), "sha");
    }

    /** {@code PetLifeStage.java}: {@code resolve} is lines 40-50 (log in the catch, line 47), {@code stageFor} lines 52-54. */
    static String petLifeStageSource() {
        return source("PetLifeStage", 60, Map.of(
            40, "    static String resolve(LocalDate birthDate, int years) {",
            41, "        if (birthDate == null) {",
            42, "            return \"unknown\";",
            43, "        }",
            44, "        try {",
            45, "            return stageFor(years);",
            46, "        } catch (ArrayIndexOutOfBoundsException e) {",
            47, "            log.error(\"Failed to resolve life stage for pet {} (born {}, {} years old)\", name, birthDate, years, e);",
            48, "            throw new IllegalStateException(e);",
            50, "    }"))
            .replace("// PetLifeStage line 52", "    private static String stageFor(int years) {")
            .replace("// PetLifeStage line 53", STAGE_LINE)
            .replace("// PetLifeStage line 54", "    }");
    }

    static MethodInfo method(String id, String file, String cls, String signature, int start, int end,
                             List<String> annotations, List<CallerRef> calledBy) {
        return new MethodInfo(id, PROJECT, "spring-petclinic-customers-service", "customers-service", "f-" + file,
            "customers/" + file, "org.springframework.samples.petclinic.customers." + cls, cls, signature.substring(0, signature.indexOf('(')),
            signature, start, end, annotations, false, List.of(), calledBy, calledBy.size());
    }

    static CallerRef ref(String methodId, String cls, String name, int line) {
        return new CallerRef(methodId, "org.springframework.samples.petclinic.customers." + cls, name, "f-" + cls + ".java", line);
    }

    static ControlContext stageControl() {
        return new ControlContext(
            List.of(new Condition("try", "", 44, false), new Condition("catch", "ArrayIndexOutOfBoundsException", 46, false)),
            List.of(new EarlyExit("birthDate == null", 41, "return")),
            List.of(new PrecedingStatement("call", "stageFor(years)", 45)),
            List.of(new CallSite(45, "stageFor(years)", "stageFor", null, false)));
    }

    static CatalogEntry stageEntry(CodeUnit unit, ControlContext control) {
        return new CatalogEntry("stmt-stage", "logical-stage", unit, "spring-petclinic-customers-service", "customers-service",
            "customers/application/PetLifeStage.java", "f-PetLifeStage.java", "org.springframework.samples.petclinic.customers.application",
            "org.springframework.samples.petclinic.customers.application.PetLifeStage", "PetLifeStage",
            "resolve", "resolve(LocalDate,int)", "m-resolve", false, 47, 47, 13, 40, 50, "slf4j", "typed", "log",
            "org.springframework.samples.petclinic.customers.application.PetLifeStage", "class_literal", Level.ERROR, false,
            "\"Failed\"", "Failed to resolve life stage for pet {} (born {}, {} years old)", "placeholders", null, "^x$",
            List.of("Failed"), 30, 3, true, new EnclosingBlock("catch", "ArrayIndexOutOfBoundsException", null, 46, 49), control,
            "snippet", 45, "https://github.com/x", "0.1.0", Instant.parse("2026-09-29T10:00:00Z"));
    }

    static MatchResult match(String status, String statementId, double confidence, String level) {
        return new MatchResult(status, statementId, confidence, level,
            statementId == null ? List.of() : List.of(new Candidate(statementId, confidence)), Map.of(), List.of(),
            null, null, null, null, null, null, null, null, null, null);
    }

    static StackFrame projectFrame(String cls, String method, String file, int line) {
        return new StackFrame("org.springframework.samples.petclinic.customers.application." + cls, method, cls + ".java", line, true,
            "spring-petclinic-microservices", file, null);
    }

    static StackFrame libraryFrame(String cls, String method, int line) {
        return new StackFrame("org.springframework.aop." + cls, method, cls + ".java", line, false, "spring-aop:6.2.0", null, null);
    }

    /** The two-line trace header plus {@code frames} {@code \tat} lines. */
    static String raw(String message, String exceptionHeader, int frames) {
        StringBuilder raw = new StringBuilder("2026-09-29T10:00:00.123Z ERROR 1 --- [customers-service] [nio-8081-exec-1] [t-1] o.s.s.p.c.a.PetLifeStage : ")
            .append(message);
        if (exceptionHeader != null) {
            raw.append('\n').append(exceptionHeader);
            for (int i = 0; i < frames; i++) {
                raw.append("\n\tat some.pkg.Frame").append(i).append(".call(Frame.java:").append(i + 1).append(')');
            }
        }
        return raw.toString();
    }

    static EnrichedLog log(String id, Level level, String message, String raw, ExceptionInfo exception, MatchResult match) {
        return new EnrichedLog(id, Instant.parse("2026-09-29T10:00:00.123Z"), "2026-09-29T10:00:00.123Z", "demo-02",
            "logs/customers-service.log", 10, 1, 5, "customers-service", "spring-petclinic-customers-service", "customers-service",
            "1", "nio-8081-exec-1", level, "o.s.s.p.c.a.PetLifeStage",
            "org.springframework.samples.petclinic.customers.application.PetLifeStage", message, raw, "trace-1", "span-1", exception,
            new CodeVersion(PROJECT.name(), PROJECT.version()), match, null, "spring-boot-default", "1.0.0",
            Instant.parse("2026-09-29T10:00:01Z"));
    }

    static LogSummary summary(String id, Instant at, String level, String message) {
        return new LogSummary(id, at, "customers-service", level, "t", "o.s.s.p.c.a.X", message, null, null, null, false, null, null, null, null);
    }

    /** Two logs before and two after {@code log}, as {@code scope=service}, 5 and 5. */
    static LogNeighborhoodService neighbors(EnrichedLog log) {
        LogNeighborhoodService service = mock(LogNeighborhoodService.class);
        Instant at = log.timestamp() != null ? log.timestamp() : Instant.parse("2026-09-29T10:00:00.123Z");
        NeighborsResponse response = new NeighborsResponse(
            List.of(summary("b2", at.minusMillis(20), "INFO", "before two"), summary("b1", at.minusMillis(10), "INFO", "before one")),
            LogMapper.toSummary(log),
            List.of(summary("a1", at.plusMillis(10), "ERROR", "after one\nsecond line"), summary("a2", at.plusMillis(20), "WARN", "after two")));
        when(service.neighbors(any(EnrichedLog.class), eq(5), eq(5), eq("service"))).thenReturn(response);
        return service;
    }

    /** The whole S1 scenario: project ERROR at L0-L4, with callers in three classes and a project stack trace. */
    static Store petLifeStageStore() {
        Store store = new Store();
        store.put(INDEX.catalog(), "stmt-stage", stageEntry(PROJECT, stageControl()));
        store.put(INDEX.sources(), "f-PetLifeStage.java", sourceFile("f-PetLifeStage.java", "customers/application/PetLifeStage.java", petLifeStageSource()));
        store.put(INDEX.sources(), "f-PetProfile.java", sourceFile("f-PetProfile.java", "customers/PetProfile.java",
            source("PetProfile", 60, Map.of(25, "    String describe(Pet pet) {", 31, "        return PetLifeStage.resolve(born, years);", 35, "    }"))));
        store.put(INDEX.sources(), "f-PetService.java", sourceFile("f-PetService.java", "customers/PetService.java",
            source("PetService", 80, Map.of(45, "    Pet savePet(Pet pet) {", 50, "        String profile = profile.describe(pet);", 56, "    }"))));
        store.put(INDEX.sources(), "f-PetResource.java", sourceFile("f-PetResource.java", "customers/PetResource.java",
            source("PetResource", 100, Map.of(60, "    Pet processCreationForm() {", 64, "        return petService.savePet(pet);", 66, "    }",
                68, "    void processUpdateForm() {", 72, "        petService.savePet(pet);", 74, "    }"))));

        store.put(INDEX.methods(), "m-resolve", method("m-resolve", "PetLifeStage.java", "application.PetLifeStage", "resolve(LocalDate,int)", 40, 50,
            List.of(), List.of(ref("m-describe", "PetProfile", "describe", 31))));
        store.put(INDEX.methods(), "m-describe", method("m-describe", "PetProfile.java", "PetProfile", "describe(Pet)", 25, 35,
            List.of(), List.of(ref("m-save", "PetService", "savePet", 50))));
        store.put(INDEX.methods(), "m-save", method("m-save", "PetService.java", "PetService", "savePet(Pet)", 45, 56,
            List.of("Transactional"), List.of(ref("m-create", "PetResource", "processCreationForm", 64),
                ref("m-update", "PetResource", "processUpdateForm", 72))));
        store.put(INDEX.methods(), "m-create", method("m-create", "PetResource.java", "PetResource", "processCreationForm()", 60, 66,
            List.of("PostMapping"), List.of()));
        store.put(INDEX.methods(), "m-update", method("m-update", "PetResource.java", "PetResource", "processUpdateForm()", 68, 74,
            List.of("PutMapping"), List.of()));
        return store;
    }

    static ExceptionInfo stageException() {
        return new ExceptionInfo("java.lang.ArrayIndexOutOfBoundsException", "java.lang.ArrayIndexOutOfBoundsException",
            "Index 5 out of bounds for length 4", List.of(
                projectFrame("PetLifeStage", "stageFor", "f-PetLifeStage.java", 53),
                projectFrame("PetLifeStage", "resolve", "f-PetLifeStage.java", 45),
                libraryFrame("CglibAopProxy", "intercept", 100),
                projectFrame("PetProfile", "describe", "f-PetProfile.java", 31)), List.of());
    }

    static EnrichedLog stageLog() {
        return log("log-stage", Level.ERROR, "Failed to resolve life stage for pet Oldie (born 2001-05-01, 25 years old)",
            raw("Failed to resolve life stage for pet Oldie (born 2001-05-01, 25 years old)",
                "java.lang.ArrayIndexOutOfBoundsException: Index 5 out of bounds for length 4", 3),
            stageException(), match(MatchResult.STATUS_MATCHED, "stmt-stage", 0.95, "high"));
    }
}
