package org.log2code.ingester.enrich;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.log2code.core.github.CodeUnitsConfig;
import org.log2code.core.github.GithubLinker;
import org.log2code.core.ids.StableIds;
import org.log2code.core.model.AnalysisRun;
import org.log2code.core.model.CausedBy;
import org.log2code.core.model.CodeUnit;
import org.log2code.core.model.ExceptionInfo;
import org.log2code.core.model.ModuleInfo;
import org.log2code.core.model.StackFrame;
import org.log2code.core.model.TypeInfo;
import org.log2code.ingester.catalog.CatalogIndex;

/**
 * T21's "Rezolucija stack frame-ova" step, against a synthetic {@link CatalogIndex} (bypasses OpenSearch,
 * same pattern as {@code CatalogIndexTest}) and a synthetic {@code config/code-units.yml}. The
 * dependency-source-exists check is injected as a plain predicate (no OpenSearch needed for this unit
 * test), the same testability pattern T15's {@code LinksVerifyRunner} already used for its {@code UrlChecker}.
 */
class StackFrameResolverTest {

    private static final CodeUnit PROJECT = new CodeUnit(CodeUnit.TYPE_PROJECT, "petclinic-fixture", "v1");
    private static final CodeUnit HIKARI = new CodeUnit(CodeUnit.TYPE_DEPENDENCY, "com.zaxxer:HikariCP", "7.0.2");
    private static final String OWNER_RESOURCE = "org.log2code.fixture.customers.OwnerResource";
    private static final String OWNER_FILE_PATH = "spring-petclinic-customers-service/src/main/java/org/log2code/fixture/customers/OwnerResource.java";
    private static final String HIKARI_POOL = "com.zaxxer.hikari.pool.HikariPool";
    private static final String HIKARI_FILE_PATH = "com/zaxxer/hikari/pool/HikariPool.java";
    private static final String UNKNOWN_CLASS = "java.lang.Thread";

    private final AnalysisRun run = new AnalysisRun("run-1", CodeUnit.TYPE_PROJECT, PROJECT,
        "https://example.invalid/petclinic-fixture", "test-analyzer",
        Instant.parse("2026-09-24T10:00:00Z"), Instant.parse("2026-09-24T10:00:05Z"), 5000, Map.of(),
        List.of(new ModuleInfo("spring-petclinic-customers-service", "customers-service",
            List.of("src/main/java"), List.of(), List.of("com.zaxxer:HikariCP:7.0.2"))));

    private final CatalogIndex index = CatalogIndex.build(run, List.of(), List.of(
        new TypeInfo("type-owner", PROJECT, "spring-petclinic-customers-service", OWNER_FILE_PATH,
            OWNER_RESOURCE, OWNER_RESOURCE, null, List.of(), "class"),
        new TypeInfo("type-hikari", HIKARI, "com.zaxxer:HikariCP", HIKARI_FILE_PATH,
            HIKARI_POOL, HIKARI_POOL, null, List.of(), "class")));

    private final CodeUnitsConfig codeUnitsConfig = new CodeUnitsConfig(
        Map.of("petclinic-fixture", new CodeUnitsConfig.ProjectMapping(
            "https://github.com/example/petclinic-fixture", "{version}", "{file_path}")),
        List.of(new CodeUnitsConfig.DependencyMapping("com.zaxxer:*",
            "https://github.com/brettwooldridge/HikariCP", "dev", "src/main/java/{file_path}")));

    private final GithubLinker linker = new GithubLinker(codeUnitsConfig, null);

    @Test
    void resolvesAProjectFrameAlwaysSettingFileIdWithoutCheckingSourcesExistence() {
        Set<String> checked = new HashSet<>();
        StackFrameResolver resolver = new StackFrameResolver(index, linker, id -> {
            checked.add(id);
            return false;
        });

        ExceptionInfo resolved = resolver.resolve(exceptionWith(frame(OWNER_RESOURCE, "OwnerResource.java", 89)), "customers-service");
        StackFrame result = resolved.frames().get(0);

        assertThat(result.inProject()).isTrue();
        assertThat(result.codeUnit()).isEqualTo("petclinic-fixture");
        assertThat(result.fileId()).isEqualTo(StableIds.fileId("petclinic-fixture", "v1", OWNER_FILE_PATH));
        assertThat(result.githubUrl()).isEqualTo(
            "https://github.com/example/petclinic-fixture/blob/v1/" + OWNER_FILE_PATH + "#L89");
        assertThat(checked).isEmpty(); // project sources are never gated on log2code-sources presence.
    }

