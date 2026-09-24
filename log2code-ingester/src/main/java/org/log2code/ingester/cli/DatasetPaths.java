package org.log2code.ingester.cli;

import java.nio.file.Files;
import java.nio.file.Path;
import org.log2code.ingester.IngesterUserException;

/**
 * Resolves a {@code --dataset} argument to a dataset folder: either a path to the folder directly (T21's
 * own example, {@code ingest --dataset datasets/smoke-01}), or a bare {@code dataset_id} looked up under
 * {@code datasets/}.
 */
final class DatasetPaths {

    private static final Path DATASETS_ROOT = Path.of("datasets");

    private DatasetPaths() {
    }

    static Path resolve(String datasetArg) {
        Path direct = Path.of(datasetArg);
        if (Files.isDirectory(direct)) {
            return direct;
        }
        Path underRoot = DATASETS_ROOT.resolve(datasetArg);
        if (Files.isDirectory(underRoot)) {
            return underRoot;
        }
        throw new IngesterUserException("dataset folder not found: tried " + direct.toAbsolutePath()
            + " and " + underRoot.toAbsolutePath());
    }
}
