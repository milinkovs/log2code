package org.log2code.analyzer.template;

import org.log2code.core.template.MessageTemplate;

/**
 * The result of extracting one {@link org.log2code.analyzer.logging.LogCall}'s message template (T09).
 * T10 reads {@code regex}/{@code constantTokens}/{@code literalLength} straight off {@link #template()}
 * (T06 already computes those); this record only adds what T09 itself determines.
 *
 * @param templateRaw source text of the message argument (0.7 {@code template_raw}), always present
 * @param template the normalized template (0.7 {@code template}), or {@code null} when {@link #unsupportedReason()} is set
 * @param templateKind one of the {@link TemplateKind} constants
 * @param unsupportedReason one of the {@link UnsupportedReason} constants, or {@code null} unless {@code templateKind} is {@link TemplateKind#UNSUPPORTED}
 * @param placeholderCount {@link MessageTemplate#holeCount()} of {@link #template()}, or {@code 0} when unsupported
 */
public record ExtractedTemplate(
    String templateRaw,
    MessageTemplate template,
    String templateKind,
    String unsupportedReason,
    int placeholderCount
) {
}
