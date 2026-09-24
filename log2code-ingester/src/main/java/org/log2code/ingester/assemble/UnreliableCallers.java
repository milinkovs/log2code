package org.log2code.ingester.assemble;

import java.util.Set;

/**
 * {@code oracle.unreliable-callers} (T18 step 5), as a temporary constant: {@code config/matching.yml}
 * does not exist yet (T20 creates the final file and this list moves there). A caller class marks a
 * {@code ground_truth} as unreliable when it is a logging wrapper whose {@code %C}/{@code %M} caller
 * info names the wrapper itself, not the code that actually logged (e.g. a JCL bridge). Entries
 * ending in {@code .*} match by package/class prefix; others match the class exactly.
 *
 * <p>The base list is T18 step 5's own text. {@code org.apache.juli.logging.*} is added on top of
 * it: Tomcat's own JCL bridge ({@code DirectJDKLog}) is not in that text, but T16's progress entry
 * (real {@code smoke-oracle-01} data, e.g. {@code @@L2C[org.apache.juli.logging.DirectJDKLog|log|168]@@}
 * for a plain Tomcat startup line) found it appears constantly as the marker's caller and explicitly
 * flagged it for this exact step.
 */
final class UnreliableCallers {

    static final Set<String> DEFAULT = Set.of(
        "org.springframework.core.log.LogAccessor",
        "org.springframework.core.log.LogMessage",
        "org.apache.commons.logging.*",
        "org.slf4j.bridge.*",
        "org.apache.logging.slf4j.*",
        "java.util.logging.*",
        "org.jboss.logging.*",
        "org.apache.juli.logging.*"
    );

    private UnreliableCallers() {
    }

    static boolean matches(String className, Set<String> patterns) {
        if (className == null) {
            return false;
        }
        for (String pattern : patterns) {
            if (pattern.endsWith(".*")) {
                String prefix = pattern.substring(0, pattern.length() - 1);
                if (className.startsWith(prefix)) {
                    return true;
                }
            } else if (pattern.equals(className)) {
                return true;
            }
        }
        return false;
    }
}
