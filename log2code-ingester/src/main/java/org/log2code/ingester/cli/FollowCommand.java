package org.log2code.ingester.cli;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import org.log2code.core.opensearch.IndexNames;
import org.log2code.core.opensearch.OpenSearchClientFactory;
import org.log2code.core.opensearch.OpenSearchConfig;
import org.log2code.ingester.IngesterCli;
import org.log2code.ingester.follow.FollowSession;
import org.log2code.ingester.manifest.DatasetManifest;
import org.log2code.ingester.manifest.ManifestLoader;
import org.opensearch.client.opensearch.OpenSearchClient;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;

/**
 * {@code ingest --follow [--manifest config/live-manifest.yml] [--poll 2s] [--flush-timeout 5s]} (T22):
 * tails the files {@code config/live-manifest.yml} lists (Fluent Bit's {@code data/logs/<service>.log},
 * T02) and ingests new events within one poll interval, for a live demo. Runs until interrupted
 * (Ctrl+C).
 */
@Command(name = "follow", description = "Tail data/logs/*.log and ingest new events as they arrive, until interrupted.")
public final class FollowCommand implements Callable<Integer> {

    private static final Path OFFSETS_FILE = Path.of("data/work/ingest/live/offsets.json");
    private static final Path REPO_ROOT = Path.of(".");

    @ParentCommand
    private IngesterCli parent;

    @Option(names = "--manifest", defaultValue = "config/live-manifest.yml",
        description = "Live manifest file (default: ${DEFAULT-VALUE}).")
    private Path manifestFile;

    @Option(names = "--poll", defaultValue = "2s", converter = SimpleDurationConverter.class,
        description = "How often to check the tailed files for new lines (default: ${DEFAULT-VALUE}).")
    private Duration pollInterval;

    @Option(names = "--flush-timeout", defaultValue = "5s", converter = SimpleDurationConverter.class,
        description = "How long an unfinished event is held before it is written anyway (default: ${DEFAULT-VALUE}).")
    private Duration flushTimeout;

    @Override
    public Integer call() throws IOException {
        DatasetManifest manifest = ManifestLoader.load(manifestFile);
        OpenSearchClient client = OpenSearchClientFactory.create(OpenSearchConfig.of(parent.openSearchUrl()));
        try {
            IndexNames indexNames = new IndexNames();
            Wiring.Components components = Wiring.build(client, indexNames, manifest);
            FollowSession session =
                FollowSession.open(client, indexNames, manifest, components, REPO_ROOT, OFFSETS_FILE, flushTimeout);

            System.out.println("follow: watching " + manifest.files().size() + " file(s) from " + manifestFile
                + ", polling every " + pollInterval + ", flush-timeout " + flushTimeout + " - Ctrl+C to stop");
            runUntilInterrupted(session);
            return 0;
        } finally {
            OpenSearchClientFactory.close(client);
        }
    }

    /**
     * Polls {@code session} every {@code pollInterval} until Ctrl+C. A {@link ReentrantLock} gives the
     * shutdown hook exclusive access to {@code session} for its final flush - {@link FollowSession} is
     * not thread-safe, and the hook runs on its own thread while the poll loop may still be mid-cycle.
     * The loop itself never calls {@code System.exit()}; it just returns once {@code stopped} is set,
     * so there is nothing that could deadlock against the hook (the classic hazard when a thread blocks
     * in {@code System.exit()} while a shutdown hook blocks joining it).
     */
    private void runUntilInterrupted(FollowSession session) throws IOException {
        ReentrantLock lock = new ReentrantLock();
        AtomicBoolean stopped = new AtomicBoolean(false);
        Thread pollingThread = Thread.currentThread();

        Thread hook = new Thread(() -> {
            lock.lock();
            try {
                stopped.set(true);
                session.shutdown();
                System.out.println("follow: stopped, offsets saved");
            } catch (IOException e) {
                System.err.println("follow: error while shutting down: " + e);
            } finally {
                lock.unlock();
            }
            pollingThread.interrupt();
        }, "ingester-follow-shutdown");
        Runtime.getRuntime().addShutdownHook(hook);

        while (!stopped.get()) {
            lock.lock();
            try {
                printProgress(session.pollOnce());
            } finally {
                lock.unlock();
            }
            if (stopped.get()) {
                return;
            }
            try {
                Thread.sleep(pollInterval.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private void printProgress(FollowSession.PollResult result) {
        if (result.written() > 0) {
            System.out.println("follow: wrote " + result.written() + " event(s)"
                + (result.filesMissing() > 0 ? " (" + result.filesMissing() + " file(s) not present yet)" : ""));
        }
    }
}
