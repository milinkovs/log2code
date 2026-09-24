package org.log2code.ingester.follow;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.log2code.core.model.CatalogEntry;
import org.log2code.core.model.EnrichedLog;
import org.log2code.core.model.LogEvent;
import org.log2code.core.opensearch.BulkWriter;
import org.log2code.core.opensearch.DocumentReader;
import org.log2code.core.opensearch.IndexManager;
import org.log2code.core.opensearch.IndexNames;
import org.log2code.ingester.assemble.AssemblyContext;
import org.log2code.ingester.assemble.EventAccumulator;
import org.log2code.ingester.cli.Wiring;
import org.log2code.ingester.follow.FileTailer.TailChunk;
import org.log2code.ingester.ingest.EventEnricher;
import org.log2code.ingester.manifest.DatasetManifest;
import org.opensearch.client.opensearch.OpenSearchClient;

/**
 * T22's follow loop core: tails every file the manifest lists, holding one {@link EventAccumulator}
 * per file alive across poll cycles (so a multi-line event spanning several polls is not split), and
 * writes each completed event through the same {@code EventEnricher}/{@code BulkWriter} path batch
 * ingest uses. {@link #pollOnce()} is one round; the CLI ({@code FollowCommand}) drives it in a loop
 * with {@code --poll} between calls and a shutdown hook that calls {@link #shutdown()} once.
 *
 * <p>Not thread-safe (like {@link BulkWriter}): {@link #pollOnce()} and {@link #shutdown()} must only
 * ever be called from one thread at a time, never concurrently with each other.
 */
public final class FollowSession {

    private final OpenSearchClient client;
    private final IndexNames indexNames;
    private final Wiring.Components components;
    private final DocumentReader reader;
    private final Map<String, CatalogEntry> catalogCache = new HashMap<>();
    private final BulkWriter<EnrichedLog> writer;
    private final Path offsetsFile;
    private final Duration flushTimeout;
    private final List<TailedFile> tailedFiles;
    private long failedSoFar = 0;

    /** One {@link #pollOnce()} round's outcome - printed by the CLI, asserted by tests. */
    public record PollResult(int written, int filesMissing) {
    }

    private FollowSession(OpenSearchClient client, IndexNames indexNames, Wiring.Components components,
                           BulkWriter<EnrichedLog> writer, Path offsetsFile, Duration flushTimeout,
                           List<TailedFile> tailedFiles) {
        this.client = client;
        this.indexNames = indexNames;
        this.components = components;
        this.reader = new DocumentReader(client);
        this.writer = writer;
        this.offsetsFile = offsetsFile;
        this.flushTimeout = flushTimeout;
        this.tailedFiles = tailedFiles;
    }

    /**
     * Builds one session for {@code manifest}: an {@link EventAccumulator} per file, resumed from
     * {@code offsetsFile} (T22 step 2) - replaying each file's already-consumed byte prefix once so
     * the resumed accumulator's {@code line_number}/{@code sequence} counters match what they would
     * be had the process never stopped (0.7/0.8), without needing to persist those separately.
     *
     * @param repoRoot manifest {@code files[].path} entries are resolved against this (the project
     *                 root - {@code scripts/ingester.sh} always runs from there, 0.14)
     */
    public static FollowSession open(OpenSearchClient client, IndexNames indexNames, DatasetManifest manifest,
                                      Wiring.Components components, Path repoRoot, Path offsetsFile,
                                      Duration flushTimeout) throws IOException {
        new IndexManager(client, indexNames).ensureAll();
        FollowOffsets offsets = OffsetStore.load(offsetsFile);

        List<TailedFile> tailedFiles = new ArrayList<>();
        for (DatasetManifest.FileEntry fileEntry : manifest.files()) {
            tailedFiles.add(openTailedFile(manifest, fileEntry, components, repoRoot, offsets));
        }

        BulkWriter<EnrichedLog> writer = new BulkWriter<>(client, indexNames.logs(), EnrichedLog::logId);
        return new FollowSession(client, indexNames, components, writer, offsetsFile, flushTimeout, tailedFiles);
    }

