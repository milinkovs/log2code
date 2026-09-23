package org.log2code.analyzer.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URISyntaxException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.log2code.analyzer.catalog.ProjectCatalogBuilder.Result;
import org.log2code.analyzer.modules.ModuleScanner;
import org.log2code.core.model.CatalogEntry;
import org.log2code.core.model.CodeUnit;
import org.log2code.core.model.ModuleInfo;
import org.log2code.core.model.SourceFile;
import org.log2code.core.model.TypeInfo;

/**
 * End-to-end against {@code src/test/resources/fixtures/mini-project/} (T10 step 1-4, without the
 * OpenSearch write - see {@code CatalogAssemblyIT} for that): two modules, cross-file inheritance
 * (inherited logger field), an interface/enum/record/annotation, a repeated template in one method
 * (0.8 ordinal), and an anonymous class (0.8 {@code class_binary} best estimate).
 */
class ProjectCatalogBuilderTest {

    private static final int SNIPPET_LINES = 3;
    private static final String ANALYZER_VERSION = "test-analyzer";

    private final Path projectRoot = fixtureRoot();
    private final List<ModuleInfo> modules = new ModuleScanner().scan(projectRoot, List.of(), List.of());

    private Result build(String version) {
        CodeUnit codeUnit = new CodeUnit(CodeUnit.TYPE_PROJECT, "mini-project", version);
        return ProjectCatalogBuilder.build(projectRoot, modules, codeUnit, SNIPPET_LINES, ANALYZER_VERSION, Instant.parse("2026-09-23T10:00:00Z"));
    }

    private static CatalogEntry byTemplateAndLine(List<CatalogEntry> catalog, String template, int minLine) {
        return catalog.stream()
            .filter(e -> template.equals(e.template()) && e.line() >= minLine)
            .findFirst()
            .orElseThrow(() -> new AssertionError("no entry with template '" + template + "' at/after line " + minLine + " in " + catalog));
    }

    @Test
    void findsBothModules() {
        assertThat(modules).extracting(ModuleInfo::module).containsExactlyInAnyOrder("module-a", "module-b");
    }

    @Test
    void catalogHasOneEntryPerLogStatement() {
        Result result = build("v1");
        assertThat(result.catalog()).hasSize(10);
    }

    @Test
    void sourcesCoverEveryJavaFile() {
        Result result = build("v1");
        assertThat(result.sources()).hasSize(7);
        assertThat(result.sources()).extracting(SourceFile::filePath)
            .contains("module-a/src/main/java/org/log2code/fixture/mini/ServiceA.java",
                "module-b/src/main/java/org/log2code/fixture/mini/other/ServiceB.java");
        SourceFile serviceA = result.sources().stream()
            .filter(f -> f.filePath().endsWith("ServiceA.java")).findFirst().orElseThrow();
        assertThat(serviceA.content()).contains("package org.log2code.fixture.mini;");
        assertThat(serviceA.lineCount()).isGreaterThan(0);
        assertThat(serviceA.sha256()).hasSize(64);
    }

    @Test
    void typesCoverEveryKind() {
        Result result = build("v1");
        assertThat(result.types()).hasSize(7);
        Map<String, TypeInfo> byFqn = result.types().stream()
            .collect(java.util.stream.Collectors.toMap(TypeInfo::classFqn, t -> t));

        assertThat(byFqn.get("org.log2code.fixture.mini.Greeter").kind()).isEqualTo("interface");
        assertThat(byFqn.get("org.log2code.fixture.mini.Mood").kind()).isEqualTo("enum");
        assertThat(byFqn.get("org.log2code.fixture.mini.Point").kind()).isEqualTo("record");
        assertThat(byFqn.get("org.log2code.fixture.mini.MiniMarker").kind()).isEqualTo("annotation");
        assertThat(byFqn.get("org.log2code.fixture.mini.LoggingBase").kind()).isEqualTo("class");
        assertThat(byFqn.get("org.log2code.fixture.mini.LoggingBase").superclassFqn()).isNull();

        TypeInfo serviceA = byFqn.get("org.log2code.fixture.mini.ServiceA");
        assertThat(serviceA.kind()).isEqualTo("class");
        assertThat(serviceA.superclassFqn()).isEqualTo("org.log2code.fixture.mini.LoggingBase");
        assertThat(serviceA.interfaces()).containsExactly("org.log2code.fixture.mini.Greeter");

        TypeInfo serviceB = byFqn.get("org.log2code.fixture.mini.other.ServiceB");
        assertThat(serviceB.superclassFqn()).isNull();
        assertThat(serviceB.interfaces()).isEmpty();
    }

