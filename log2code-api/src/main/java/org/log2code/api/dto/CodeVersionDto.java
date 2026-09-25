package org.log2code.api.dto;

/** The code version a log event was produced by (mirrors {@link org.log2code.core.model.CodeVersion}). */
public record CodeVersionDto(String name, String version) {
}
