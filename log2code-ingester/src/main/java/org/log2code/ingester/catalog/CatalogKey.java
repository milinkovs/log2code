package org.log2code.ingester.catalog;

import org.log2code.core.model.CatalogEntry;
import org.log2code.core.model.CodeUnit;
import org.log2code.core.model.Level;
import org.log2code.core.template.MessageTemplate;

/**
 * The fields of a {@link CatalogEntry} that 0.10 matching actually needs, kept in memory instead of the
 * full ~40-field document (T19 step 1). {@code template} is {@link MessageTemplate#parse(String) parsed}
 * once here so a matcher (T20) never recompiles a regex per candidate per event: it already exposes
 * {@code matchFull}/{@code matchPrefix}/{@code constantTokens}/{@code literalLength} directly.
 *
 * <p>{@code service} is the project module's service (0.7: {@code null} for a dependency entry, since a
 * dependency has no single owning service) - together with {@code codeUnit}, it is exactly what
 * {@link CatalogIndex}'s per-service applicability check (0.10 step 0) needs, without re-deriving it from
 * {@code module}.
 */
public record CatalogKey(
    String statementId,
    CodeUnit codeUnit,
    String service,
    String classFqn,
    String loggerName,
    String loggerNameKind,
    Level level,
    boolean levelDynamic,
    String templateKind,
    MessageTemplate template,
    boolean hasThrowableArg
) {

    static CatalogKey from(CatalogEntry entry) {
        return new CatalogKey(
            entry.statementId(),
            entry.codeUnit(),
            entry.service(),
            entry.classFqn(),
            entry.loggerName(),
            entry.loggerNameKind(),
            entry.level(),
            entry.levelDynamic(),
            entry.templateKind(),
            MessageTemplate.parse(entry.template()),
            entry.hasThrowableArg());
    }
}
