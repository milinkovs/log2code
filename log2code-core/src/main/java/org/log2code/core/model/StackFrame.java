package org.log2code.core.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/** One stack trace frame, optionally resolved against the catalog. */
public record StackFrame(
    @JsonProperty("class") String className,
    String method,
    String file,
    Integer line,
    boolean inProject,
    String codeUnit,
    String fileId,
    String githubUrl
) {
}
