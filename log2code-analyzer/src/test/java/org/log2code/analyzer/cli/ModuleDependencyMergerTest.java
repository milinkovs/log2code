package org.log2code.analyzer.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.log2code.core.model.AnalysisRun;
import org.log2code.core.model.CodeUnit;
import org.log2code.core.model.ModuleInfo;

/** ADR-020: {@code project} must not drop {@code deps resolve}'s selected dependencies on a re-run. */
class ModuleDependencyMergerTest {

    private static final CodeUnit CODE_UNIT = new CodeUnit(CodeUnit.TYPE_PROJECT, "p", "v1");
    private static final Instant T0 = Instant.parse("2026-09-24T00:00:00Z");

    @Test
    void noExistingRunReturnsFreshModulesUnchanged() {
        List<ModuleInfo> fresh = List.of(module("mod-a", "svc-a", List.of(), List.of()));

        assertThat(ModuleDependencyMerger.merge(fresh, null)).isEqualTo(fresh);
    }

    @Test
    void existingModuleWithSelectedDependenciesIsCarriedOverOntoFreshScan() {
        List<ModuleInfo> fresh = List.of(module("mod-a", "svc-a", List.of(), List.of()));
        AnalysisRun existing = run(module("mod-a", "svc-a",
            List.of("g:a:1.0", "g:b:1.0"), List.of("g:a:1.0")));

        List<ModuleInfo> merged = ModuleDependencyMerger.merge(fresh, existing);

        assertThat(merged).hasSize(1);
        assertThat(merged.get(0).module()).isEqualTo("mod-a");
        assertThat(merged.get(0).service()).isEqualTo("svc-a");
        assertThat(merged.get(0).dependencies()).containsExactly("g:a:1.0", "g:b:1.0");
        assertThat(merged.get(0).selectedDependencies()).containsExactly("g:a:1.0");
    }

    @Test
    void existingModuleWithNoSelectedDependenciesLeavesFreshModuleAsIs() {
        List<ModuleInfo> fresh = List.of(module("mod-a", "svc-a", List.of(), List.of()));
        AnalysisRun existing = run(module("mod-a", "svc-a", List.of(), List.of()));

        List<ModuleInfo> merged = ModuleDependencyMerger.merge(fresh, existing);

        assertThat(merged).isEqualTo(fresh);
    }

    @Test
    void moduleAbsentFromExistingRunIsKeptAsFreshlyScanned() {
        List<ModuleInfo> fresh = List.of(module("mod-new", "svc-new", List.of(), List.of()));
        AnalysisRun existing = run(module("mod-a", "svc-a", List.of("g:a:1.0"), List.of("g:a:1.0")));

        List<ModuleInfo> merged = ModuleDependencyMerger.merge(fresh, existing);

        assertThat(merged).containsExactly(module("mod-new", "svc-new", List.of(), List.of()));
    }

    @Test
    void freshServiceAndSourceRootsWinOverExistingOnes() {
        List<ModuleInfo> fresh = List.of(
            new ModuleInfo("mod-a", "svc-a-renamed", List.of("src/main/java", "src/other"), List.of(), List.of()));
        AnalysisRun existing = run(module("mod-a", "svc-a-old", List.of("src/main/java"),
            List.of("g:a:1.0")));

        ModuleInfo merged = ModuleDependencyMerger.merge(fresh, existing).get(0);

        assertThat(merged.service()).isEqualTo("svc-a-renamed");
        assertThat(merged.sourceRoots()).containsExactly("src/main/java", "src/other");
        assertThat(merged.selectedDependencies()).containsExactly("g:a:1.0");
    }

    private static ModuleInfo module(String name, String service, List<String> deps, List<String> selected) {
        return new ModuleInfo(name, service, List.of("src/main/java"), deps, selected);
    }

    private static AnalysisRun run(ModuleInfo... modules) {
        return new AnalysisRun("run-1", CodeUnit.TYPE_PROJECT, CODE_UNIT, "https://example.test/repo",
            "test-analyzer", T0, T0, 0L, Map.of(), List.of(modules));
    }
}
