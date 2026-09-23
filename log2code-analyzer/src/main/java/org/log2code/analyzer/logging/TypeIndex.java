package org.log2code.analyzer.logging;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * All named types of one code unit (T08 step 3, pass 1 output), keyed by FQN. Public (T10 note,
 * 0.13): catalog assembly reuses {@link #classesByFqn()} to build {@code TypeInfo} documents.
 */
public record TypeIndex(Map<String, ClassInfo> classesByFqn) {

    Optional<ClassInfo> get(String classFqn) {
        return Optional.ofNullable(classesByFqn.get(classFqn));
    }

    Set<String> knownFqns() {
        return classesByFqn.keySet();
    }
}
