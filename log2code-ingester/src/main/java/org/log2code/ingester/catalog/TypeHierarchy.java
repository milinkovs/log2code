package org.log2code.ingester.catalog;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.log2code.core.model.TypeInfo;

/**
 * The superclass chain across every applicable {@code log2code-types} document (T19 step 4), used by
 * {@link CatalogIndex#ancestors(String)} - 0.10 step 2's {@code get_class} condition needs it to tell
 * whether a resolved logger name {@code l} (typically obtained via {@code getClass()} in a subclass) is
 * itself, or a subclass of, the class a candidate log statement's {@code class_fqn} actually belongs to.
 *
 * <p>Looked up by plain FQN, without code-unit scoping: two different jars coincidentally declaring the
 * exact same fully-qualified class name is not a real-world case this project's dependency set hits, so
 * the (deterministic, first-loaded-wins) collision is an accepted simplification, not a correctness bug
 * for the data this runs against.
 */
final class TypeHierarchy {

    /** 0.10/T19 step 4: "najviše 20 nivoa". */
    static final int MAX_DEPTH = 20;

    private final Map<String, String> superclassByClassFqn;

    private TypeHierarchy(Map<String, String> superclassByClassFqn) {
        this.superclassByClassFqn = superclassByClassFqn;
    }

    static TypeHierarchy build(List<TypeInfo> types) {
        Map<String, String> byFqn = new HashMap<>();
        for (TypeInfo type : types) {
            if (type.superclassFqn() != null) {
                byFqn.putIfAbsent(type.classFqn(), type.superclassFqn());
            }
        }
        return new TypeHierarchy(byFqn);
    }

    /**
     * The chain of proper superclasses of {@code classFqn}, nearest first, NOT including {@code classFqn}
     * itself (0.10 step 2 unions it in separately: {@code class_fqn ∈ ancestors(l) ∪ {l}}). Stops at
     * {@link #MAX_DEPTH} levels or the first already-seen class (cycle guard); an unknown or non-FQN
     * {@code classFqn} simply yields an empty chain ("best effort", T19 step 4).
     */
    List<String> ancestors(String classFqn) {
        if (classFqn == null) {
            return List.of();
        }
        List<String> chain = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        visited.add(classFqn);
        String current = classFqn;
        for (int depth = 0; depth < MAX_DEPTH; depth++) {
            String superFqn = superclassByClassFqn.get(current);
            if (superFqn == null || !visited.add(superFqn)) {
                break;
            }
            chain.add(superFqn);
            current = superFqn;
        }
        return List.copyOf(chain);
    }
}
