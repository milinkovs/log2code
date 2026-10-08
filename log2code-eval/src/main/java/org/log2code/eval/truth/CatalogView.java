package org.log2code.eval.truth;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.log2code.core.model.AnalysisRun;
import org.log2code.core.model.CatalogEntry;
import org.log2code.core.model.CodeUnit;
import org.log2code.core.model.ModuleInfo;

/**
 * The statements the matcher could have chosen for each service (0.10, step 0: the project's statements of
 * that service's module plus the selected dependencies of that module, at their exact versions), indexed by
 * class so that an oracle position ({@code class}, {@code line}) can be turned into the set of correct
 * statements (T33 step 2). Unlike the matcher's own index, statements with {@code template_kind =
 * unsupported} are kept here: they are in the catalog, the matcher just can never pick them.
 */
public final class CatalogView {

    private final Map<String, CatalogEntry> byId;
    private final Map<String, Map<String, List<CatalogEntry>>> byServiceAndClass;

    private CatalogView(Map<String, CatalogEntry> byId, Map<String, Map<String, List<CatalogEntry>>> byServiceAndClass) {
        this.byId = byId;
        this.byServiceAndClass = byServiceAndClass;
    }

    public static CatalogView build(AnalysisRun run, List<CatalogEntry> entries) {
        Map<String, CatalogEntry> byId = new HashMap<>();
        for (CatalogEntry entry : entries) {
            byId.put(entry.statementId(), entry);
        }

        Map<String, Map<String, List<CatalogEntry>>> byServiceAndClass = new LinkedHashMap<>();
        for (ModuleInfo module : run.modules()) {
            if (module.service() == null) {
                continue;
            }
            Set<CodeUnit> selected = new LinkedHashSet<>();
            for (String gav : module.selectedDependencies()) {
                selected.add(parseDependency(gav));
            }
            Map<String, List<CatalogEntry>> byClass = new HashMap<>();
            for (CatalogEntry entry : entries) {
                if (!applicable(entry, module.service(), selected)) {
                    continue;
                }
                index(byClass, entry.classBinary(), entry);
                if (!Objects.equals(entry.classBinary(), entry.classFqn())) {
                    index(byClass, entry.classFqn(), entry);
                }
            }
            byServiceAndClass.put(module.service(), byClass);
        }
        return new CatalogView(byId, byServiceAndClass);
    }

    /**
     * The statements applicable to {@code service} that sit in {@code className} and whose call spans
     * {@code line} ({@code line <= oracleLine <= end_line}). {@code className} is the binary name from the
     * oracle ({@code a.b.Outer$Inner}); a statement matches by {@code class_binary}, or by {@code class_fqn}
     * once {@code $} is replaced with {@code .}. The method is not compared: it would only differ from the
     * catalog for lambdas ({@code lambda$...}) and field initializers, and class plus line already identify
     * the call.
     */
    public List<CatalogEntry> statementsAt(String service, String className, int line) {
        Map<String, List<CatalogEntry>> byClass = byServiceAndClass.get(service);
        if (byClass == null || className == null) {
            return List.of();
        }
        Map<String, CatalogEntry> found = new LinkedHashMap<>();
        for (String key : List.of(className, className.replace('$', '.'))) {
            for (CatalogEntry entry : byClass.getOrDefault(key, List.of())) {
                if (entry.line() <= line && line <= entry.endLine()) {
                    found.put(entry.statementId(), entry);
                }
            }
        }
        List<CatalogEntry> result = new ArrayList<>(found.values());
        result.sort(Comparator.comparing(CatalogEntry::statementId));
        return result;
    }

    public Optional<CatalogEntry> byId(String statementId) {
        return Optional.ofNullable(byId.get(statementId));
    }

    /** Number of statements loaded, over all services (a statement shared by services counts once). */
    public int size() {
        return byId.size();
    }

    private static void index(Map<String, List<CatalogEntry>> byClass, String key, CatalogEntry entry) {
        if (key != null) {
            byClass.computeIfAbsent(key, k -> new ArrayList<>()).add(entry);
        }
    }

    private static boolean applicable(CatalogEntry entry, String service, Set<CodeUnit> selectedDependencies) {
        if (CodeUnit.TYPE_PROJECT.equals(entry.codeUnit().type())) {
            return service.equals(entry.service());
        }
        return selectedDependencies.contains(entry.codeUnit());
    }

    /** {@code groupId:artifactId:version} (the format of {@code ModuleInfo.selectedDependencies}) to a dependency code unit. */
    static CodeUnit parseDependency(String gav) {
        int firstColon = gav.indexOf(':');
        int secondColon = gav.indexOf(':', firstColon + 1);
        return new CodeUnit(CodeUnit.TYPE_DEPENDENCY, gav.substring(0, secondColon), gav.substring(secondColon + 1));
    }
}
