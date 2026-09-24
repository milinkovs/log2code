package org.log2code.ingester.follow;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@code data/work/ingest/live/offsets.json} (T22 step 2) round-trips and defaults sensibly when absent. */
class OffsetStoreTest {

    @Test
    void missingFileLoadsAsEmpty(@TempDir Path dir) throws IOException {
        FollowOffsets offsets = OffsetStore.load(dir.resolve("offsets.json"));
        assertThat(offsets.files()).isEmpty();
        assertThat(offsets.get("data/logs/customers-service.log")).isEqualTo(new FollowOffsets.FileOffset(0, 0));
    }

    @Test
    void savedOffsetsRoundTrip(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("nested").resolve("offsets.json");
        FollowOffsets saved = new FollowOffsets(Map.of(
            "data/logs/customers-service.log", new FollowOffsets.FileOffset(1234, 1300),
            "data/logs/vets-service.log", new FollowOffsets.FileOffset(0, 0)));

        OffsetStore.save(file, saved);
        FollowOffsets loaded = OffsetStore.load(file);

        assertThat(loaded).isEqualTo(saved);
        assertThat(loaded.get("data/logs/customers-service.log").offset()).isEqualTo(1234);
        assertThat(loaded.get("data/logs/customers-service.log").size()).isEqualTo(1300);
    }
}
