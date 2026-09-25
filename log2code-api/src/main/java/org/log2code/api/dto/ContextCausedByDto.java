package org.log2code.api.dto;

import java.util.List;

/** One "Caused by" cause, resolved for {@link ContextBundleDto} (T25 step 1: mirrors {@link CausedByDto}). */
public record ContextCausedByDto(String className, String message, List<ContextStackFrameDto> frames) {
}
