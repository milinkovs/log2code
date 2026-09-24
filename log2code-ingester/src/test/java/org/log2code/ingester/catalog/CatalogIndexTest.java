package org.log2code.ingester.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.log2code.core.model.AnalysisRun;
import org.log2code.core.model.CatalogEntry;
import org.log2code.core.model.CodeUnit;
import org.log2code.core.model.EnclosingBlock;
import org.log2code.core.model.Level;
import org.log2code.core.model.ModuleInfo;
import org.log2code.core.model.TypeInfo;

/**
 * T19 tests against a synthetic project (bypasses OpenSearch entirely, built via {@link CatalogIndex#build}):
 * per-service applicability (0.10 step 0) and the {@code get_class}-through-an-abstract-superclass scenario
 * end to end via {@link CatalogIndex#byLogger}.
 */
class CatalogIndexTest {

    private static final CodeUnit PROJECT = new CodeUnit(CodeUnit.TYPE_PROJECT, "petclinic-fixture", "v1");
    private static final CodeUnit HIKARI = new CodeUnit(CodeUnit.TYPE_DEPENDENCY, "com.zaxxer:HikariCP", "7.0.2");
    private static final CodeUnit TOMCAT = new CodeUnit(CodeUnit.TYPE_DEPENDENCY, "org.apache.tomcat.embed:tomcat-embed-core", "11.0.15");

    private static final String ABSTRACT_RESOURCE = "org.log2code.fixture.customers.AbstractResource";
    private static final String OWNER_RESOURCE = "org.log2code.fixture.customers.OwnerResource";
    private static final String VET_RESOURCE = "org.log2code.fixture.vets.VetResource";
    private static final String HIKARI_POOL = "com.zaxxer.hikari.pool.HikariPool";
    private static final String TOMCAT_CLASS = "org.apache.tomcat.embed.core.SomeTomcatClass";

    private final AnalysisRun run = new AnalysisRun("run-1", CodeUnit.TYPE_PROJECT, PROJECT,
        "https://example.invalid/petclinic-fixture", "test-analyzer",
        Instant.parse("2026-09-24T10:00:00Z"), Instant.parse("2026-09-24T10:00:05Z"), 5000, Map.of(),
        List.of(
            new ModuleInfo("spring-petclinic-customers-service", "customers-service", List.of("src/main/java"),
                List.of(), List.of("com.zaxxer:HikariCP:7.0.2", "org.apache.tomcat.embed:tomcat-embed-core:11.0.15")),
            new ModuleInfo("spring-petclinic-vets-service", "vets-service", List.of("src/main/java"),
                List.of(), List.of("com.zaxxer:HikariCP:7.0.2"))));

    private final List<CatalogEntry> catalogEntries = List.of(
        // Project: customers-service module. AbstractResource logs via getClass() (get_class), and its
        // concrete subclass OwnerResource has its own, ordinary class-literal-logged statement.
        entry("stmt-abstract", PROJECT, "spring-petclinic-customers-service", "customers-service",
            ABSTRACT_RESOURCE, null, "get_class", "Handling request", "literal", false),
        entry("stmt-owner", PROJECT, "spring-petclinic-customers-service", "customers-service",
            OWNER_RESOURCE, OWNER_RESOURCE, "class_literal", "Saving owner {}", "placeholders", false),
        // Project: vets-service module.
        entry("stmt-vet", PROJECT, "spring-petclinic-vets-service", "vets-service",
            VET_RESOURCE, VET_RESOURCE, "class_literal", "Saving vet {}", "placeholders", false),
        // Dependency selected by both services.
        entry("stmt-hikari", HIKARI, "com.zaxxer:HikariCP", null,
            HIKARI_POOL, HIKARI_POOL, "class_literal", "Added connection {}", "placeholders", false),
        // Dependency selected only by customers-service.
        entry("stmt-tomcat", TOMCAT, "org.apache.tomcat.embed:tomcat-embed-core", null,
            TOMCAT_CLASS, TOMCAT_CLASS, "class_literal", "Starting {}", "placeholders", false),
        // Excluded at load time regardless of service (0.10 step 0).
        entry("stmt-unsupported", PROJECT, "spring-petclinic-customers-service", "customers-service",
            OWNER_RESOURCE, null, "unknown", "n/a", "unsupported", false));

    private final List<TypeInfo> types = List.of(
        new TypeInfo("type-abstract", PROJECT, "spring-petclinic-customers-service", "AbstractResource.java",
            ABSTRACT_RESOURCE, ABSTRACT_RESOURCE, null, List.of(), "class"),
        new TypeInfo("type-owner", PROJECT, "spring-petclinic-customers-service", "OwnerResource.java",
            OWNER_RESOURCE, OWNER_RESOURCE, ABSTRACT_RESOURCE, List.of(), "class"),
        new TypeInfo("type-vet", PROJECT, "spring-petclinic-vets-service", "VetResource.java",
            VET_RESOURCE, VET_RESOURCE, null, List.of(), "class"));

