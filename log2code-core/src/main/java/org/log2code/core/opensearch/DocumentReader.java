package org.log2code.core.opensearch;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch._types.SortOrder;
import org.opensearch.client.opensearch._types.Time;
import org.opensearch.client.opensearch._types.query_dsl.Query;
import org.opensearch.client.opensearch.core.GetResponse;
import org.opensearch.client.opensearch.core.SearchRequest;
import org.opensearch.client.opensearch.core.SearchResponse;
import org.opensearch.client.opensearch.core.search.Hit;
import org.opensearch.client.opensearch.core.search.Pit;

/** Reads single documents, and streams large result sets lazily via Point in Time + {@code search_after}. */
public final class DocumentReader {

    private static final String PIT_KEEP_ALIVE = "2m";

    private final OpenSearchClient client;

    public DocumentReader(OpenSearchClient client) {
        this.client = client;
    }

    public <T> T get(String index, String id, Class<T> type) throws IOException {
        GetResponse<T> response = client.get(g -> g.index(index).id(id), type);
        return response.found() ? response.source() : null;
    }

    /**
     * Streams every document matching {@code query} (or all documents, if {@code query} is {@code null}),
     * reading pages of {@code pageSize} documents lazily via Point in Time + {@code search_after}.
     * The returned stream must be closed (it releases the point in time on close).
     */
    public <T> Stream<T> streamAll(String index, Query query, Class<T> type, int pageSize) throws IOException {
        String pitId = client.createPit(p -> p.targetIndexes(index).keepAlive(Time.of(t -> t.time(PIT_KEEP_ALIVE)))).pitId();

        PitIterator<T> iterator = new PitIterator<>(client, pitId, query, type, pageSize);
        return StreamSupport.stream(Spliterators.spliteratorUnknownSize(iterator, Spliterator.ORDERED), false)
            .onClose(() -> deletePit(pitId));
    }

    private void deletePit(String pitId) {
        try {
            client.deletePit(d -> d.pitId(List.of(pitId)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static final class PitIterator<T> implements Iterator<T> {

        private final OpenSearchClient client;
        private final String pitId;
        private final Query query;
        private final Class<T> type;
        private final int pageSize;

        private List<String> searchAfter;
        private Iterator<Hit<T>> currentPage = Collections.emptyIterator();
        private boolean exhausted = false;

        PitIterator(OpenSearchClient client, String pitId, Query query, Class<T> type, int pageSize) {
            this.client = client;
            this.pitId = pitId;
            this.query = query;
            this.type = type;
            this.pageSize = pageSize;
        }

        @Override
        public boolean hasNext() {
            if (currentPage.hasNext()) {
                return true;
            }
            if (exhausted) {
                return false;
            }
            fetchNextPage();
            return currentPage.hasNext();
        }

        @Override
        public T next() {
            if (!hasNext()) {
                throw new NoSuchElementException();
            }
            return currentPage.next().source();
        }

        private void fetchNextPage() {
            // "_doc" is the plain Lucene index-order sort, the portable PIT/search_after tiebreaker.
            // OpenSearch 2.19 does not implement Elasticsearch's "_shard_doc" shortcut (query_shard_exception:
            // "No mapping found for [_shard_doc]").
            SearchRequest.Builder builder = new SearchRequest.Builder()
                .pit(Pit.of(p -> p.id(pitId).keepAlive(PIT_KEEP_ALIVE)))
                .size(pageSize)
                .sort(s -> s.field(f -> f.field("_doc").order(SortOrder.Asc)));
            if (query != null) {
                builder.query(query);
            }
            if (searchAfter != null) {
                builder.searchAfter(searchAfter);
            }

            SearchResponse<T> response;
            try {
                response = client.search(builder.build(), type);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }

            List<Hit<T>> hits = response.hits().hits();
            if (hits.isEmpty()) {
                exhausted = true;
                currentPage = Collections.emptyIterator();
                return;
            }
            searchAfter = hits.get(hits.size() - 1).sort();
            currentPage = hits.iterator();
        }
    }
}
