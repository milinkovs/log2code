package org.log2code.ingester.manifest;

import java.util.List;
import org.log2code.core.model.CodeVersion;

/** {@code datasets/<id>/manifest.yml} (0.11), as the ingester reads it. */
public record DatasetManifest(
    String datasetId,
    String description,
    String createdAt,
    boolean oracle,
    String logFormat,
    CodeVersion code,
    List<FileEntry> files,
    String notes
) {

    /** One {@code files[]} entry: a dataset log file and the service/module it belongs to. */
    public record FileEntry(String path, String service, String module) {
    }
}
