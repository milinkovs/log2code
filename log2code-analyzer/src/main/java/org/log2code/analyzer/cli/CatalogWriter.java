package org.log2code.analyzer.cli;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.log2code.core.json.Json;
import org.log2code.core.model.CatalogEntry;
import org.log2code.core.model.CodeUnit;
import org.log2code.core.model.MethodInfo;
import org.log2code.core.model.SourceFile;
import org.log2code.core.model.TypeInfo;
import org.log2code.core.opensearch.BulkWriter;
import org.log2code.core.opensearch.IndexManager;
import org.log2code.core.opensearch.IndexNames;
import org.opensearch.client.opensearch.OpenSearchClient;

/**
 * Writes one code unit's catalog documents idempotently (T10 step 4): deletes every existing document
 * for this exact {@code code_unit.name}+{@code code_unit.version} from {@code catalog}, {@code sources},
 * {@code types} and {@code methods}, then bulk-writes the freshly built documents. Shared by
 * {@link ProjectCommand} (project, {@code catalog} graph-enriched by T13, real {@code methods}) and
 * {@code DepsAnalyzeCommand} (T14: one dependency artifact, {@code methods} always empty - no level 3
 * for dependencies).
 */
final class CatalogWriter {

    private static final String CATALOG_FILE = "catalog.jsonl";
    private static final String SOURCES_FILE = "sources.jsonl";
    private static final String TYPES_FILE = "types.jsonl";
    private static final String METHODS_FILE = "methods.jsonl";

    private CatalogWriter() {
    }

    static void writeToOpenSearch(OpenSearchClient client, IndexNames indexNames, CodeUnit codeUnit,
                                   List<CatalogEntry> catalog, List<SourceFile> sources, List<TypeInfo> types,
                                   List<MethodInfo> methods) throws IOException {
        IndexManager indexManager = new IndexManager(client, indexNames);
        indexManager.ensureAll();

        Map<String, String> versionFilter = Map.of("code_unit.name", codeUnit.name(), "code_unit.version", codeUnit.version());
        indexManager.deleteByQuery(indexNames.catalog(), versionFilter);
        indexManager.deleteByQuery(indexNames.sources(), versionFilter);
        indexManager.deleteByQuery(indexNames.types(), versionFilter);
        indexManager.deleteByQuery(indexNames.methods(), versionFilter);

        bulkWrite(client, indexNames.catalog(), CatalogEntry::statementId, catalog);
        bulkWrite(client, indexNames.sources(), SourceFile::fileId, sources);
        bulkWrite(client, indexNames.types(), TypeInfo::typeId, types);
        bulkWrite(client, indexNames.methods(), MethodInfo::methodId, methods);
    }

    private static <T> void bulkWrite(OpenSearchClient client, String index,
                                       java.util.function.Function<T, String> idFunction, List<T> documents) throws IOException {
        BulkWriter.Report report;
        try (BulkWriter<T> writer = new BulkWriter<>(client, index, idFunction)) {
            for (T document : documents) {
                writer.add(document);
            }
            writer.flush();
            report = writer.report();
        }
        if (report.failed() > 0) {
            throw new IOException("bulk write to " + index + " failed for " + report.failed() + " document(s): " + report.errors());
        }
    }

    static Path writeToJson(Path jsonDir, CodeUnit codeUnit, List<CatalogEntry> catalog, List<SourceFile> sources,
                             List<TypeInfo> types, List<MethodInfo> methods) throws IOException {
        Path dir = JsonPaths.forCodeUnit(jsonDir, codeUnit);
        Files.createDirectories(dir);
        writeJsonl(dir.resolve(CATALOG_FILE), catalog);
        writeJsonl(dir.resolve(SOURCES_FILE), sources);
        writeJsonl(dir.resolve(TYPES_FILE), types);
        writeJsonl(dir.resolve(METHODS_FILE), methods);
        return dir;
    }

    private static void writeJsonl(Path target, List<?> documents) throws IOException {
        try (BufferedWriter writer = Files.newBufferedWriter(target, StandardCharsets.UTF_8)) {
            for (Object document : documents) {
                writer.write(Json.mapper().writeValueAsString(document));
                writer.newLine();
            }
        }
    }
}
