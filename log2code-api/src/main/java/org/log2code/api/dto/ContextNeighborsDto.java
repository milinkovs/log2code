package org.log2code.api.dto;

import java.util.List;

/**
 * The {@code neighbors} section of {@link ContextBundleDto} (T25 step 1): narrower than
 * {@link NeighborsResponse} — no {@code current}, since the bundle already carries the full log
 * at its top level (dogovoreno sa korisnikom pre implementacije).
 */
public record ContextNeighborsDto(List<LogSummary> before, List<LogSummary> after) {
}
