package org.log2code.eval.data;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.log2code.core.model.AnalysisRun;
import org.log2code.core.model.CatalogEntry;
import org.log2code.core.model.CodeUnit;
import org.log2code.core.model.CodeVersion;
import org.log2code.core.model.EnrichedLog;
import org.log2code.core.model.Label;
import org.log2code.core.model.ModuleInfo;
import org.log2code.core.opensearch.DocumentReader;
import org.log2code.core.opensearch.IndexNames;
import org.log2code.eval.EvalUserException;
import org.log2code.eval.truth.CatalogView;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch._types.FieldValue;
import org.opensearch.client.opensearch._types.query_dsl.Query;

/**
 * Reads events ({@code log2code-logs}), manual labels ({@code log2code-labels}) and the catalog
 * ({@code log2code-catalog}, {@code log2code-runs}) from OpenSearch. Read-only: nothing is created or written.
 */
public final class OpenSearchEvalDataSource implements EvalDataSource {

    private static final int PAGE_SIZE = 1000;

    private final OpenSearchClient client;
    private final IndexNames indexNames;
    private final DocumentReader reader;

    public OpenSearchEvalDataSource(OpenSearchClient client, IndexNames indexNames) {
        this.client = client;
        this.indexNames = indexNames;
        this.reader = new DocumentReader(client);
    }

    @Override
    public List<EnrichedLog> events(String datasetId) throws IOException {
        if (!indexExists(indexNames.logs())) {
            return List.of();
        }
        List<EnrichedLog> events;
        try (Stream<EnrichedLog> stream = reader.streamAll(indexNames.logs(), datasetQuery(datasetId), EnrichedLog.class, PAGE_SIZE)) {
            events = new ArrayList<>(stream.toList());
        }
        events.sort(Comparator.comparing(EnrichedLog::sourceFile).thenComparingInt(EnrichedLog::lineNumber));
        return events;
    }

    @Override
    public Map<String, Label> labels(String datasetId) throws IOException {
        Map<String, Label> labels = new LinkedHashMap<>();
        if (!indexExists(indexNames.labels())) {
            return labels;
        }
        try (Stream<Label> stream = reader.streamAll(indexNames.labels(), datasetQuery(datasetId), Label.class, PAGE_SIZE)) {
            stream.forEach(label -> labels.put(label.logId(), label));
        }
        return labels;
    }

    @Override
    public CatalogView catalog(CodeVersion code) throws IOException {
        AnalysisRun run = findRun(code);

        Set<CodeUnit> codeUnits = new LinkedHashSet<>();
        codeUnits.add(run.codeUnit());
        for (ModuleInfo module : run.modules()) {
            for (String gav : module.selectedDependencies()) {
                int firstColon = gav.indexOf(':');
                int secondColon = gav.indexOf(':', firstColon + 1);
                codeUnits.add(new CodeUnit(CodeUnit.TYPE_DEPENDENCY, gav.substring(0, secondColon), gav.substring(secondColon + 1)));
            }
        }

        List<CatalogEntry> entries = new ArrayList<>();
        try (Stream<CatalogEntry> stream = reader.streamAll(indexNames.catalog(), codeUnitsQuery(codeUnits), CatalogEntry.class, PAGE_SIZE)) {
            stream.forEach(entries::add);
        }
        return CatalogView.build(run, entries);
    }

    private AnalysisRun findRun(CodeVersion code) throws IOException {
        Query query = Query.of(q -> q.bool(b -> b.filter(List.of(
            Query.of(f -> f.term(t -> t.field("kind").value(FieldValue.of(CodeUnit.TYPE_PROJECT)))),
            Query.of(f -> f.term(t -> t.field("code_unit.name").value(FieldValue.of(code.name())))),
            Query.of(f -> f.term(t -> t.field("code_unit.version").value(FieldValue.of(code.version()))))))));
        List<AnalysisRun> runs = List.of();
        if (indexExists(indexNames.runs())) {
            try (Stream<AnalysisRun> stream = reader.streamAll(indexNames.runs(), query, AnalysisRun.class, 10)) {
                runs = stream.toList();
            }
        }
        return runs.stream().max(Comparator.comparing(AnalysisRun::startedAt))
            .orElseThrow(() -> new EvalUserException("no analysis run in " + indexNames.runs() + " for project '" + code.name()
                + "' at version '" + code.version() + "'; run 'analyzer project' first."));
    }

    private boolean indexExists(String index) throws IOException {
        return client.indices().exists(e -> e.index(index)).value();
    }

    private static Query datasetQuery(String datasetId) {
        return Query.of(q -> q.term(t -> t.field("dataset_id").value(FieldValue.of(datasetId))));
    }

    private static Query codeUnitsQuery(Set<CodeUnit> codeUnits) {
        List<Query> should = codeUnits.stream()
            .map(unit -> Query.of(q -> q.bool(b -> b.filter(List.of(
                Query.of(f -> f.term(t -> t.field("code_unit.name").value(FieldValue.of(unit.name())))),
                Query.of(f -> f.term(t -> t.field("code_unit.version").value(FieldValue.of(unit.version())))))))))
            .toList();
        return should.size() == 1 ? should.get(0) : Query.of(q -> q.bool(b -> b.should(should).minimumShouldMatch("1")));
    }
}
