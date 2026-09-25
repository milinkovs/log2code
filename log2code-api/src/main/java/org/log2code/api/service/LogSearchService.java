package org.log2code.api.service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import org.log2code.api.dto.LogSearchResponse;
import org.log2code.api.dto.LogSummary;
import org.log2code.core.model.EnrichedLog;
import org.log2code.core.opensearch.IndexNames;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch._types.FieldValue;
import org.opensearch.client.opensearch._types.SortOptions;
import org.opensearch.client.opensearch._types.SortOrder;
import org.opensearch.client.opensearch._types.query_dsl.Query;
import org.opensearch.client.opensearch._types.query_dsl.RangeQuery;
import org.opensearch.client.opensearch.core.SearchRequest;
import org.opensearch.client.opensearch.core.SearchResponse;
import org.opensearch.client.opensearch.core.search.Hit;
import org.opensearch.client.json.JsonData;

/** Builds and runs the {@code GET /api/logs} query against {@code log2code-logs} (T23 step 3, 0.10 is not involved). */
public final class LogSearchService {

    private final OpenSearchClient client;
    private final IndexNames indexNames;

    public LogSearchService(OpenSearchClient client, IndexNames indexNames) {
        this.client = client;
        this.indexNames = indexNames;
    }

    public LogSearchResponse search(LogSearchParams params) {
        SortOrder sortOrder = LogSearchParams.ORDER_ASC.equals(params.order()) ? SortOrder.Asc : SortOrder.Desc;

        SearchRequest.Builder builder = new SearchRequest.Builder()
            .index(indexNames.logs())
            .size(params.size())
            .query(buildQuery(params))
            .sort(sortFields(sortOrder));

        if (params.searchAfter() != null && !params.searchAfter().isBlank()) {
            builder.searchAfter(SearchAfterCodec.decode(params.searchAfter()));
        }

        SearchResponse<EnrichedLog> response;
        try {
            response = client.search(builder.build(), EnrichedLog.class);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        List<Hit<EnrichedLog>> hits = response.hits().hits();
        List<LogSummary> items = hits.stream().map(Hit::source).map(LogMapper::toSummary).toList();
        String nextSearchAfter = hits.isEmpty() ? null : SearchAfterCodec.encode(hits.get(hits.size() - 1).sort());
        long total = response.hits().total() == null ? items.size() : response.hits().total().value();

        return new LogSearchResponse(items, nextSearchAfter, total, response.took());
    }

    private static List<SortOptions> sortFields(SortOrder order) {
        return List.of(
            SortOptions.of(s -> s.field(f -> f.field("@timestamp").order(order))),
            SortOptions.of(s -> s.field(f -> f.field("sequence").order(order))),
            SortOptions.of(s -> s.field(f -> f.field("log_id").order(order)))
        );
    }

    private static Query buildQuery(LogSearchParams params) {
        List<Query> filters = new ArrayList<>();

        if (params.q() != null && !params.q().isBlank()) {
            String q = params.q();
            filters.add(Query.of(query -> query.simpleQueryString(s -> s.query(q).fields(List.of("message")))));
        }
        addTermsFilter(filters, "service", params.service());
        addTermsFilter(filters, "level", params.level());
        addTermsFilter(filters, "match.status", params.status());
        addTermsFilter(filters, "match.confidence_level", params.confidenceLevel());
        if (params.datasetId() != null) {
            filters.add(termQuery("dataset_id", params.datasetId()));
        }
        if (params.traceId() != null) {
            filters.add(termQuery("trace_id", params.traceId()));
        }
        if (params.statementId() != null) {
            filters.add(termQuery("match.statement_id", params.statementId()));
        }
        if (params.from() != null || params.to() != null) {
            filters.add(rangeQuery(params));
        }
        if (params.hasException() != null) {
            Query hasExceptionQuery = Query.of(query -> query.exists(e -> e.field("exception.class")));
            filters.add(params.hasException() ? hasExceptionQuery
                : Query.of(query -> query.bool(b -> b.mustNot(hasExceptionQuery))));
        }

        if (filters.isEmpty()) {
            return Query.of(query -> query.matchAll(m -> m));
        }
        return Query.of(query -> query.bool(b -> b.filter(filters)));
    }

    private static Query rangeQuery(LogSearchParams params) {
        return Query.of(query -> query.range(r -> {
            RangeQuery.Builder rangeBuilder = r.field("@timestamp");
            if (params.from() != null) {
                rangeBuilder = rangeBuilder.gte(JsonData.of(params.from().toString()));
            }
            if (params.to() != null) {
                rangeBuilder = rangeBuilder.lte(JsonData.of(params.to().toString()));
            }
            return rangeBuilder;
        }));
    }

    private static void addTermsFilter(List<Query> filters, String field, List<String> values) {
        if (values.isEmpty()) {
            return;
        }
        List<FieldValue> fieldValues = values.stream().map(FieldValue::of).toList();
        filters.add(Query.of(query -> query.terms(t -> t.field(field).terms(tf -> tf.value(fieldValues)))));
    }

    private static Query termQuery(String field, String value) {
        return Query.of(query -> query.term(t -> t.field(field).value(FieldValue.of(value))));
    }
}
