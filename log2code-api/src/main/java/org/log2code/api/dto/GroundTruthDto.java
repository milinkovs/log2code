package org.log2code.api.dto;

/** Ground truth attached by the oracle recorder, oracle datasets only (mirrors {@link org.log2code.core.model.GroundTruth}). */
public record GroundTruthDto(String className, String method, Integer line, Boolean reliable) {
}
