package org.log2code.api.service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.log2code.api.dto.LogSummary;
import org.log2code.core.model.EnrichedLog;
import org.log2code.core.model.Label;
import org.log2code.core.model.MatchResult;
import org.log2code.core.opensearch.DocumentReader;
import org.log2code.core.opensearch.IndexNames;
import org.opensearch.client.opensearch._types.FieldValue;
import org.opensearch.client.opensearch._types.query_dsl.Query;

/**
 * Backs {@code GET /api/review-queue} (T25 step 2): logs needing manual review that don't have a
 * label yet, in an order that is random but reproducible for the same {@code seed}.
 *
 * <p>Eligibility ({@code ground_truth.reliable = false}, OR {@code match.status = ambiguous}, OR
 * {@code match.confidence_level = low}) and the "not already labeled" exclusion are both pushed
 * into the OpenSearch query (the latter via an {@code ids} lookup of every label already recorded
 * for the dataset); the random-but-repeatable ordering is then a plain
 * {@link Collections#shuffle(List, Random)} over the resulting (dataset-sized, not enormous) list —
 * {@code java.util.Random}'s algorithm is part of its documented contract, so the same seed over
 * the same candidate set always produces the same order.
 */
public final class ReviewQueueService {

    public static final int DEFAULT_LIMIT = 50;
    public static final long DEFAULT_SEED = 1L;
    static final int MAX_LIMIT = 500;
    private static final int PAGE_SIZE = 500;

    private final DocumentReader documentReader;
    private final IndexNames indexNames;

    public ReviewQueueService(DocumentReader documentReader, IndexNames indexNames) {
        this.documentReader = documentReader;
        this.indexNames = indexNames;
    }

    public List<LogSummary> reviewQueue(String datasetId, int limit, long seed) {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive: " + limit);
        }
        int clampedLimit = Math.min(limit, MAX_LIMIT);

        Set<String> labeledIds = labeledLogIds(datasetId);
        List<EnrichedLog> eligible = fetchEligible(datasetId, labeledIds);

        List<EnrichedLog> shuffled = new ArrayList<>(eligible);
        Collections.shuffle(shuffled, new Random(seed));
        return shuffled.stream().limit(clampedLimit).map(LogMapper::toSummary).toList();
    }

    private Set<String> labeledLogIds(String datasetId) {
        Query query = datasetId == null ? null : termQuery("dataset_id", datasetId);
        try (Stream<Label> stream = documentReader.streamAll(indexNames.labels(), query, Label.class, PAGE_SIZE)) {
            return stream.map(Label::logId).collect(Collectors.toSet());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private List<EnrichedLog> fetchEligible(String datasetId, Set<String> labeledIds) {
        Query query = eligibilityQuery(datasetId, labeledIds);
        try (Stream<EnrichedLog> stream = documentReader.streamAll(indexNames.logs(), query, EnrichedLog.class, PAGE_SIZE)) {
            return stream.collect(Collectors.toCollection(ArrayList::new));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Query eligibilityQuery(String datasetId, Set<String> labeledIds) {
        List<Query> filters = new ArrayList<>();
        if (datasetId != null) {
            filters.add(termQuery("dataset_id", datasetId));
        }
        filters.add(reviewReasonQuery());
        Query positive = filters.size() == 1 ? filters.get(0) : Query.of(q -> q.bool(b -> b.filter(filters)));
        if (labeledIds.isEmpty()) {
            return positive;
        }
        return Query.of(q -> q.bool(b -> b.filter(List.of(positive)).mustNot(idsQuery(labeledIds))));
    }

    private static Query reviewReasonQuery() {
        List<Query> should = List.of(
            Query.of(q -> q.term(t -> t.field("ground_truth.reliable").value(FieldValue.FALSE))),
            termQuery("match.status", MatchResult.STATUS_AMBIGUOUS),
            termQuery("match.confidence_level", MatchResult.CONFIDENCE_LOW)
        );
        return Query.of(q -> q.bool(b -> b.should(should).minimumShouldMatch("1")));
    }

    private static Query idsQuery(Set<String> ids) {
        return Query.of(q -> q.ids(i -> i.values(List.copyOf(ids))));
    }

    private static Query termQuery(String field, String value) {
        return Query.of(q -> q.term(t -> t.field(field).value(FieldValue.of(value))));
    }
}
