package org.log2code.eval.data;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.log2code.core.model.CodeVersion;
import org.log2code.core.model.EnrichedLog;
import org.log2code.core.model.Label;
import org.log2code.eval.truth.CatalogView;

/** Everything an evaluation reads. The OpenSearch implementation is the real one; tests use an in-memory one. */
public interface EvalDataSource {

    /** The ingested events of a dataset, in a stable order (source file, then line number). */
    List<EnrichedLog> events(String datasetId) throws IOException;

    /** Manual labels of a dataset, by {@code log_id}. Empty when nothing was labelled. */
    Map<String, Label> labels(String datasetId) throws IOException;

    /** The statements applicable per service for this exact code version. */
    CatalogView catalog(CodeVersion code) throws IOException;
}
