package org.log2code.api.service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import org.log2code.api.dto.CallerDto;
import org.log2code.api.dto.MethodDetailDto;
import org.log2code.core.model.CallerRef;
import org.log2code.core.model.MethodInfo;
import org.log2code.core.opensearch.DocumentReader;
import org.log2code.core.opensearch.IndexNames;

/**
 * Backs {@code GET /api/methods/{methodId}} and {@code /callers} (T24 step 1, level 3 / T13 data).
 * Returns {@code null} for an unknown {@code methodId} (mirrors {@link DocumentReader#get}); the
 * controller decides how to turn that into a 404.
 */
public final class MethodGraphService {

    private final DocumentReader documentReader;
    private final IndexNames indexNames;

    public MethodGraphService(DocumentReader documentReader, IndexNames indexNames) {
        this.documentReader = documentReader;
        this.indexNames = indexNames;
    }

    public MethodInfo fetchMethod(String methodId) {
        try {
            return documentReader.get(indexNames.methods(), methodId, MethodInfo.class);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public MethodDetailDto toDetail(MethodInfo info) {
        return MethodMapper.toDetailDto(info);
    }

    /**
     * The callers of {@code target}, each joined with ITS OWN {@code caller_count}/{@code annotations}
     * (not the target's) so the UI can lazily expand further without a second round trip per row.
     */
    public List<CallerDto> callers(MethodInfo target) {
        List<CallerRef> calledBy = target.calledBy() == null ? List.of() : target.calledBy();
        return calledBy.stream().map(this::toCallerDto).toList();
    }

    private CallerDto toCallerDto(CallerRef ref) {
        MethodInfo caller = fetchMethod(ref.methodId());
        int callerCount = caller == null ? 0 : caller.callerCount();
        List<String> annotations = caller == null || caller.annotations() == null ? List.of() : caller.annotations();
        return new CallerDto(ref.methodId(), ref.classFqn(), ref.methodName(), ref.fileId(), ref.line(), callerCount, annotations);
    }
}