    @Test
    void resolvesADependencyFrameFileIdOnlyWhenItsSourceExists() {
        StackFrameResolver withSource = new StackFrameResolver(index, linker, id -> true);
        StackFrameResolver withoutSource = new StackFrameResolver(index, linker, id -> false);
        StackFrame frame = frame(HIKARI_POOL, "HikariPool.java", 200);

        StackFrame withFileId = withSource.resolve(exceptionWith(frame), "customers-service").frames().get(0);
        StackFrame withoutFileId = withoutSource.resolve(exceptionWith(frame), "customers-service").frames().get(0);

        assertThat(withFileId.inProject()).isFalse();
        assertThat(withFileId.codeUnit()).isEqualTo("com.zaxxer:HikariCP");
        assertThat(withFileId.fileId()).isEqualTo(StableIds.fileId("com.zaxxer:HikariCP", "7.0.2", HIKARI_FILE_PATH));
        assertThat(withoutFileId.fileId()).isNull();
        // github_url does not depend on whether we cached the file: same value either way.
        String expectedUrl = "https://github.com/brettwooldridge/HikariCP/blob/dev/src/main/java/" + HIKARI_FILE_PATH + "#L200";
        assertThat(withFileId.githubUrl()).isEqualTo(expectedUrl);
        assertThat(withoutFileId.githubUrl()).isEqualTo(expectedUrl);
    }

    @Test
    void cachesTheDependencySourceExistsCheckPerFileId() {
        AtomicInteger calls = new AtomicInteger();
        StackFrameResolver resolver = new StackFrameResolver(index, linker, id -> {
            calls.incrementAndGet();
            return true;
        });
        StackFrame frame = frame(HIKARI_POOL, "HikariPool.java", 200);
        ExceptionInfo exception = new ExceptionInfo("java.lang.RuntimeException", "java.lang.RuntimeException",
            "boom", List.of(frame, frame), List.of());

        resolver.resolve(exception, "customers-service");

        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void aClassOutsideTheApplicableCatalogIsLeftUnchanged() {
        StackFrameResolver resolver = new StackFrameResolver(index, linker, id -> true);
        StackFrame frame = frame(UNKNOWN_CLASS, "Thread.java", 1583);

        StackFrame result = resolver.resolve(exceptionWith(frame), "customers-service").frames().get(0);

        assertThat(result).isEqualTo(frame);
    }

    @Test
    void aFrameWithNoLineNumberGetsNoGithubUrlButStillResolvesTheRest() {
        StackFrameResolver resolver = new StackFrameResolver(index, linker, id -> true);
        StackFrame frame = new StackFrame(OWNER_RESOURCE, "updateOwner", null, null, false, null, null, null);

        StackFrame result = resolver.resolve(exceptionWith(frame), "customers-service").frames().get(0);

        assertThat(result.githubUrl()).isNull();
        assertThat(result.fileId()).isNotNull();
        assertThat(result.inProject()).isTrue();
    }

    @Test
    void resolvesCausedByFramesToo() {
        StackFrameResolver resolver = new StackFrameResolver(index, linker, id -> true);
        StackFrame causeFrame = frame(OWNER_RESOURCE, "OwnerResource.java", 50);
        ExceptionInfo exception = new ExceptionInfo("java.lang.RuntimeException", "java.lang.IllegalStateException",
            "boom", List.of(), List.of(new CausedBy("java.lang.IllegalStateException", "inner", List.of(causeFrame))));

        ExceptionInfo resolved = resolver.resolve(exception, "customers-service");

        assertThat(resolved.causedBy().get(0).frames().get(0).inProject()).isTrue();
    }

    @Test
    void resolveOfNullExceptionIsNull() {
        StackFrameResolver resolver = new StackFrameResolver(index, linker, id -> true);

        assertThat(resolver.resolve(null, "customers-service")).isNull();
    }

    private static StackFrame frame(String className, String file, int line) {
        return new StackFrame(className, "someMethod", file, line, false, null, null, null);
    }

    private static ExceptionInfo exceptionWith(StackFrame frame) {
        return new ExceptionInfo("java.lang.RuntimeException", "java.lang.RuntimeException", "boom", List.of(frame), List.of());
    }
}
