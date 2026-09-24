package org.log2code.ingester.follow;

import java.util.Map;

/**
 * {@code data/work/ingest/live/offsets.json} (T22 step 2): per tailed file, how far {@code follow}
 * has read on disk ({@code offset}, bytes) and the file's size the last time it was checked
 * ({@code size}, bytes) - a shrink (current size &lt; {@code size}) means the file was rotated or
 * truncated, so reading restarts from 0 (T22 text, rotation itself stays out of scope).
 *
 * <p>{@code offset} always lands on a line boundary (right after a {@code '\n'}, {@link FileTailer});
 * the exact physical {@code line_number}/{@code sequence} to resume counting from (needed so {@code
 * log_id}, 0.8, matches what it would have been without a restart) is not stored here - it is cheap
 * to recover by replaying the already-consumed prefix through {@link
 * org.log2code.ingester.assemble.EventAssembler}'s accumulator once at startup ({@link FollowSession}).
 */
public record FollowOffsets(Map<String, FileOffset> files) {

    public static FollowOffsets empty() {
        return new FollowOffsets(Map.of());
    }

    public FileOffset get(String path) {
        FileOffset offset = files.get(path);
        return offset != null ? offset : new FileOffset(0, 0);
    }

    /** One tailed file's read position ({@code offset}) and last-seen size ({@code size}), in bytes. */
    public record FileOffset(long offset, long size) {
    }
}
