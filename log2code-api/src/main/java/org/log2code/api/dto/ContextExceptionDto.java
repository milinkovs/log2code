package org.log2code.api.dto;

import java.util.List;

/**
 * The {@code exception} section of {@link ContextBundleDto} (T25 step 1): the log's exception with
 * its frames resolved, project frames carrying a {@code snippet} (mirrors {@link ExceptionInfoDto}).
 */
public record ContextExceptionDto(
    String className,
    String rootClass,
    String message,
    List<ContextStackFrameDto> frames,
    List<ContextCausedByDto> causedBy
) {
}
