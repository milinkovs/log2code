package org.log2code.analyzer.logging;

import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import org.log2code.core.model.Level;

/**
 * Resolves a {@code Level}-typed first argument, shared by the APIs whose method signature is
 * {@code log(Level, ...)} (JUL, Log4j2, System.Logger; step 4).
 */
final class LevelArgs {

    private LevelArgs() {
    }

    record Resolved(Level level, boolean dynamic) {
    }

    /**
     * A {@code Level.INFO}-style field access is a literal level, parsed via {@link Level#parse}
     * (yielding {@link Level#UNKNOWN} for a name we do not map, e.g. JUL's {@code ALL}/{@code OFF} -
     * still not "dynamic", just unrecognized). Any other expression shape (a variable, a method call)
     * is a runtime level: {@code level = UNKNOWN}, {@code dynamic = true} (step 4).
     */
    static Resolved resolve(Expression levelExpr) {
        if (levelExpr instanceof FieldAccessExpr fieldAccess) {
            return new Resolved(Level.parse(fieldAccess.getNameAsString()), false);
        }
        return new Resolved(Level.UNKNOWN, true);
    }
}
