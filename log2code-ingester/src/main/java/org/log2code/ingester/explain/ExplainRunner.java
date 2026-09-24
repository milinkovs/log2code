package org.log2code.ingester.explain;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.log2code.ingester.IngesterUserException;
import org.log2code.ingester.assemble.AssemblyContext;
import org.log2code.ingester.assemble.EventAssembler;
import org.log2code.ingester.manifest.DatasetManifest;
import org.log2code.ingester.manifest.DatasetManifest.FileEntry;
import org.log2code.ingester.match.Matcher;
import org.log2code.core.model.LogEvent;

/**
 * {@code explain --dataset <folder> --at <fajl>:<linija>} (T21): finds the event that starts at, or
 * spans (a multiline message/stack trace), the given physical line of the given dataset file, and
 * returns {@link Matcher#explain(LogEvent)} for it verbatim - no formatting logic of its own (T20
 * already produces the exact output T21's task text asks for).
 */
public final class ExplainRunner {

    private ExplainRunner() {
    }

    public static String explain(DatasetManifest manifest, Path datasetDir, EventAssembler assembler,
                                  Matcher matcher, String fileArg, int line) throws IOException {
        FileEntry fileEntry = findFile(manifest, fileArg);
        Path logFile = datasetDir.resolve(fileEntry.path());
        if (!Files.isRegularFile(logFile)) {
            throw new IngesterUserException("dataset log file not found: " + logFile.toAbsolutePath());
        }
        AssemblyContext ctx = new AssemblyContext(manifest.datasetId(), fileEntry.path(), fileEntry.service(),
            fileEntry.module(), manifest.code(), manifest.logFormat(), manifest.oracle());

        try (Stream<LogEvent> events = assembler.assemble(logFile, ctx)) {
            LogEvent event = events
                .filter(e -> line >= e.lineNumber() && line < e.lineNumber() + e.lineCount())
                .findFirst()
                .orElseThrow(() -> new IngesterUserException(
                    "no event spans line " + line + " in " + fileEntry.path()));
            return matcher.explain(event);
        }
    }

    private static FileEntry findFile(DatasetManifest manifest, String fileArg) {
        for (FileEntry entry : manifest.files()) {
            if (entry.path().equals(fileArg) || entry.service().equals(fileArg)) {
                return entry;
            }
        }
        throw new IngesterUserException("no file in manifest matches \"" + fileArg + "\" (known paths: "
            + manifest.files().stream().map(FileEntry::path).toList() + ")");
    }
}
