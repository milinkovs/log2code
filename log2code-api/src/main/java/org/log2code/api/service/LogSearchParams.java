package org.log2code.api.service;

import java.time.Instant;
import java.util.List;

/**
 * Parsed, validated parameters of {@code GET /api/logs} (T23 step 3). {@code size} above
 * {@link #MAX_SIZE} is silently clamped; an unknown {@code order} or non-positive {@code size}
 * is rejected (mapped to HTTP 400 by the controller advice).
 */
public record LogSearchParams(
    String q,
    List<String> service,
    List<String> level,
    List<String> status,
    List<String> confidenceLevel,
    String datasetId,
    Instant from,
    Instant to,
    String traceId,
    Boolean hasException,
    String statementId,
    int size,
    String searchAfter,
    String order
) {

    public static final int DEFAULT_SIZE = 100;
    public static final int MAX_SIZE = 500;
    public static final String ORDER_ASC = "asc";
    public static final String ORDER_DESC = "desc";

    public LogSearchParams {
        service = service == null ? List.of() : List.copyOf(service);
        level = level == null ? List.of() : List.copyOf(level);
        status = status == null ? List.of() : List.copyOf(status);
        confidenceLevel = confidenceLevel == null ? List.of() : List.copyOf(confidenceLevel);
        order = (order == null || order.isBlank()) ? ORDER_DESC : order;
        if (!order.equals(ORDER_ASC) && !order.equals(ORDER_DESC)) {
            throw new IllegalArgumentException("order must be 'asc' or 'desc': " + order);
        }
        if (size <= 0) {
            throw new IllegalArgumentException("size must be positive: " + size);
        }
        size = Math.min(size, MAX_SIZE);
    }
}
