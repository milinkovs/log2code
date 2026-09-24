package org.log2code.ingester.follow;

import java.nio.file.Path;
import java.time.Instant;
import org.log2code.ingester.assemble.AssemblyContext;
import org.log2code.ingester.assemble.EventAccumulator;

/**
 * One manifest {@code files[]} entry's live tailing state (T22): the accumulator that survives across
 * poll cycles (so a multi-line event started in one poll can be finished in a later one), how far the
 * file has been read, its size as of the last poll, and when its still-open buffer (if any) was last
 * touched - the clock {@code --flush-timeout} counts against.
 */
final class TailedFile {

    private final String path;
    private final Path absolutePath;
    private final AssemblyContext context;

    private EventAccumulator accumulator;
    private long offset;
    private long size;
    private Instant lastAppendAt;

    TailedFile(String path, Path absolutePath, AssemblyContext context,
               EventAccumulator accumulator, long offset, long size) {
        this.path = path;
        this.absolutePath = absolutePath;
        this.context = context;
        this.accumulator = accumulator;
        this.offset = offset;
        this.size = size;
        this.lastAppendAt = Instant.now();
    }

    String path() {
        return path;
    }

    Path absolutePath() {
        return absolutePath;
    }

    AssemblyContext context() {
        return context;
    }

    EventAccumulator accumulator() {
        return accumulator;
    }

    void replaceAccumulator(EventAccumulator fresh) {
        this.accumulator = fresh;
    }

    long offset() {
        return offset;
    }

    void offset(long offset) {
        this.offset = offset;
    }

    long size() {
        return size;
    }

    void size(long size) {
        this.size = size;
    }

    Instant lastAppendAt() {
        return lastAppendAt;
    }

    void touch() {
        this.lastAppendAt = Instant.now();
    }
}
