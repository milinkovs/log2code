package org.log2code.core.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/** The exception attached to a log event, if any. */
public record ExceptionInfo(
    @JsonProperty("class") String className,
    String rootClass,
    String message,
    List<StackFrame> frames,
    List<CausedBy> causedBy
) {
}
