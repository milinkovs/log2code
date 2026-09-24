package org.log2code.ingester.assemble;

import java.util.Objects;
import org.log2code.core.model.CodeVersion;

/**
 * Per-file metadata that {@link EventAssembler} stamps onto every {@code LogEvent} it produces:
 * everything 0.11's dataset manifest records for one {@code files[]} entry, plus the code version
 * and log format resolved for the whole dataset. {@code EventAssembler} itself never reads
 * {@code manifest.yml}; T21 (ingester CLI) reads it and builds one context per file.
 *
 * <p>0.7 documents {@code dataset_id}, {@code source_file}, {@code service}, {@code module},
 * {@code code.*} and {@code parser_format} as always-populated keyword fields on {@code log2code-logs}
 * (never {@code null}) — the compact constructor fails fast on a blank/{@code null} value instead of
 * letting {@code EventAssembler} silently stamp bad data onto every event of the file (which would
 * otherwise surface much later, e.g. during evaluation or in the web UI, not at the point of misuse).
 */
public record AssemblyContext(
    String datasetId,
    String sourceFile,
    String service,
    String module,
    CodeVersion code,
    String parserFormat,
    boolean oracle
) {
    public AssemblyContext {
        requireNonBlank(datasetId, "datasetId");
        requireNonBlank(sourceFile, "sourceFile");
        requireNonBlank(service, "service");
        requireNonBlank(module, "module");
        requireNonBlank(parserFormat, "parserFormat");
        Objects.requireNonNull(code, "code");
    }

    private static void requireNonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be null/blank");
        }
    }
}
