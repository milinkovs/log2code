package org.log2code.core.opensearch;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.log2code.core.json.Json;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch._types.OpenSearchException;
import org.opensearch.client.opensearch.core.BulkResponse;
import org.opensearch.client.opensearch.core.bulk.BulkOperation;
import org.opensearch.client.opensearch.core.bulk.BulkResponseItem;
import org.opensearch.client.opensearch.core.bulk.IndexOperation;

/**
 * Buffers documents and flushes them to OpenSearch in bulk batches, retrying 429/503 failures
 * with increasing backoff. Not thread-safe: use one writer per thread.
 */
public final class BulkWriter<T> implements AutoCloseable {

    public static final int DEFAULT_MAX_DOCUMENTS = 1000;
    public static final long DEFAULT_MAX_BYTES = 5L * 1024 * 1024;
    private static final int MAX_RETRIES = 3;
    private static final long BACKOFF_BASE_MILLIS = 500;
    private static final int MAX_REPORTED_ERRORS = 10;
    private static final ObjectMapper SIZE_ESTIMATE_MAPPER = Json.mapper();

    /** Outcome of everything written through this writer so far. */
    public record Report(long succeeded, long failed, List<String> errors) {
    }

    private final OpenSearchClient client;
    private final String index;
    private final Function<T, String> idFunction;
    private final int maxDocuments;
    private final long maxBytes;

    private final List<BulkOperation> buffer = new ArrayList<>();
    private long bufferBytes = 0;

    private long succeeded = 0;
    private long failed = 0;
    private final List<String> errors = new ArrayList<>();

    public BulkWriter(OpenSearchClient client, String index, Function<T, String> idFunction) {
        this(client, index, idFunction, DEFAULT_MAX_DOCUMENTS, DEFAULT_MAX_BYTES);
    }

    public BulkWriter(OpenSearchClient client, String index, Function<T, String> idFunction, int maxDocuments, long maxBytes) {
        this.client = client;
        this.index = index;
        this.idFunction = idFunction;
        this.maxDocuments = maxDocuments;
        this.maxBytes = maxBytes;
    }

    public void add(T document) throws IOException {
        String id = idFunction.apply(document);
        IndexOperation<T> indexOperation = IndexOperation.of(i -> i.index(index).id(id).document(document));
        buffer.add(BulkOperation.of(b -> b.index(indexOperation)));
        bufferBytes += estimateSize(document);

        if (buffer.size() >= maxDocuments || bufferBytes >= maxBytes) {
            flush();
        }
    }

    /** Sends any buffered documents now. */
    public void flush() throws IOException {
        if (buffer.isEmpty()) {
            return;
        }
        List<BulkOperation> pending = List.copyOf(buffer);
        buffer.clear();
        bufferBytes = 0;
        sendWithRetry(pending, 0);
    }

    public Report report() {
        return new Report(succeeded, failed, List.copyOf(errors));
    }

    @Override
    public void close() throws IOException {
        flush();
    }

    private void sendWithRetry(List<BulkOperation> operations, int retryCount) throws IOException {
        if (operations.isEmpty()) {
            return;
        }
        BulkResponse response;
        try {
            response = client.bulk(b -> b.operations(operations));
        } catch (OpenSearchException e) {
            if (retryCount < MAX_RETRIES && isRetryable(e.status())) {
                backoff(retryCount);
                sendWithRetry(operations, retryCount + 1);
                return;
            }
            failed += operations.size();
            addError("bulk request failed (HTTP " + e.status() + "): " + e.getMessage());
            return;
        }

        List<BulkOperation> retryBatch = new ArrayList<>();
        List<BulkResponseItem> items = response.items();
        for (int i = 0; i < items.size(); i++) {
            BulkResponseItem item = items.get(i);
            if (item.error() == null) {
                succeeded++;
            } else if (retryCount < MAX_RETRIES && isRetryable(item.status())) {
                retryBatch.add(operations.get(i));
            } else {
                failed++;
                addError(item.id() + ": [" + item.status() + "] " + item.error().reason());
            }
        }

        if (!retryBatch.isEmpty()) {
            backoff(retryCount);
            sendWithRetry(retryBatch, retryCount + 1);
        }
    }

    private static boolean isRetryable(int status) {
        return status == 429 || status == 503;
    }

    private void backoff(int retryCount) {
        long delayMillis = BACKOFF_BASE_MILLIS * (1L << retryCount);
        try {
            Thread.sleep(delayMillis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void addError(String message) {
        if (errors.size() < MAX_REPORTED_ERRORS) {
            errors.add(message);
        }
    }

    private static long estimateSize(Object document) {
        try {
            return SIZE_ESTIMATE_MAPPER.writeValueAsBytes(document).length;
        } catch (IOException e) {
            return 0;
        }
    }
}
