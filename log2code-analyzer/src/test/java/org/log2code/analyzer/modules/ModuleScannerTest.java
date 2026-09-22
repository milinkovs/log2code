package org.log2code.analyzer.modules;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URISyntaxException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.log2code.analyzer.CliUserException;
import org.log2code.core.model.ModuleInfo;

/** Against {@code src/test/resources/fixtures/multimodule/} (0.12 test spec: one module without src/main/java, one with .properties). */
class ModuleScannerTest {

    private final ModuleScanner scanner = new ModuleScanner();
    private final Path projectRoot = fixtureRoot();

    @Test
    void skipsModuleWithoutSrcMainJava() {
        List<ModuleInfo> modules = scanner.scan(projectRoot, List.of(), List.of());

        assertThat(modules).extracting(ModuleInfo::module).doesNotContain("module-without-src");
    }

    @Test
    void findsModuleWithYamlApplicationName() {
        List<ModuleInfo> modules = scanner.scan(projectRoot, List.of(), List.of());

        ModuleInfo module = byName(modules, "module-with-code");
        assertThat(module.service()).isEqualTo("code-service");
        assertThat(module.sourceRoots()).containsExactly("src/main/java");
        assertThat(module.dependencies()).isEmpty();
        assertThat(module.selectedDependencies()).isEmpty();
    }

    @Test
    void findsModuleWithPropertiesApplicationName() {
        List<ModuleInfo> modules = scanner.scan(projectRoot, List.of(), List.of());

        ModuleInfo module = byName(modules, "module-with-properties");
        assertThat(module.service()).isEqualTo("props-service");
    }

    @Test
    void moduleWithoutAnyApplicationFileHasNullService() {
        List<ModuleInfo> modules = scanner.scan(projectRoot, List.of(), List.of());

        ModuleInfo module = byName(modules, "module-without-name");
        assertThat(module.service()).isNull();
    }

    @Test
    void countsOnlyJavaFilesUnderSrcMainJava() {
        long count = ModuleScanner.countJavaFiles(projectRoot.resolve("module-with-code"));

        // Foo.java and Bar.java under src/main/java; FooTest.java under src/test/java must not be counted.
        assertThat(count).isEqualTo(2);
    }

    @Test
    void includeModulesRestrictsToNamedModules() {
        List<ModuleInfo> modules = scanner.scan(projectRoot, List.of("module-with-code"), List.of());

        assertThat(modules).extracting(ModuleInfo::module).containsExactly("module-with-code");
    }

    @Test
    void excludeModulesRemovesNamedModules() {
        List<ModuleInfo> modules = scanner.scan(projectRoot, List.of(), List.of("module-with-code"));

        assertThat(modules).extracting(ModuleInfo::module)
            .containsExactlyInAnyOrder("module-with-properties", "module-without-name");
    }

    @Test
    void missingRootPomIsAUserError(@org.junit.jupiter.api.io.TempDir Path emptyDir) {
        assertThatThrownBy(() -> scanner.scan(emptyDir, List.of(), List.of()))
            .isInstanceOf(CliUserException.class)
            .hasMessageContaining("pom.xml");
    }

    private static ModuleInfo byName(List<ModuleInfo> modules, String name) {
        return modules.stream().filter(m -> m.module().equals(name)).findFirst()
            .orElseThrow(() -> new AssertionError("module not found: " + name + " in " + modules));
    }

    private static Path fixtureRoot() {
        try {
            return Paths.get(ModuleScannerTest.class.getResource("/fixtures/multimodule/pom.xml").toURI()).getParent();
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }
}
