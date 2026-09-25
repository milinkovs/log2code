package org.log2code.api.dto;

import java.util.List;

/**
 * One row of {@code GET /api/methods/{methodId}/callers} (T24 step 1): a caller of the target
 * method, joined with the CALLER's own {@code caller_count} and {@code annotations} (not the
 * target's) so the UI can decide whether that caller itself has further callers to lazily expand,
 * and show its annotations (e.g. {@code @GetMapping}) without a second round trip.
 */
public record CallerDto(String methodId, String classFqn, String methodName, String fileId, int line, int callerCount, List<String> annotations) {
}
