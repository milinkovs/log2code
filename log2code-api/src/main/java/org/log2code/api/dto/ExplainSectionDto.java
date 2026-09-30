package org.log2code.api.dto;

/** One prompt section in {@link ExplainPromptDto}: whether it is in the prompt and, if not (or only partly), why. */
public record ExplainSectionDto(String id, boolean included, String reason) {
}
