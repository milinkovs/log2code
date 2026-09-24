package org.log2code.ingester.follow;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.log2code.ingester.follow.FileTailer.TailChunk;

/**
 * T22 AC4 at the tailer level: a temp file appended to in several separate steps, polled after each
 * one - only complete new lines come back each time, a trailing not-yet-terminated line is held back
 * for the next poll, and a shrink (rotation/truncation) is detected and restarts reading from 0.
 */
class FileTailerTest {

    @Test
    void pollingAcrossMultipleAppendStepsReturnsOnlyTheNewCompleteLinesEachTime(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("customers-service.log");
        Files.writeString(file, "line one\nline two\n");

        TailChunk first = FileTailer.poll(file, 0, 0);
        assertThat(first.lines()).containsExactly("line one", "line two");
        assertThat(first.reset()).isFalse();

        append(file, "line three\n");
        TailChunk second = FileTailer.poll(file, first.newOffset(), first.size());
        assertThat(second.lines()).containsExactly("line three");

        // nothing new since the last poll
        TailChunk third = FileTailer.poll(file, second.newOffset(), second.size());
        assertThat(third.lines()).isEmpty();
        assertThat(third.newOffset()).isEqualTo(second.newOffset());

        append(file, "line four\nline five\n");
        TailChunk fourth = FileTailer.poll(file, third.newOffset(), third.size());
        assertThat(fourth.lines()).containsExactly("line four", "line five");
    }

    @Test
    void aTrailingLineNotYetTerminatedByNewlineIsHeldBackUntilTheNextPoll(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("customers-service.log");
        Files.writeString(file, "complete line\n");
        append(file, "still being writ"); // no trailing '\n' yet - Fluent Bit mid-write

        TailChunk chunk = FileTailer.poll(file, 0, 0);
        assertThat(chunk.lines()).containsExactly("complete line");

        // the partial line is re-read (with its completion) on the next poll, offset unchanged past it
        append(file, "ten\n");
        TailChunk next = FileTailer.poll(file, chunk.newOffset(), chunk.size());
        assertThat(next.lines()).containsExactly("still being written");
    }

    @Test
    void aSmallerFileThanLastObservedIsTreatedAsRotatedAndReadFromZero(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("customers-service.log");
        Files.writeString(file, "old line one\nold line two\nold line three\n");
        TailChunk before = FileTailer.poll(file, 0, 0);
        assertThat(before.lines()).hasSize(3);

        // rotated: file replaced with much smaller content
        Files.writeString(file, "fresh line\n");
        TailChunk after = FileTailer.poll(file, before.newOffset(), before.size());

        assertThat(after.reset()).isTrue();
        assertThat(after.lines()).containsExactly("fresh line");
    }

    @Test
    void prefixLinesReplaysExactlyTheAlreadyConsumedRange(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("customers-service.log");
        Files.writeString(file, "one\ntwo\nthree\n");
        TailChunk chunk = FileTailer.poll(file, 0, 0);

        assertThat(FileTailer.prefixLines(file, chunk.newOffset())).containsExactly("one", "two", "three");
        assertThat(FileTailer.prefixLines(file, 0)).isEmpty();
    }

    private static void append(Path file, String text) throws IOException {
        Files.writeString(file, text, StandardCharsets.UTF_8, StandardOpenOption.APPEND);
    }
}
