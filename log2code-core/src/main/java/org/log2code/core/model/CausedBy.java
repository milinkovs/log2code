package org.log2code.core.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/** One "Caused by" cause in an exception chain. */
public record CausedBy(@JsonProperty("class") String className, String message, List<StackFrame> frames) {
}
