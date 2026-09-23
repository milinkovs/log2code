package org.log2code.core.model;

/**
 * A method call found within one of a log statement's {@link ControlContext#preceding()} statements
 * (level 2, T11), e.g. {@code {line, text: "ownerRepository.save(owner)", target: "ownerRepository.save"}}.
 * {@code targetMethodId} and {@code resolved} are {@code null}/{@code false} here and populated by T13
 * once the call graph can resolve {@code target} to a project method.
 */
public record CallSite(int line, String text, String target, String targetMethodId, boolean resolved) {
}
