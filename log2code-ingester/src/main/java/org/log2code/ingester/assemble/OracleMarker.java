package org.log2code.ingester.assemble;

import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.log2code.core.model.GroundTruth;

/**
 * Recognizes and strips the oracle marker (0.9, T16) that, in oracle datasets, is inserted
 * immediately before the message by {@code infra/docker-compose.oracle.yml}'s
 * {@code LOGGING_PATTERN_CONSOLE}: {@code @@L2C[<class_fqn>|<method>|<line>]@@ }, always a prefix
 * of the event's first line (T18 step 5).
 */
final class OracleMarker {

    private static final Pattern MARKER = Pattern.compile("^@@L2C\\[([^|\\]]*)\\|([^|\\]]*)\\|([^|\\]]*)\\]@@ (.*)$");

    record Result(String remainder, GroundTruth groundTruth) {
    }

    private OracleMarker() {
    }

    /** Returns {@code null} when {@code firstLine} does not start with a marker. */
    static Result strip(String firstLine, Set<String> unreliableCallers) {
        Matcher m = MARKER.matcher(firstLine);
        if (!m.matches()) {
            return null;
        }
        String className = blank(m.group(1));
        String method = blank(m.group(2));
        String lineText = blank(m.group(3));
        Integer line = null;
        if (lineText != null) {
            try {
                line = Integer.parseInt(lineText);
            } catch (NumberFormatException e) {
                line = null;
            }
        }
        boolean reliable = className != null && line != null && !UnreliableCallers.matches(className, unreliableCallers);
        return new Result(m.group(4), new GroundTruth(className, method, line, reliable));
    }

    /** {@code "?"} (Logback's "unknown" placeholder for %C/%M/%L) and blank both mean "unavailable". */
    private static String blank(String value) {
        String trimmed = value.trim();
        return (trimmed.isEmpty() || trimmed.equals("?")) ? null : trimmed;
    }
}
