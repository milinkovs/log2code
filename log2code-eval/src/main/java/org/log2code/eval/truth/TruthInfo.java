package org.log2code.eval.truth;

import org.log2code.core.model.CatalogEntry;

/**
 * The catalog attributes of an event's (primary) ground-truth statement, used to break the metrics down
 * (T33 step 3). All fields are {@code null} except {@code statementId} when the statement is not in the
 * loaded catalog (possible only for manual labels).
 */
public record TruthInfo(
    String statementId,
    String codeUnitType,
    String artifact,
    String loggingApi,
    String templateKind,
    String classFqn,
    String methodName,
    Integer line,
    String template
) {

    public static TruthInfo of(CatalogEntry entry) {
        return new TruthInfo(entry.statementId(), entry.codeUnit().type(), entry.codeUnit().name(), entry.loggingApi(),
            entry.templateKind(), entry.classFqn(), entry.methodName(), entry.line(), entry.template());
    }

    public static TruthInfo unknown(String statementId) {
        return new TruthInfo(statementId, null, null, null, null, null, null, null, null);
    }
}
