package org.log2code.core.opensearch;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.log2code.core.json.Json;
import org.log2code.core.model.CatalogEntry;
import org.log2code.core.model.CodeUnit;
import org.log2code.core.model.EnclosingBlock;
import org.log2code.core.model.Level;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch.core.pit.PitRecord;
import org.opensearch.client.opensearch.generic.Requests;
import org.opensearch.client.opensearch.generic.Response;
import org.opensearch.testcontainers.OpenSearchContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Integration tests for the OpenSearch layer (T05): index creation from the 0.7 mappings, bulk
 * writes, PIT/search_after streaming, recreate, delete-by-query. One OpenSearch container is
 * shared by all tests; each test works in its own randomly prefixed set of indices so tests
 * cannot interfere with each other.
 */
@Testcontainers
class OpenSearchLayerIT {

    @Container
    static final OpenSearchContainer<?> OPENSEARCH = new OpenSearchContainer<>("opensearchproject/opensearch:2.19.0")
        .withEnv("OPENSEARCH_JAVA_OPTS", "-Xms512m -Xmx512m");

    private static OpenSearchClient client;

    @BeforeAll
    static void setUpClient() {
        client = OpenSearchClientFactory.create(OpenSearchConfig.of(OPENSEARCH.getHttpHostAddress()));
    }

    @AfterAll
    static void tearDownClient() throws IOException {
        OpenSearchClientFactory.close(client);
    }

    @Test
    void ensureAllCreatesAllSevenIndices() throws IOException {
        IndexNames names = freshIndexNames();
        IndexManager manager = new IndexManager(client, names);

        manager.ensureAll();

        for (String index : names.all()) {
            assertThat(manager.exists(index)).as("index %s exists", index).isTrue();
        }
    }

    @Test
    void ensureAllDoesNotFailWhenIndicesAlreadyExist() throws IOException {
        IndexNames names = freshIndexNames();
        IndexManager manager = new IndexManager(client, names);

        manager.ensureAll();
        manager.ensureAll();

        for (String index : names.all()) {
            assertThat(manager.exists(index)).isTrue();
        }
    }

    @Test
    void bulkWriterWritesAndDocumentReaderStreamsBackTenThousandDocuments() throws IOException {
        IndexNames names = freshIndexNames();
        IndexManager manager = new IndexManager(client, names);
        manager.ensureAll();

        int total = 10_000;
        BulkWriter.Report report;
        try (BulkWriter<CatalogEntry> writer = new BulkWriter<>(client, names.catalog(), CatalogEntry::statementId)) {
            for (int i = 0; i < total; i++) {
                writer.add(sampleCatalogEntry(i, "spring-petclinic-customers-service"));
            }
            writer.flush();
            report = writer.report();
        }

        assertThat(report.failed()).isZero();
        assertThat(report.errors()).isEmpty();
        assertThat(report.succeeded()).isEqualTo(total);

        manager.refresh(names.catalog());
        assertThat(manager.count(names.catalog(), null)).isEqualTo(total);

        DocumentReader reader = new DocumentReader(client);
        long streamed;
        try (Stream<CatalogEntry> stream = reader.streamAll(names.catalog(), null, CatalogEntry.class, 733)) {
            streamed = stream.count();
        }
        assertThat(streamed).isEqualTo(total);
    }

    @Test
    void documentReaderGetReturnsWrittenDocumentAndNullWhenMissing() throws IOException {
        IndexNames names = freshIndexNames();
        IndexManager manager = new IndexManager(client, names);
        manager.ensureAll();

        CatalogEntry entry = sampleCatalogEntry(0, "spring-petclinic-customers-service");
        try (BulkWriter<CatalogEntry> writer = new BulkWriter<>(client, names.catalog(), CatalogEntry::statementId)) {
            writer.add(entry);
        }
        manager.refresh(names.catalog());

        DocumentReader reader = new DocumentReader(client);
        CatalogEntry read = reader.get(names.catalog(), entry.statementId(), CatalogEntry.class);
        assertThat(read).isEqualTo(entry);
        assertThat(reader.get(names.catalog(), "does-not-exist", CatalogEntry.class)).isNull();
    }

    @Test
    void documentReaderExistsChecksPresenceWithoutFetchingTheSource() throws IOException {
        // T21: the dependency-source-exists check for exception.frames[].file_id does not need the
        // (large, index:false) content field at all - just whether the id is there.
        IndexNames names = freshIndexNames();
        IndexManager manager = new IndexManager(client, names);
        manager.ensureAll();

        CatalogEntry entry = sampleCatalogEntry(0, "spring-petclinic-customers-service");
        try (BulkWriter<CatalogEntry> writer = new BulkWriter<>(client, names.catalog(), CatalogEntry::statementId)) {
            writer.add(entry);
        }
        manager.refresh(names.catalog());

        DocumentReader reader = new DocumentReader(client);
        assertThat(reader.exists(names.catalog(), entry.statementId())).isTrue();
        assertThat(reader.exists(names.catalog(), "does-not-exist")).isFalse();
    }

