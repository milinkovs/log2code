package org.log2code.ingester.follow;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.log2code.core.json.Json;

/** Loads/saves {@link FollowOffsets} at {@code data/work/ingest/live/offsets.json} (T22 step 2). */
final class OffsetStore {

    private OffsetStore() {
    }

    static FollowOffsets load(Path file) throws IOException {
        if (!Files.isRegularFile(file)) {
            return FollowOffsets.empty();
        }
        return Json.mapper().readValue(file.toFile(), FollowOffsets.class);
    }

    static void save(Path file, FollowOffsets offsets) throws IOException {
        Files.createDirectories(file.toAbsolutePath().getParent());
        Json.mapper().writerWithDefaultPrettyPrinter().writeValue(file.toFile(), offsets);
    }
}
