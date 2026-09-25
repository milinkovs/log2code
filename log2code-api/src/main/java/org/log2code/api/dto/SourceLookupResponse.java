package org.log2code.api.dto;

/**
 * Response of {@code GET /api/sources/lookup?codeUnit=&version=&path=} (T24 step 1). Wrapped in an
 * object (rather than a bare string) for consistency with the rest of this API, whose responses are
 * always JSON objects or arrays.
 */
public record SourceLookupResponse(String fileId) {
}
