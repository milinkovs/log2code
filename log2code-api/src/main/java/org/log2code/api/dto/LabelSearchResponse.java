package org.log2code.api.dto;

import java.util.List;

/** Response of {@code GET /api/labels?datasetId=&verdict=} (T25 step 2): same paging shape as {@link LogSearchResponse}. */
public record LabelSearchResponse(List<LabelDto> items, String nextSearchAfter, long total) {
}