    @Test
    void bulkWriterCloseFlushesDocumentsLeftInTheBufferBelowTheBatchThreshold() throws IOException {
        IndexNames names = freshIndexNames();
        IndexManager manager = new IndexManager(client, names);
        manager.ensureAll();

        // No add() call reaches the default threshold (1000 documents / 5 MB), and flush() is
        // never called explicitly: only close() is. If close() did not flush, these 3 documents
        // would sit in the buffer forever and report() would show 0 succeeded.
        BulkWriter<CatalogEntry> writer = new BulkWriter<>(client, names.catalog(), CatalogEntry::statementId);
        writer.add(sampleCatalogEntry(0, "module-a"));
        writer.add(sampleCatalogEntry(1, "module-a"));
        writer.add(sampleCatalogEntry(2, "module-a"));
        writer.close();

        BulkWriter.Report report = writer.report();
        assertThat(report.succeeded()).isEqualTo(3);
        assertThat(report.failed()).isZero();

        manager.refresh(names.catalog());
        assertThat(manager.count(names.catalog(), null)).isEqualTo(3);
    }

    @Test
    void streamAllDeletesThePointInTimeWhenTheStreamIsClosed() throws IOException {
        IndexNames names = freshIndexNames();
        IndexManager manager = new IndexManager(client, names);
        manager.ensureAll();

        try (BulkWriter<CatalogEntry> writer = new BulkWriter<>(client, names.catalog(), CatalogEntry::statementId)) {
            writer.add(sampleCatalogEntry(0, "spring-petclinic-customers-service"));
        }
        manager.refresh(names.catalog());

        // Sanity check: no PIT left open by an earlier test (each test opens and closes its own).
        assertThat(client.listAllPit().pits()).isEmpty();

        DocumentReader reader = new DocumentReader(client);
        List<String> pitIdsWhileStreamIsOpen;
        try (Stream<CatalogEntry> stream = reader.streamAll(names.catalog(), null, CatalogEntry.class, 10)) {
            stream.findFirst(); // force at least one page fetch, so the PIT is actually in use
            pitIdsWhileStreamIsOpen = client.listAllPit().pits().stream().map(PitRecord::pitId).toList();
        }

        assertThat(pitIdsWhileStreamIsOpen).hasSize(1);
        assertThat(client.listAllPit().pits()).isEmpty();
    }

    @Test
    void recreateDropsExistingDocumentsButKeepsTheMapping() throws IOException {
        IndexNames names = freshIndexNames();
        IndexManager manager = new IndexManager(client, names);
        manager.ensureAll();

        try (BulkWriter<CatalogEntry> writer = new BulkWriter<>(client, names.catalog(), CatalogEntry::statementId)) {
            writer.add(sampleCatalogEntry(0, "spring-petclinic-customers-service"));
        }
        manager.refresh(names.catalog());
        assertThat(manager.count(names.catalog(), null)).isEqualTo(1);

        manager.recreate(names.catalog());

        assertThat(manager.exists(names.catalog())).isTrue();
        assertThat(manager.count(names.catalog(), null)).isZero();
        assertThat(fetchMappingProperties(names.catalog()).path("statement_id").path("type").asText()).isEqualTo("keyword");
    }

    @Test
    void deleteByQueryRemovesOnlyMatchingDocuments() throws IOException {
        IndexNames names = freshIndexNames();
        IndexManager manager = new IndexManager(client, names);
        manager.ensureAll();

        try (BulkWriter<CatalogEntry> writer = new BulkWriter<>(client, names.catalog(), CatalogEntry::statementId)) {
            writer.add(sampleCatalogEntry(0, "module-a"));
            writer.add(sampleCatalogEntry(1, "module-a"));
            writer.add(sampleCatalogEntry(2, "module-b"));
        }
        manager.refresh(names.catalog());
        assertThat(manager.count(names.catalog(), null)).isEqualTo(3);

        manager.deleteByQuery(names.catalog(), Map.of("module", "module-a"));

        assertThat(manager.count(names.catalog(), null)).isEqualTo(1);
        assertThat(manager.count(names.catalog(), Map.of("module", "module-b"))).isEqualTo(1);
    }

