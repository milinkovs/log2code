package org.log2code.analyzer.logging;

import java.util.List;
import java.util.Map;

/**
 * One named type declaration within the code unit (T08 step 3, pass 1): its resolved superclass
 * (within the same code unit only; {@code null} if external, an interface, or {@code Object}), resolved
 * interfaces, its own (non-inherited) logger fields, and its {@code kind} (0.7: {@code class},
 * {@code interface}, {@code enum}, {@code record} or {@code annotation}). Public (T10 note, 0.13):
 * catalog assembly reuses this instead of duplicating superclass/interface resolution to build
 * {@code TypeInfo} documents.
 */
public record ClassInfo(
    String classFqn,
    String superclassFqn,
    List<String> interfaceFqns,
    Map<String, LoggerFieldInfo> loggerFields,
    String kind
) {
}
