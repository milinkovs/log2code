package org.log2code.analyzer.deps;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URISyntaxException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.log2code.analyzer.modules.ModuleScanner;
import org.log2code.core.logger.LoggerNameMatcher;
import org.log2code.core.model.ModuleInfo;

/** Against {@code src/test/resources/fixtures/mini-project/} (shared with {@code ProjectCatalogBuilderTest}, T10). */
class ProjectClassIndexTest {

    private final Path projectRoot = fixtureRoot();
    private final List<ModuleInfo> modules = new ModuleScanner().scan(projectRoot, List.of(), List.of());

    @Test
    void collectsTopLevelClassesFromEveryModule() {
        Set<String> fqns = ProjectClassIndex.classFqns(projectRoot, modules);

        assertThat(fqns).contains(
            "org.log2code.fixture.mini.Greeter",
            "org.log2code.fixture.mini.LoggingBase",
            "org.log2code.fixture.mini.MiniMarker",
            "org.log2code.fixture.mini.Mood",
            "org.log2code.fixture.mini.Point",
            "org.log2code.fixture.mini.ServiceA",
            "org.log2code.fixture.mini.other.ServiceB");
    }

    @Test
    void loggerRawResolvesAgainstTheCollectedProjectFqns() {
        Set<String> fqns = ProjectClassIndex.classFqns(projectRoot, modules);

        // Same rule T12's DependencySelector uses to skip project loggers (abbreviated form, 0.10 step 1).
        assertThat(fqns).anyMatch(fqn -> LoggerNameMatcher.matches("o.l.fixture.mini.ServiceA", fqn));
        assertThat(fqns).noneMatch(fqn -> LoggerNameMatcher.matches("com.zaxxer.hikari.HikariDataSource", fqn));
    }

    private static Path fixtureRoot() {
        try {
            return Paths.get(ProjectClassIndexTest.class.getResource("/fixtures/mini-project/pom.xml").toURI()).getParent();
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }
}
