package org.log2code.api.dto;

import java.util.List;

/** One "Caused by" cause (mirrors {@link org.log2code.core.model.CausedBy}). */
public record CausedByDto(String className, String message, List<StackFrameDto> frames) {
}
