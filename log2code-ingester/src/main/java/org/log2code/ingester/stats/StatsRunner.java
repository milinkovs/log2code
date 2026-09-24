package org.log2code.ingester.stats;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.log2code.core.opensearch.IndexNames;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch._types.FieldValue;
import org.opensearch.client.opensearch._types.aggregations.Aggregate;
import org.opensearch.client.opensearch._types.aggregations.StringTermsBucket;
import org.opensearch.client.opensearch._types.query_dsl.Query;
import org.opensearch.client.opensearch.core.SearchRequest;
import org.opensearch.client.opensearch.core.SearchResponse;

/** Computes {@link DatasetStats} for one dataset via OpenSearch aggregations (T21's {@code stats} command). */
public final class StatsRunner {

    private static final int MAX_BUCKETS = 100;

    private StatsRunner() {
    }

    public static DatasetStats compute(OpenSearchClient client, IndexNames indexNames, String datasetId) throws IOException {
        Query datasetFilter = Query.of(q -> q.term(t -> t.field("dataset_id").value(FieldValue.of(datasetId))));

        SearchRequest request = new SearchRequest.Builder()
            .index(indexNames.logs())
            .size(0)
            .query(datasetFilter)
            .aggregations("by_status", a -> a.terms(t -> t.field("match.status").size(MAX_BUCKETS)))
            .aggregations("by_confidence_level", a -> a.terms(t -> t.field("match.confidence_level").size(MAX_BUCKETS)))
            .aggregations("by_service", a -> a.terms(t -> t.field("service").size(MAX_BUCKETS)))
            .aggregations("by_level", a -> a.terms(t -> t.field("level").size(MAX_BUCKETS)))
            .build();
        SearchResponse<Void> response = client.search(request, Void.class);

        long total = client.count(c -> c.index(indexNames.logs()).query(datasetFilter)).count();
        long withException = countMatching(client, indexNames, datasetFilter, "exception.class");
        long withTraceId = countMatching(client, indexNames, datasetFilter, "trace_id");

        return new DatasetStats(datasetId, total,
            bucketCounts(response, "by_status"), bucketCounts(response, "by_confidence_level"),
            bucketCounts(response, "by_service"), bucketCounts(response, "by_level"),
            withException, withTraceId);
    }

    private static long countMatching(OpenSearchClient client, IndexNames indexNames, Query datasetFilter, String existsField) throws IOException {
        Query query = Query.of(q -> q.bool(b -> b.filter(List.of(
            datasetFilter, Query.of(f -> f.exists(e -> e.field(existsField)))))));
        return client.count(c -> c.index(indexNames.logs()).query(query)).count();
    }

    private static Map<String, Long> bucketCounts(SearchResponse<?> response, String aggregationName) {
        Aggregate aggregate = response.aggregations().get(aggregationName);
        Map<String, Long> result = new TreeMap<>();
        if (aggregate != null) {
            for (StringTermsBucket bucket : aggregate.sterms().buckets().array()) {
                result.put(bucket.key(), bucket.docCount());
            }
        }
        return result;
    }
}
