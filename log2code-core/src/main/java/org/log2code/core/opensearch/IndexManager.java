package org.log2code.core.opensearch;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch._types.FieldValue;
import org.opensearch.client.opensearch._types.query_dsl.Query;
import org.opensearch.client.opensearch.core.CountResponse;
import org.opensearch.client.opensearch.generic.Requests;
import org.opensearch.client.opensearch.generic.Response;

/**
 * Creates the 7 catalog/log indices from their mapping resources (0.7), and provides the
 * index-level operations the analyzer and ingester need: recreate, delete-by-query, refresh, count.
 */
public final class IndexManager {

    private static final String MAPPING_RESOURCE_PATTERN = "/opensearch/mappings/%s.json";

    private final OpenSearchClient client;
    private final IndexNames indexNames;

    public IndexManager(OpenSearchClient client, IndexNames indexNames) {
        this.client = client;
        this.indexNames = indexNames;
    }

    /** Creates every one of the 7 indices that does not already exist. */
    public void ensureAll() throws IOException {
        for (String baseName : IndexNames.BASE_NAMES) {
            String resolved = indexNames.prefix() + baseName;
            if (!exists(resolved)) {
                createFromMapping(resolved, baseName);
            }
        }
    }

    /** Drops {@code index} if it exists, then recreates it from its mapping resource. */
    public void recreate(String index) throws IOException {
        if (exists(index)) {
            client.indices().delete(d -> d.index(index));
        }
        createFromMapping(index, indexNames.baseNameOf(index));
    }

    public boolean exists(String index) throws IOException {
        return client.indices().exists(e -> e.index(index)).value();
    }

    /** Deletes every document matching an AND of exact-term filters (empty/null filters match everything). */
    public void deleteByQuery(String index, Map<String, String> termFilters) throws IOException {
        Query query = termFilterQuery(termFilters);
        client.deleteByQuery(d -> d.index(index).query(query).refresh(true));
    }

    public void refresh(String index) throws IOException {
        client.indices().refresh(r -> r.index(index));
    }

    public long count(String index, Map<String, String> termFilters) throws IOException {
        Query query = termFilterQuery(termFilters);
        CountResponse response = client.count(c -> c.index(index).query(query));
        return response.count();
    }

    private void createFromMapping(String index, String baseName) throws IOException {
        String mappingJson = loadMappingResource(baseName);
        try (Response response = client.generic()
            .execute(Requests.builder().endpoint("/" + index).method("PUT").json(mappingJson).build())) {
            if (response.getStatus() >= 300) {
                throw new IOException("failed to create index " + index + ": HTTP " + response.getStatus() + " " + response.getReason());
            }
        }
    }

    private static Query termFilterQuery(Map<String, String> termFilters) {
        if (termFilters == null || termFilters.isEmpty()) {
            return Query.of(q -> q.matchAll(m -> m));
        }
        List<Query> filters = termFilters.entrySet().stream()
            .map(entry -> Query.of(q -> q.term(t -> t.field(entry.getKey()).value(FieldValue.of(entry.getValue())))))
            .toList();
        if (filters.size() == 1) {
            return filters.get(0);
        }
        return Query.of(q -> q.bool(b -> b.filter(filters)));
    }

    private static String loadMappingResource(String baseName) {
        String path = MAPPING_RESOURCE_PATTERN.formatted(baseName);
        try (InputStream in = IndexManager.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("mapping resource not found on classpath: " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
