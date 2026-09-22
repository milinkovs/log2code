package org.log2code.core.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Ground truth attached to a log event by the oracle recorder (T16/T18), oracle datasets only. */
public record GroundTruth(@JsonProperty("class") String className, String method, Integer line, Boolean reliable) {
}