    @Test
    void mappingsMatchTheSchemaFrom0_7() throws IOException {
        IndexNames names = freshIndexNames();
        IndexManager manager = new IndexManager(client, names);
        manager.ensureAll();

        JsonNode catalog = fetchMappingProperties(names.catalog());
        assertThat(catalog.path("statement_id").path("type").asText()).isEqualTo("keyword");
        assertThat(catalog.path("code_unit").path("properties").path("name").path("type").asText()).isEqualTo("keyword");
        assertThat(catalog.path("in_lambda").path("type").asText()).isEqualTo("boolean");
        assertThat(catalog.path("line").path("type").asText()).isEqualTo("integer");
        assertThat(catalog.path("template").path("type").asText()).isEqualTo("text");
        assertThat(catalog.path("template").path("fields").path("keyword").path("type").asText()).isEqualTo("keyword");
        assertThat(catalog.path("template").path("fields").path("keyword").path("ignore_above").asInt()).isEqualTo(1024);
        assertThat(catalog.path("template_raw").path("type").asText()).isEqualTo("text");
        assertThat(catalog.path("template_raw").path("index").asBoolean(true)).isFalse();
        assertThat(catalog.path("regex").path("type").asText()).isEqualTo("keyword");
        assertThat(catalog.path("regex").path("index").asBoolean(true)).isFalse();
        assertThat(catalog.path("control").path("enabled").asBoolean(true)).isFalse();
        assertThat(catalog.path("enclosing").path("properties").path("block_kind").path("type").asText()).isEqualTo("keyword");
        assertThat(catalog.path("analyzed_at").path("type").asText()).isEqualTo("date");
        assertThat(catalog.path("github_url").path("index").asBoolean(true)).isFalse();

        JsonNode sources = fetchMappingProperties(names.sources());
        assertThat(sources.path("content").path("type").asText()).isEqualTo("text");
        assertThat(sources.path("content").path("index").asBoolean(true)).isFalse();
        assertThat(sources.path("line_count").path("type").asText()).isEqualTo("integer");

        JsonNode methods = fetchMappingProperties(names.methods());
        assertThat(methods.path("calls").path("enabled").asBoolean(true)).isFalse();
        assertThat(methods.path("called_by").path("enabled").asBoolean(true)).isFalse();

        JsonNode logs = fetchMappingProperties(names.logs());
        assertThat(logs.path("message").path("type").asText()).isEqualTo("text");
        assertThat(logs.path("message").path("fields").path("keyword").path("ignore_above").asInt()).isEqualTo(512);
        assertThat(logs.path("exception").path("properties").path("frames").path("enabled").asBoolean(true)).isFalse();
        assertThat(logs.path("match").path("properties").path("confidence").path("type").asText()).isEqualTo("float");
        assertThat(logs.path("ground_truth").path("properties").path("reliable").path("type").asText()).isEqualTo("boolean");
    }

    private JsonNode fetchMappingProperties(String index) throws IOException {
        try (Response response = client.generic()
            .execute(Requests.builder().endpoint("/" + index + "/_mapping").method("GET").build())) {
            String body = response.getBody().orElseThrow().bodyAsString();
            return Json.mapper().readTree(body).path(index).path("mappings").path("properties");
        }
    }

    private static IndexNames freshIndexNames() {
        return new IndexNames("it-" + UUID.randomUUID() + "-");
    }

    private static CatalogEntry sampleCatalogEntry(int ordinal, String module) {
        return new CatalogEntry(
            "stmt-" + module + "-" + ordinal,
            "logical-" + module + "-" + ordinal,
            new CodeUnit(CodeUnit.TYPE_PROJECT, "spring-petclinic-microservices", "3858f9c630cf989bb6809a86edf47c2be78dc9f1"),
            module,
            "customers-service",
            "spring-petclinic-customers-service/src/main/java/.../OwnerResource.java",
            "file-1",
            "org.springframework.samples.petclinic.customers.web",
            "org.springframework.samples.petclinic.customers.web.OwnerResource",
            "org.springframework.samples.petclinic.customers.web.OwnerResource",
            "save",
            "save(Owner)",
            null,
            false,
            89, 89, 8,
            80, 92,
            "slf4j",
            "typed",
            "log",
            "org.springframework.samples.petclinic.customers.web.OwnerResource",
            "class_literal",
            Level.INFO,
            false,
            "\"Saving owner {}\", owner",
            "Saving owner {}",
            "placeholders",
            null,
            "^\\QSaving owner \\E(.*?)$",
            List.of("saving", "owner"),
            12, 1,
            false,
            new EnclosingBlock("method_body", null, null, 80, 92),
            null,
            "    public Owner save(Owner owner) {\n        log.info(\"Saving owner {}\", owner);\n",
            85,
            null,
            "0.1.0-SNAPSHOT",
            Instant.parse("2026-09-22T10:00:05Z")
        );
    }
}
