package org.log2code.api.dto;

/** A call found within a preceding statement (mirrors {@link org.log2code.core.model.CallSite}). */
public record CallSiteDto(int line, String text, String target, String targetMethodId, boolean resolved) {
}
