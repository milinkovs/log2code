package org.log2code.analyzer.graph;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.log2code.analyzer.logging.ClassInfo;
import org.log2code.analyzer.logging.TypeIndex;
import org.log2code.analyzer.logging.TypeKind;

/**
 * Reverse index, interface FQN to implementing project class FQNs (T13 step 3: "interfejs koji u
 * projektu ima tačno jednu implementaciju"), reusing T08's {@link TypeIndex} (already resolves
 * {@code implements}/{@code extends} clauses within the code unit, purely syntactically - no symbol
 * solver needed here) instead of re-deriving the hierarchy through the resolver. Transitive: a class
 * implementing interface {@code Sub extends Base} is recorded as an implementor of {@code Base} too
 * (found by recursing into an interface's own {@code interfaceFqns}, which {@code CodeUnitIndexer}
 * populates from its {@code extends} clause), and so is a subclass that itself declares no {@code
 * implements} clause but inherits one from its superclass.
 */
final class ProjectInterfaceIndex {

    private final Map<String, List<String>> implementorsByInterfaceFqn;

    private ProjectInterfaceIndex(Map<String, List<String>> implementorsByInterfaceFqn) {
        this.implementorsByInterfaceFqn = implementorsByInterfaceFqn;
    }

    static ProjectInterfaceIndex build(TypeIndex typeIndex) {
        Map<String, ClassInfo> classesByFqn = typeIndex.classesByFqn();
        Map<String, List<String>> result = new LinkedHashMap<>();
        for (ClassInfo classInfo : classesByFqn.values()) {
            if (TypeKind.INTERFACE.equals(classInfo.kind())) {
                continue; // only a concrete/abstract class can be an "implementation"
            }
            for (String interfaceFqn : transitiveInterfaces(classInfo.classFqn(), classesByFqn, new HashSet<>())) {
                result.computeIfAbsent(interfaceFqn, k -> new ArrayList<>()).add(classInfo.classFqn());
            }
        }
        return new ProjectInterfaceIndex(Map.copyOf(result));
    }

    /** Every project class implementing {@code interfaceFqn} (directly or transitively); empty if unknown. */
    List<String> implementorsOf(String interfaceFqn) {
        return implementorsByInterfaceFqn.getOrDefault(interfaceFqn, List.of());
    }

    private static Set<String> transitiveInterfaces(String classFqn, Map<String, ClassInfo> classesByFqn, Set<String> visited) {
        if (!visited.add(classFqn)) {
            return Set.of();
        }
        ClassInfo info = classesByFqn.get(classFqn);
        if (info == null) {
            return Set.of();
        }
        Set<String> result = new LinkedHashSet<>();
        for (String interfaceFqn : info.interfaceFqns()) {
            result.add(interfaceFqn);
            result.addAll(transitiveInterfaces(interfaceFqn, classesByFqn, visited));
        }
        if (info.superclassFqn() != null) {
            result.addAll(transitiveInterfaces(info.superclassFqn(), classesByFqn, visited));
        }
        return result;
    }
}
