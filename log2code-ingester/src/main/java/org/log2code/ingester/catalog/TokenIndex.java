package org.log2code.ingester.catalog;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The inverted token index from T19 step 5 (0.10 step 2's {@code topK_tokens}): built once per service
 * from that service's own applicable {@link CatalogKey}s, so {@code N} and {@code df} - and therefore
 * {@code idf(token) = ln(N / df(token))} - are scoped to the actual candidate pool a service's events are
 * ever ranked against, not the whole loaded catalog (an implementation choice T19's text leaves open;
 * ADR-020 records it: it is both cheaper at query time, since {@link #topK} never has to re-check
 * applicability, and a more meaningful weighting, since a token common across every dependency this
 * service pulls in is no more informative here than a stopword, even if it is rare project-wide).
 */
final class TokenIndex {

    private final int totalDocuments;
    private final Map<String, List<CatalogKey>> postings;
    private final Map<String, Double> idf;

    private TokenIndex(int totalDocuments, Map<String, List<CatalogKey>> postings, Map<String, Double> idf) {
        this.totalDocuments = totalDocuments;
        this.postings = postings;
        this.idf = idf;
    }

    static TokenIndex build(List<CatalogKey> entries) {
        Map<String, List<CatalogKey>> postings = new HashMap<>();
        for (CatalogKey entry : entries) {
            for (String token : entry.template().constantTokens()) {
                postings.computeIfAbsent(token, t -> new ArrayList<>()).add(entry);
            }
        }
        int n = entries.size();
        Map<String, Double> idf = new HashMap<>();
        for (Map.Entry<String, List<CatalogKey>> posting : postings.entrySet()) {
            idf.put(posting.getKey(), Math.log((double) n / posting.getValue().size()));
        }
        return new TokenIndex(n, postings, idf);
    }

    int totalDocuments() {
        return totalDocuments;
    }

    /**
     * Ranks statements by the sum of {@code idf(token)} over every token shared between
     * {@code messageTokens} and the statement's {@code constant_tokens} (0.10 step 2), highest first,
     * capped at {@code k}. A statement with none of {@code messageTokens} never appears (no zero-score
     * entries): 0.10 step 3 discards a candidate without a regex match anyway, and this index is only one
     * of two candidate sources ({@code byLogger} is the other) that {@code Matcher} (T20) unions.
     */
    List<CatalogKey> topK(List<String> messageTokens, int k) {
        Map<CatalogKey, Double> scores = new LinkedHashMap<>();
        for (String token : messageTokens) {
            List<CatalogKey> matches = postings.get(token);
            if (matches == null) {
                continue;
            }
            double weight = idf.get(token);
            for (CatalogKey candidate : matches) {
                scores.merge(candidate, weight, Double::sum);
            }
        }
        return scores.entrySet().stream()
            .sorted(Map.Entry.<CatalogKey, Double>comparingByValue().reversed())
            .limit(Math.max(0, k))
            .map(Map.Entry::getKey)
            .toList();
    }
}
