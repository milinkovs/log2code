package org.log2code.api.dto;

import java.util.List;

/**
 * The {@code match} section of {@link ContextBundleDto} (T25 step 1): a narrow subset of
 * {@link MatchResultDto}, with raw (not catalog-joined) candidates — the same data already
 * denormalized on {@code log.match.candidates}, kept here for convenience.
 */
public record ContextMatchDto(String status, Double confidence, String confidenceLevel, List<CandidateDto> candidates) {
}