    private static TailedFile openTailedFile(DatasetManifest manifest, DatasetManifest.FileEntry fileEntry,
                                              Wiring.Components components, Path repoRoot, FollowOffsets offsets) throws IOException {
        String relPath = fileEntry.path();
        Path absolute = repoRoot.resolve(relPath);
        AssemblyContext context = new AssemblyContext(manifest.datasetId(), relPath, fileEntry.service(),
            fileEntry.module(), manifest.code(), manifest.logFormat(), manifest.oracle());

        FollowOffsets.FileOffset saved = offsets.get(relPath);
        boolean existsNow = Files.isRegularFile(absolute);
        long currentSize = existsNow ? Files.size(absolute) : 0;
        boolean shrunk = currentSize < saved.size();
        long startOffset = shrunk ? 0 : saved.offset();

        EventAccumulator accumulator = components.assembler().newAccumulator(context);
        if (!shrunk && startOffset > 0 && existsNow) {
            for (String line : FileTailer.prefixLines(absolute, startOffset)) {
                accumulator.offer(line); // already ingested on a previous run - discard, only reconstructing state
            }
        }
        return new TailedFile(relPath, absolute, context, accumulator, startOffset, currentSize);
    }

    /** One round: tail every file for new lines, feed the accumulators, flush idle buffers, persist offsets. */
    public PollResult pollOnce() throws IOException {
        int written = 0;
        int missing = 0;
        for (TailedFile file : tailedFiles) {
            if (!Files.isRegularFile(file.absolutePath())) {
                missing++;
                continue;
            }
            written += pollFile(file);
            if (file.accumulator().hasPending()
                && Duration.between(file.lastAppendAt(), Instant.now()).compareTo(flushTimeout) >= 0) {
                written += emit(file, file.accumulator().flushPending());
            }
        }
        writer.flush();
        failFastOnNewBulkErrors();
        OffsetStore.save(offsetsFile, snapshotOffsets());
        return new PollResult(written, missing);
    }

    private int pollFile(TailedFile file) throws IOException {
        TailChunk chunk = FileTailer.poll(file.absolutePath(), file.offset(), file.size());
        if (chunk.reset()) {
            file.replaceAccumulator(components.assembler().newAccumulator(file.context()));
        }
        int written = 0;
        for (String line : chunk.lines()) {
            Optional<LogEvent> ready = file.accumulator().offer(line);
            written += emit(file, ready);
        }
        file.offset(chunk.newOffset());
        file.size(chunk.size());
        return written;
    }

    private int emit(TailedFile file, Optional<LogEvent> event) throws IOException {
        if (event.isEmpty()) {
            if (file.accumulator().hasPending()) {
                file.touch();
            }
            return 0;
        }
        EnrichedLog enriched = EventEnricher.enrich(
            event.get(), components.catalogIndex(), components.matcher(), components.frameResolver(), reader, indexNames, catalogCache);
        writer.add(enriched);
        file.touch();
        return 1;
    }

    /** Ctrl+C (T22 step 2): force-closes every still-open buffer, writes it, then persists final offsets. */
    public void shutdown() throws IOException {
        for (TailedFile file : tailedFiles) {
            emit(file, file.accumulator().flushPending());
        }
        writer.flush();
        failFastOnNewBulkErrors();
        OffsetStore.save(offsetsFile, snapshotOffsets());
    }

    /**
     * {@link BulkWriter#report()} is cumulative for the writer's whole lifetime, but a follow session
     * calls this after every poll cycle - so this only fails on failures *new* since the last check
     * (a delta), rather than re-throwing forever on one stale failure from many cycles ago.
     */
    private void failFastOnNewBulkErrors() throws IOException {
        BulkWriter.Report report = writer.report();
        if (report.failed() > failedSoFar) {
            long newFailures = report.failed() - failedSoFar;
            failedSoFar = report.failed();
            throw new IOException("bulk write failed for " + newFailures
                + " new document(s), recent error(s): " + report.errors());
        }
    }

    private FollowOffsets snapshotOffsets() {
        Map<String, FollowOffsets.FileOffset> files = new LinkedHashMap<>();
        for (TailedFile file : tailedFiles) {
            files.put(file.path(), new FollowOffsets.FileOffset(file.offset(), file.size()));
        }
        return new FollowOffsets(files);
    }
}
