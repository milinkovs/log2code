package org.log2code.api.dto;

/** {@code type}/{@code name}/{@code version} of a code unit (mirrors {@link org.log2code.core.model.CodeUnit}). */
public record CodeUnitDto(String type, String name, String version) {
}
