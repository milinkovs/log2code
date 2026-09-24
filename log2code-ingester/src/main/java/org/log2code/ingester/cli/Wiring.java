package org.log2code.ingester.cli;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.function.Predicate;
import org.log2code.core.github.CodeUnitsConfig;
import org.log2code.core.github.GithubLinker;
import org.log2code.core.opensearch.DocumentReader;
import org.log2code.core.opensearch.IndexNames;
import org.log2code.ingester.assemble.EventAssembler;
import org.log2code.ingester.catalog.CatalogIndex;
import org.log2code.ingester.config.CodeUnitsConfigLoader;
import org.log2code.ingester.enrich.StackFrameResolver;
import org.log2code.ingester.manifest.DatasetManifest;
import org.log2code.ingester.match.Matcher;
import org.log2code.ingester.match.MatchingConfig;
import org.log2code.ingester.match.MatchingConfigLoader;
import org.log2code.ingester.parse.LineParser;
import org.log2code.ingester.parse.LogFormatRegistry;
import org.opensearch.client.opensearch.OpenSearchClient;

/**
 * Builds the collaborators {@code ingest}, {@code explain} and (T22) {@code follow} all need from a
 * dataset's {@link DatasetManifest} and the fixed {@code config/} files (T21 steps 1-2): the in-memory
 * catalog (T19/T20), the matcher (T20), the event assembler (T18) and the stack frame resolver (T21).
 * Public - T22's {@code follow} package builds its own {@link Components} the same way batch ingest
 * does, rather than a second wiring implementation.
 */
public final class Wiring {

    private static final Path MATCHING_CONFIG_FILE = Path.of("config/matching.yml");
    private static final Path LOG_FORMATS_CONFIG_FILE = Path.of("config/log-formats.yml");
    private static final Path CODE_UNITS_CONFIG_FILE = Path.of("config/code-units.yml");

    private Wiring() {
    }

    public record Components(CatalogIndex catalogIndex, Matcher matcher, EventAssembler assembler, StackFrameResolver frameResolver) {
    }

    public static Components build(OpenSearchClient client, IndexNames indexNames, DatasetManifest manifest) throws IOException {
        CatalogIndex catalogIndex = CatalogIndex.load(client, manifest.code().name(), manifest.code().version());

        MatchingConfig matchingConfig = MatchingConfigLoader.load(MATCHING_CONFIG_FILE);
        Matcher matcher = new Matcher(catalogIndex, matchingConfig);

        LogFormatRegistry formats = LogFormatRegistry.load(LOG_FORMATS_CONFIG_FILE);
        LineParser lineParser = formats.get(manifest.logFormat());
        EventAssembler assembler = new EventAssembler(lineParser, matchingConfig.oracle().unreliableCallers());

        CodeUnitsConfig codeUnitsConfig = CodeUnitsConfigLoader.load(CODE_UNITS_CONFIG_FILE);
        GithubLinker linker = new GithubLinker(codeUnitsConfig, catalogIndex.projectRepoUrl());
        DocumentReader reader = new DocumentReader(client);
        Predicate<String> dependencySourceExists = fileId -> existsUnchecked(reader, indexNames.sources(), fileId);
        StackFrameResolver frameResolver = new StackFrameResolver(catalogIndex, linker, dependencySourceExists);

        return new Components(catalogIndex, matcher, assembler, frameResolver);
    }

    private static boolean existsUnchecked(DocumentReader reader, String index, String id) {
        try {
            return reader.exists(index, id);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
