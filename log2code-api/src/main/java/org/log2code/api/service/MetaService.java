package org.log2code.api.service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Comparator;
import java.util.List;
import org.log2code.api.dto.CodeUnitSummary;
import org.log2code.api.dto.DatasetSummary;
import org.log2code.core.model.AnalysisRun;
import org.log2code.core.opensearch.IndexNames;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch._types.FieldValue;
import org.opensearch.client.opensearch._types.aggregations.Aggregate;
import org.opensearch.client.opensearch._types.aggregations.StringTermsBucket;
import org.opensearch.client.opensearch._types.query_dsl.Query;
import org.opensearch.client.opensearch.core.SearchRequest;
import org.opensearch.client.opensearch.core.SearchResponse;

/** Backs the {@code GET /api/meta/*} endpoints (T23 step 3). */
public final class MetaService {

    private static final int MAX_BUCKETS = 500;
    private static final int MAX_RUNS = 500;

    private final OpenSearchClient client;
    private final IndexNames indexNames;

    public MetaService(OpenSearchClient client, IndexNames indexNames) {
        this.client = client;
        this.indexNames = indexNames;
    }

    /** One row per {@code dataset_id} seen in {@code log2code-logs}, with its event count. */
    public List<DatasetSummary> datasets() {
        SearchRequest request = new SearchRequest.Builder()
            .index(indexNames.logs())
            .size(0)
            .aggregations("by_dataset", a -> a.terms(t -> t.field("dataset_id").size(MAX_BUCKETS)))
            .build();
        SearchResponse<Void> response = search(request, Void.class);

        Aggregate aggregate = response.aggregations().get("by_dataset");
        if (aggregate == null) {
            return List.of();
        }
        return aggregate.sterms().buckets().array().stream()
            .map(bucket -> new DatasetSummary(bucket.key(), bucket.docCount()))
            .sorted(Comparator.comparing(DatasetSummary::datasetId))
            .toList();
    }

    /** Distinct {@code service} values in {@code log2code-logs}, optionally restricted to one dataset. */
    public List<String> services(String datasetId) {
        SearchRequest.Builder builder = new SearchRequest.Builder()
            .index(indexNames.logs())
            .size(0)
            .aggregations("by_service", a -> a.terms(t -> t.field("service").size(MAX_BUCKETS)));
        if (datasetId != null && !datasetId.isBlank()) {
            builder.query(Query.of(q -> q.term(t -> t.field("dataset_id").value(FieldValue.of(datasetId)))));
        }
        SearchResponse<Void> response = search(builder.build(), Void.class);

        Aggregate aggregate = response.aggregations().get("by_service");
        if (aggregate == null) {
            return List.of();
        }
        return aggregate.sterms().buckets().array().stream()
            .map(StringTermsBucket::key)
            .sorted()
            .toList();
    }

    /** Every analyzed code unit (project or dependency), from {@code log2code-runs}. */
    public List<CodeUnitSummary> codeUnits() {
        SearchRequest request = new SearchRequest.Builder()
            .index(indexNames.runs())
            .size(MAX_RUNS)
            .query(Query.of(q -> q.matchAll(m -> m)))
            .build();
        SearchResponse<AnalysisRun> response = search(request, AnalysisRun.class);

        return response.hits().hits().stream()
            .map(hit -> hit.source())
            .map(run -> new CodeUnitSummary(run.kind(), run.codeUnit().name(), run.codeUnit().version(), run.finishedAt()))
            .sorted(Comparator.comparing(CodeUnitSummary::name).thenComparing(CodeUnitSummary::version))
            .toList();
    }

    private <T> SearchResponse<T> search(SearchRequest request, Class<T> type) {
        try {
            return client.search(request, type);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
