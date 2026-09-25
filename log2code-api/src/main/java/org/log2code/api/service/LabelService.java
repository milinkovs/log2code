package org.log2code.api.service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.log2code.api.dto.LabelDto;
import org.log2code.api.dto.LabelRequest;
import org.log2code.api.dto.LabelSearchResponse;
import org.log2code.core.model.EnrichedLog;
import org.log2code.core.model.Label;
import org.log2code.core.model.MatchResult;
import org.log2code.core.opensearch.DocumentReader;
import org.log2code.core.opensearch.IndexNames;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch._types.FieldValue;
import org.opensearch.client.opensearch._types.Refresh;
import org.opensearch.client.opensearch._types.SortOptions;
import org.opensearch.client.opensearch._types.SortOrder;
import org.opensearch.client.opensearch._types.query_dsl.Query;
import org.opensearch.client.opensearch.core.SearchRequest;
import org.opensearch.client.opensearch.core.SearchResponse;
import org.opensearch.client.opensearch.core.search.Hit;

/**
 * Backs {@code /api/labels/**} (T25 step 2): CRUD over {@code log2code-labels}, {@code _id = logId}.
 * Every write uses {@code refresh=true} — labeling is a low-throughput, human-paced action, so
 * making the write immediately visible (to a following {@code GET}, or to {@code review-queue})
 * matters more here than it would for bulk ingestion.
 */
public final class LabelService {

    private final OpenSearchClient client;
    private final DocumentReader documentReader;
    private final IndexNames indexNames;

    public LabelService(OpenSearchClient client, DocumentReader documentReader, IndexNames indexNames) {
        this.client = client;
        this.documentReader = documentReader;
        this.indexNames = indexNames;
    }

    /** {@code dataset_id} and {@code predicted_statement_id} are filled in from {@code log}, never from the request body. */
    public LabelDto put(EnrichedLog log, LabelRequest request) {
        String verdict = validateVerdict(request.verdict());
        MatchResult match = log.match();
        String predictedStatementId = match == null ? null : match.statementId();
        Label label = new Label(log.logId(), log.datasetId(), verdict, request.correctStatementId(),
            predictedStatementId, request.note(), Instant.now());
        try {
            client.index(i -> i.index(indexNames.labels()).id(label.logId()).document(label).refresh(Refresh.True));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return toDto(label);
    }

    /** {@code null} when no label exists for {@code logId} — the controller decides how to turn that into a 404. */
    public LabelDto get(String logId) {
        Label label = fetchLabel(logId);
        return label == null ? null : toDto(label);
    }

    /** Idempotent: succeeds whether or not a label existed. */
    public void delete(String logId) {
        try {
            client.delete(d -> d.index(indexNames.labels()).id(logId).refresh(Refresh.True));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public LabelSearchResponse list(LabelListParams params) {
        SearchRequest.Builder builder = new SearchRequest.Builder()
            .index(indexNames.labels())
            .size(params.size())
            .query(buildQuery(params))
            .sort(sortFields());
        if (params.searchAfter() != null && !params.searchAfter().isBlank()) {
            builder.searchAfter(SearchAfterCodec.decode(params.searchAfter()));
        }

        SearchResponse<Label> response;
        try {
            response = client.search(builder.build(), Label.class);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        List<Hit<Label>> hits = response.hits().hits();
        List<LabelDto> items = hits.stream().map(Hit::source).map(LabelService::toDto).toList();
        String nextSearchAfter = hits.isEmpty() ? null : SearchAfterCodec.encode(hits.get(hits.size() - 1).sort());
        long total = response.hits().total() == null ? items.size() : response.hits().total().value();
        return new LabelSearchResponse(items, nextSearchAfter, total);
    }

    private Label fetchLabel(String logId) {
        try {
            return documentReader.get(indexNames.labels(), logId, Label.class);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Query buildQuery(LabelListParams params) {
        List<Query> filters = new ArrayList<>();
        if (params.datasetId() != null && !params.datasetId().isBlank()) {
            filters.add(termQuery("dataset_id", params.datasetId()));
        }
        if (params.verdict() != null && !params.verdict().isBlank()) {
            filters.add(termQuery("verdict", params.verdict()));
        }
        if (filters.isEmpty()) {
            return Query.of(q -> q.matchAll(m -> m));
        }
        return Query.of(q -> q.bool(b -> b.filter(filters)));
    }

    private static List<SortOptions> sortFields() {
        return List.of(
            SortOptions.of(s -> s.field(f -> f.field("labeled_at").order(SortOrder.Desc))),
            SortOptions.of(s -> s.field(f -> f.field("log_id").order(SortOrder.Desc)))
        );
    }

    private static String validateVerdict(String verdict) {
        if (verdict == null || (!verdict.equals(Label.VERDICT_CORRECT) && !verdict.equals(Label.VERDICT_INCORRECT)
                && !verdict.equals(Label.VERDICT_NOT_IN_CATALOG))) {
            throw new IllegalArgumentException("verdict must be 'correct', 'incorrect', or 'not_in_catalog': " + verdict);
        }
        return verdict;
    }

    private static LabelDto toDto(Label label) {
        return new LabelDto(label.logId(), label.datasetId(), label.verdict(), label.correctStatementId(),
            label.predictedStatementId(), label.note(), label.labeledAt());
    }

    private static Query termQuery(String field, String value) {
        return Query.of(q -> q.term(t -> t.field(field).value(FieldValue.of(value))));
    }
}
