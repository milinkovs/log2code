package org.log2code.ingester.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.log2code.core.model.CodeUnit;
import org.log2code.core.model.Level;

/** T19 step 5: {@code idf(token) = ln(N / df(token))}, summed over tokens shared with the query (0.10 step 2). */
class TokenIndexTest {

    private static final CodeUnit CODE_UNIT = new CodeUnit(CodeUnit.TYPE_PROJECT, "p", "v1");

    @Test
    void ranksByTheRarerSharedTokenFirst() {
        // "connection" is shared by 2 of the 3 loaded entries (idf = ln(3/2), mildly informative);
        // "shutdown" appears in only 1 (idf = ln(3/1), more informative) - the entry with both wins over
        // the one with only "connection", and the unrelated third entry never appears at all.
        CatalogKey connectionOnly = key("a", "Opening database connection successfully");
        CatalogKey connectionAndShutdown = key("b", "Connection shutdown in progress");
        CatalogKey unrelated = key("c", "Loading configuration from disk");
        TokenIndex index = TokenIndex.build(List.of(connectionOnly, connectionAndShutdown, unrelated));

        List<CatalogKey> ranked = index.topK(List.of("connection", "shutdown"), 10);

        assertThat(ranked).containsExactly(connectionAndShutdown, connectionOnly);
    }

    @Test
    void kCapsTheNumberOfResults() {
        CatalogKey a = key("a", "alpha token here");
        CatalogKey b = key("b", "alpha token there");
        CatalogKey c = key("c", "alpha token everywhere");
        TokenIndex index = TokenIndex.build(List.of(a, b, c));

        assertThat(index.topK(List.of("alpha", "token"), 2)).hasSize(2);
    }

    @Test
    void aQueryTokenAbsentFromEveryStatementContributesNothing() {
        CatalogKey a = key("a", "hello world");
        TokenIndex index = TokenIndex.build(List.of(a));

        assertThat(index.topK(List.of("never", "seen"), 10)).isEmpty();
    }

    @Test
    void emptyIndexNeverThrows() {
        TokenIndex index = TokenIndex.build(List.of());

        assertThat(index.topK(List.of("anything"), 10)).isEmpty();
        assertThat(index.totalDocuments()).isZero();
    }

    private static CatalogKey key(String statementId, String template) {
        return new CatalogKey(statementId, CODE_UNIT, "svc", "pkg.A", "pkg.A", "class_literal",
            Level.INFO, false, "literal",
            org.log2code.core.template.MessageTemplate.parse(template), false);
    }
}
