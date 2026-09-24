package org.log2code.ingester.follow;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads whatever complete new lines a growing file has since {@code offset} (T22 step 2), without
 * touching already-processed bytes and without consuming a trailing line that has not been terminated
 * by {@code '\n'} yet (the writer - Fluent Bit - may still be mid-write when a poll happens; that tail
 * is simply re-read, along with whatever gets appended after it, on the next poll).
 */
final class FileTailer {

    private FileTailer() {
    }

    /** One tailed file's read position and last-seen size, in bytes - {@link FollowOffsets.FileOffset}'s shape. */
    record TailChunk(List<String> lines, long newOffset, long size, boolean reset) {
    }

    /**
     * @param offset        bytes already consumed for this file, as of the last poll
     * @param lastKnownSize the file's size as of the last poll - if the file is now smaller, it was
     *                      rotated/truncated (T22 text: "ako se fajl skratio, čitanje kreće od 0")
     */
    static TailChunk poll(Path file, long offset, long lastKnownSize) throws IOException {
        long currentSize = Files.size(file);
        boolean reset = currentSize < lastKnownSize;
        long startOffset = reset ? 0 : Math.min(offset, currentSize);

        if (startOffset >= currentSize) {
            return new TailChunk(List.of(), startOffset, currentSize, reset);
        }

        byte[] chunk = readRange(file, startOffset, currentSize - startOffset);
        int lastNewline = lastIndexOf(chunk, (byte) '\n');
        long newOffset = startOffset + lastNewline + 1;
        return new TailChunk(splitCompleteLines(chunk, lastNewline), newOffset, currentSize, reset);
    }

    /**
     * Replays the lines in {@code [0, uptoOffset)} - a byte range {@link #poll} previously reported as
     * fully consumed (so it always ends exactly at a {@code '\n'}) - so a freshly started
     * {@link org.log2code.ingester.assemble.EventAccumulator} can recover the same
     * {@code line_number}/{@code sequence} counters and pending-buffer state a still-running process
     * would have, without needing to persist those separately (T22's restart/idempotency requirement).
     */
    static List<String> prefixLines(Path file, long uptoOffset) throws IOException {
        if (uptoOffset <= 0) {
            return List.of();
        }
        byte[] chunk = readRange(file, 0, uptoOffset);
        return splitCompleteLines(chunk, chunk.length - 1);
    }

    private static byte[] readRange(Path file, long start, long length) throws IOException {
        byte[] chunk = new byte[(int) length];
        try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
            raf.seek(start);
            raf.readFully(chunk);
        }
        return chunk;
    }

    private static List<String> splitCompleteLines(byte[] chunk, int lastNewline) {
        List<String> lines = new ArrayList<>();
        int lineStart = 0;
        for (int i = 0; i <= lastNewline; i++) {
            if (chunk[i] == '\n') {
                lines.add(stripTrailingCr(new String(chunk, lineStart, i - lineStart, StandardCharsets.UTF_8)));
                lineStart = i + 1;
            }
        }
        return lines;
    }

    private static int lastIndexOf(byte[] chunk, byte value) {
        for (int i = chunk.length - 1; i >= 0; i--) {
            if (chunk[i] == value) {
                return i;
            }
        }
        return -1;
    }

    private static String stripTrailingCr(String line) {
        return line.endsWith("\r") ? line.substring(0, line.length() - 1) : line;
    }
}