    private final CatalogIndex index = CatalogIndex.build(run, catalogEntries, types);

    @Test
    void unsupportedStatementsAreExcludedFromTheTotalCount() {
        // 5 supported statements loaded (abstract, owner, vet, hikari, tomcat) - stmt-unsupported never
        // enters the index at all, and hikari is counted once even though 2 services can both see it.
        assertThat(index.statementCount()).isEqualTo(5);
    }

    @Test
    void applicabilityIsScopedPerServiceProjectModulePlusItsOwnSelectedDependencies() {
        // customers-service: its own 2 project statements + both of its selected dependencies.
        assertThat(index.applicableStatementCount("customers-service")).isEqualTo(4);
        // vets-service: its own 1 project statement + only the dependency it selected (not tomcat).
        assertThat(index.applicableStatementCount("vets-service")).isEqualTo(2);
    }

    @Test
    void aProjectStatementFromAnotherServicesModuleIsNeverApplicable() {
        List<CatalogKey> vetsCandidates = index.byLogger(Set.of(VET_RESOURCE, OWNER_RESOURCE), "vets-service");

        assertThat(vetsCandidates).extracting(CatalogKey::statementId).containsExactly("stmt-vet");
    }

    @Test
    void aDependencyStatementIsOnlyApplicableToServicesThatSelectedItsExactCodeUnit() {
        List<CatalogKey> vetsCandidates = index.byLogger(Set.of(TOMCAT_CLASS), "vets-service");

        assertThat(vetsCandidates).isEmpty(); // vets-service never selected tomcat-embed-core
    }

    @Test
    void ancestorsClimbsFromTheConcreteSubclassToTheAbstractBase() {
        assertThat(index.ancestors(OWNER_RESOURCE)).containsExactly(ABSTRACT_RESOURCE);
    }

    @Test
    void byLoggerFindsAGetClassStatementInAnAbstractSuperclassViaTheConcreteSubclassName() {
        // 0.10 step 2's third byLogger condition: logger_name_kind=get_class and the entry's own
        // class_fqn (AbstractResource) is an ancestor of the resolved runtime logger name (OwnerResource).
        LoggerResolver.Resolution resolution = index.loggerResolver().resolve(OWNER_RESOURCE, "customers-service");
        assertThat(resolution.kind()).isEqualTo(LoggerResolver.Kind.EXACT);

        List<CatalogKey> candidates = index.byLogger(resolution.names(), "customers-service");

        assertThat(candidates).extracting(CatalogKey::statementId)
            .containsExactlyInAnyOrder("stmt-owner", "stmt-abstract");
    }

    @Test
    void byTokensIsScopedPerServiceTooAndRanksByIdf() {
        List<CatalogKey> ranked = index.byTokens(List.of("saving", "owner"), "customers-service", 10);

        assertThat(ranked).extracting(CatalogKey::statementId).containsExactly("stmt-owner");
        // "owner" is unique to the customers-only statement; querying it against vets-service (whose
        // applicable set never even loaded that statement) must never surface it.
        assertThat(index.byTokens(List.of("owner"), "vets-service", 10)).isEmpty();
    }

    @Test
    void loggerResolverNamesIncludeBothCatalogLoggerNamesAndAllProjectTypes() {
        // AbstractResource has no logger_name of its own (get_class), but it is still a known name via
        // log2code-types (T19 step 1: "class_fqn/class_binary iz log2code-types").
        LoggerResolver.Resolution resolution = index.loggerResolver().resolve(ABSTRACT_RESOURCE, "customers-service");

        assertThat(resolution.kind()).isEqualTo(LoggerResolver.Kind.EXACT);
    }

    private static CatalogEntry entry(String statementId, CodeUnit codeUnit, String module, String service,
                                       String classFqn, String loggerName, String loggerNameKind,
                                       String template, String templateKind, boolean hasThrowableArg) {
        return new CatalogEntry(
            statementId, statementId + "-logical", codeUnit, module, service,
            "Fixture.java", "file-" + statementId, "pkg", classFqn, classFqn,
            "run", "run()", null, false,
            10, 10, 4, 5, 15,
            "slf4j", "typed", "log", loggerName, loggerNameKind,
            Level.INFO, false,
            "\"" + template + "\"", template, templateKind, null, null, List.of(), template.length(), 0, hasThrowableArg,
            new EnclosingBlock("method", null, null, 5, 15), null,
            "    log.info(...);\n", 10,
            null, "test-analyzer", Instant.parse("2026-09-24T10:00:00Z"));
    }
}