    @Test
    void statsCountEverything() {
        Result result = build("v1");
        Map<String, Object> stats = result.stats();

        assertThat(stats.get("module_count")).isEqualTo(2);
        assertThat(stats.get("file_count")).isEqualTo(7);
        assertThat(stats.get("parse_error_count")).isEqualTo(0L);
        assertThat(stats.get("statement_count")).isEqualTo(10);
        assertThat(stats.get("calls_by_logging_api")).isEqualTo(Map.of("slf4j", 10L));
        assertThat(stats.get("calls_by_detection")).isEqualTo(Map.of("inherited", 9L, "typed", 1L));
        assertThat(stats.get("unsupported_by_reason")).isEqualTo(Map.of());
        @SuppressWarnings("unchecked")
        Map<String, Long> byKind = (Map<String, Long>) stats.get("calls_by_template_kind");
        assertThat(byKind.get("literal") + byKind.get("placeholders")).isEqualTo(10L);
    }

    @Test
    void repeatedTemplateInSameMethodGetsDistinctOrdinals() {
        List<CatalogEntry> pingEntries = build("v1").catalog().stream()
            .filter(e -> "ping".equals(e.template())).toList();
        assertThat(pingEntries).hasSize(2);
        assertThat(pingEntries.get(0).statementId()).isNotEqualTo(pingEntries.get(1).statementId());
        assertThat(pingEntries.get(0).logicalId()).isNotEqualTo(pingEntries.get(1).logicalId());
        assertThat(pingEntries).extracting(CatalogEntry::line).doesNotHaveDuplicates();
    }

    @Test
    void anonymousClassGetsEstimatedBinaryName() {
        CatalogEntry entry = byTemplateAndLine(build("v1").catalog(), "anon running", 1);
        assertThat(entry.classFqn()).isEqualTo("org.log2code.fixture.mini.ServiceA$1");
        assertThat(entry.classBinary()).isEqualTo("org.log2code.fixture.mini.ServiceA$1");
        assertThat(entry.methodName()).isEqualTo("run");
        assertThat(entry.methodSignature()).isEqualTo("run()");
    }

    @Test
    void enclosingBlockIsResolvedPerStatement() {
        List<CatalogEntry> catalog = build("v1").catalog();

        assertThat(byTemplateAndLine(catalog, "Processing {}", 1).enclosing().blockKind()).isEqualTo("method");
        assertThat(byTemplateAndLine(catalog, "Flag set for {}", 1).enclosing().blockKind()).isEqualTo("if");
        assertThat(byTemplateAndLine(catalog, "Flag set for {}", 1).enclosing().branch()).isEqualTo("then");
        assertThat(byTemplateAndLine(catalog, "Flag not set for {}", 1).enclosing().branch()).isEqualTo("else");
        assertThat(byTemplateAndLine(catalog, "looping", 1).enclosing().blockKind()).isEqualTo("for");

        CatalogEntry failed = byTemplateAndLine(catalog, "Failed processing {}", 1);
        assertThat(failed.enclosing().blockKind()).isEqualTo("catch");
        assertThat(failed.enclosing().condition()).isEqualTo("IllegalStateException");
        assertThat(failed.hasThrowableArg()).isTrue();
    }

    @Test
    void snippetSurroundsTheStatementByConfiguredLines() {
        CatalogEntry entry = byTemplateAndLine(build("v1").catalog(), "Handling {}", 1);
        assertThat(entry.snippetStartLine()).isEqualTo(Math.max(1, entry.line() - SNIPPET_LINES));
        assertThat(entry.snippet().lines().count()).isLessThanOrEqualTo(2L * SNIPPET_LINES + 1);
        assertThat(entry.snippet()).contains("Handling");
    }

    @Test
    void secondBuildIsIdempotent() {
        List<CatalogEntry> first = build("v1").catalog().stream()
            .sorted(java.util.Comparator.comparing(CatalogEntry::statementId)).toList();
        List<CatalogEntry> second = build("v1").catalog().stream()
            .sorted(java.util.Comparator.comparing(CatalogEntry::statementId)).toList();
        assertThat(first).extracting(CatalogEntry::statementId).containsExactlyElementsOf(
            second.stream().map(CatalogEntry::statementId).toList());
    }

    /** AC4: {@code statement_id} changes when {@code code_unit.version} changes; {@code logical_id} does not. */
    @Test
    void statementIdChangesWithVersionButLogicalIdDoesNot() {
        CatalogEntry v1 = byTemplateAndLine(build("v1").catalog(), "Handling {}", 1);
        CatalogEntry v2 = byTemplateAndLine(build("v2").catalog(), "Handling {}", 1);

        assertThat(v1.statementId()).isNotEqualTo(v2.statementId());
        assertThat(v1.logicalId()).isEqualTo(v2.logicalId());
    }

    private static Path fixtureRoot() {
        try {
            return Paths.get(ProjectCatalogBuilderTest.class.getResource("/fixtures/mini-project/pom.xml").toURI()).getParent();
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }
}
