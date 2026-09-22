package org.log2code.analyzer.logging;

import java.util.List;
import java.util.Map;

/**
 * One named type declaration within the code unit (T08 step 3, pass 1): its resolved superclass
 * (within the same code unit only; {@code null} if external, an interface, or {@code Object}), resolved
 * interfaces, and its own (non-inherited) logger fields.
 */
record ClassInfo(
    String classFqn,
    String superclassFqn,
    List<String> interfaceFqns,
    Map<String, LoggerFieldInfo> loggerFields
) {
}
