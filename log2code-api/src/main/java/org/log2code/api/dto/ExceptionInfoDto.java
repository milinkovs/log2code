package org.log2code.api.dto;

import java.util.List;

/** The exception attached to a log event, if any (mirrors {@link org.log2code.core.model.ExceptionInfo}). */
public record ExceptionInfoDto(
    String className,
    String rootClass,
    String message,
    List<StackFrameDto> frames,
    List<CausedByDto> causedBy
) {
}
