package org.log2code.api.service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.log2code.api.dto.LogSummary;
import org.log2code.api.dto.NeighborsResponse;
import org.log2code.api.dto.TraceResponse;
import org.log2code.core.model.EnrichedLog;
import org.log2code.core.opensearch.IndexNames;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch._types.FieldValue;
import org.opensearch.client.opensearch._types.SortOptions;
import org.opensearch.client.opensearch._types.SortOrder;
import org.opensearch.client.opensearch._types.query_dsl.Query;
import org.opensearch.client.opensearch.core.SearchRequest;
import org.opensearch.client.opensearch.core.SearchResponse;
import org.opensearch.client.opensearch.core.search.Hit;

/**
 * Backs {@code GET /api/logs/{logId}/neighbors} and {@code /trace} (T24 step 1). Both take the
 * already-fetched "current" {@link EnrichedLog} (the controller owns the 404 for an unknown
 * {@code logId}, same as {@link org.log2code.api.web.LogsController#get}).
 */
public final class LogNeighborhoodService {

    public static final String SCOPE_SERVICE = "service";
    public static final String SCOPE_THREAD = "thread";
    public static final String SCOPE_DATASET = "dataset";
    /** {@code service} (exact file neighbors) is the most literal reading of "neighboring logs" and is listed first in 0.4/T24. */
    public static final String DEFAULT_SCOPE = SCOPE_SERVICE;

    public static final int DEFAULT_BEFORE = 20;
    public static final int DEFAULT_AFTER = 20;
    public static final int DEFAULT_TRACE_LIMIT = 200;
    private static final int MAX_NEIGHBORS_PER_SIDE = 500;
    private static final int MAX_TRACE_LIMIT = 500;
    private static final List<String> TIME_SORT_FIELDS = List.of("@timestamp", "sequence", "log_id");

    private final OpenSearchClient client;
    private final IndexNames indexNames;

    public LogNeighborhoodService(OpenSearchClient client, IndexNames indexNames) {
        this.client = client;
        this.indexNames = indexNames;
    }

    public NeighborsResponse neighbors(EnrichedLog current, int before, int after, String scope) {
        String resolvedScope = (scope == null || scope.isBlank()) ? DEFAULT_SCOPE : scope;
        if (before < 0) {
            throw new IllegalArgumentException("before must not be negative: " + before);
        }
        if (after < 0) {
            throw new IllegalArgumentException("after must not be negative: " + after);
        }
        int clampedBefore = Math.min(before, MAX_NEIGHBORS_PER_SIDE);
        int clampedAfter = Math.min(after, MAX_NEIGHBORS_PER_SIDE);

        ScopeSpec spec = scopeSpec(resolvedScope, current);
        List<String> anchor = anchorSortValues(spec, current.logId());

        List<EnrichedLog> beforeLogs = clampedBefore == 0 ? List.of()
            : reversed(fetchSide(spec, anchor, clampedBefore, SortOrder.Desc));
        List<EnrichedLog> afterLogs = clampedAfter == 0 ? List.of() : fetchSide(spec, anchor, clampedAfter, SortOrder.Asc);

        return new NeighborsResponse(toSummaries(beforeLogs), LogMapper.toSummary(current), toSummaries(afterLogs));
    }

    public TraceResponse trace(EnrichedLog current, int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive: " + limit);
        }
        if (current.traceId() == null || current.traceId().isBlank()) {
            return new TraceResponse(List.of(), TraceResponse.REASON_NO_TRACE_ID);
        }

        int size = Math.min(limit, MAX_TRACE_LIMIT);
        List<Query> filters = List.of(termQuery("dataset_id", current.datasetId()), termQuery("trace_id", current.traceId()));
        SearchRequest request = new SearchRequest.Builder()
            .index(indexNames.logs())
            .size(size)
            .query(boolFilter(filters))
            .sort(sortOptions(TIME_SORT_FIELDS, SortOrder.Asc))
            .build();

        List<EnrichedLog> hits = search(request).hits().hits().stream().map(Hit::source).toList();
        return new TraceResponse(toSummaries(hits), null);
    }

    private ScopeSpec scopeSpec(String scope, EnrichedLog current) {
        List<Query> filters = new ArrayList<>();
        filters.add(termQuery("dataset_id", current.datasetId()));
        List<String> sortFields;
        switch (scope) {
            case SCOPE_SERVICE -> {
                filters.add(termQuery("source_file", current.sourceFile()));
                sortFields = List.of("sequence");
            }
            case SCOPE_THREAD -> {
                filters.add(termQuery("service", current.service()));
                filters.add(termQuery("thread", current.thread()));
                sortFields = TIME_SORT_FIELDS;
            }
            case SCOPE_DATASET -> sortFields = TIME_SORT_FIELDS;
            default -> throw new IllegalArgumentException("scope must be 'service', 'thread', or 'dataset': " + scope);
        }
        return new ScopeSpec(filters, sortFields);
    }

    /**
     * The current log's own sort-key tuple, read back from OpenSearch (rather than built by hand)
     * so it exactly matches the representation {@code search_after} expects for these field types
     * (in particular the {@code date} field's doc-value encoding).
     */
    private List<String> anchorSortValues(ScopeSpec spec, String logId) {
        List<Query> filters = new ArrayList<>(spec.filters());
        filters.add(termQuery("log_id", logId));
        SearchRequest request = new SearchRequest.Builder()
            .index(indexNames.logs())
            .size(1)
            .query(boolFilter(filters))
            .sort(sortOptions(spec.sortFields(), SortOrder.Asc))
            .build();

        List<Hit<EnrichedLog>> hits = search(request).hits().hits();
        if (hits.isEmpty()) {
            throw new IllegalStateException("current log not found within its own neighbor scope: " + logId);
        }
        return hits.get(0).sort();
    }

    /**
     * {@code size} documents strictly before ({@code order = Desc}) or after ({@code order = Asc})
     * {@code anchor} in {@code spec}'s sort order; "before" results come back nearest-first and are
     * reversed by the caller.
     */
    private List<EnrichedLog> fetchSide(ScopeSpec spec, List<String> anchor, int size, SortOrder order) {
        SearchRequest request = new SearchRequest.Builder()
            .index(indexNames.logs())
            .size(size)
            .query(boolFilter(spec.filters()))
            .sort(sortOptions(spec.sortFields(), order))
            .searchAfter(anchor)
            .build();
        return search(request).hits().hits().stream().map(Hit::source).toList();
    }

    private SearchResponse<EnrichedLog> search(SearchRequest request) {
        try {
            return client.search(request, EnrichedLog.class);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static List<SortOptions> sortOptions(List<String> fields, SortOrder order) {
        return fields.stream().map(field -> SortOptions.of(s -> s.field(f -> f.field(field).order(order)))).toList();
    }

    private static Query termQuery(String field, String value) {
        return Query.of(query -> query.term(t -> t.field(field).value(FieldValue.of(value))));
    }

    private static Query boolFilter(List<Query> filters) {
        return Query.of(query -> query.bool(b -> b.filter(filters)));
    }

    private static List<LogSummary> toSummaries(List<EnrichedLog> logs) {
        return logs.stream().map(LogMapper::toSummary).toList();
    }

    private static <T> List<T> reversed(List<T> list) {
        List<T> copy = new ArrayList<>(list);
        Collections.reverse(copy);
        return copy;
    }

    private record ScopeSpec(List<Query> filters, List<String> sortFields) {
    }
}
