package org.log2code.analyzer.cli;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.log2code.core.model.AnalysisRun;
import org.log2code.core.model.ModuleInfo;

/**
 * Preserves {@code deps resolve}'s (T12) {@code dependencies}/{@code selectedDependencies} across a
 * later {@code project} re-run. {@link ModuleScanner} rebuilds {@code modules[]} from scratch on every
 * {@code project} run and knows nothing about resolved dependencies, so a plain overwrite of the
 * {@code log2code-runs} document (T13's {@link RunWriter}) silently drops them - exactly the gap ADR-013
 * documented and ADR-020 fixes by merging in whatever the previous {@link AnalysisRun} already had, per
 * module, before the fresh run is written.
 */
final class ModuleDependencyMerger {

    private ModuleDependencyMerger() {
    }

    /**
     * For each freshly scanned module, keeps its own {@code service}/{@code sourceRoots} but carries
     * over {@code dependencies}/{@code selectedDependencies} from the matching module (by name) in
     * {@code existing}, unless the existing module has no selected dependencies (nothing to preserve,
     * e.g. before {@code deps resolve} ever ran). Modules that only exist in {@code existing} (removed
     * from the project) or only in {@code freshModules} (new) are left as-is; {@code existing == null}
     * (no prior run) returns {@code freshModules} unchanged.
     */
    static List<ModuleInfo> merge(List<ModuleInfo> freshModules, AnalysisRun existing) {
        if (existing == null || existing.modules() == null || existing.modules().isEmpty()) {
            return freshModules;
        }
        Map<String, ModuleInfo> priorByModule = new HashMap<>();
        for (ModuleInfo prior : existing.modules()) {
            priorByModule.putIfAbsent(prior.module(), prior);
        }
        return freshModules.stream()
            .map(fresh -> {
                ModuleInfo prior = priorByModule.get(fresh.module());
                if (prior == null || prior.selectedDependencies() == null || prior.selectedDependencies().isEmpty()) {
                    return fresh;
                }
                return new ModuleInfo(fresh.module(), fresh.service(), fresh.sourceRoots(),
                    prior.dependencies(), prior.selectedDependencies());
            })
            .toList();
    }
}
