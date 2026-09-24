package org.log2code.ingester.enrich;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import org.log2code.core.github.GithubLinker;
import org.log2code.core.ids.StableIds;
import org.log2code.core.model.CausedBy;
import org.log2code.core.model.CodeUnit;
import org.log2code.core.model.ExceptionInfo;
import org.log2code.core.model.StackFrame;
import org.log2code.core.model.TypeInfo;
import org.log2code.ingester.catalog.CatalogIndex;

/**
 * T21's "Rezolucija stack frame-ova" step (0.7's {@code exception.frames[]}): resolves each frame's
 * {@code class} against the in-memory catalog's types ({@link CatalogIndex#resolveType}, applicable to
 * the event's own service) and fills in {@code in_project}, {@code code_unit}, {@code file_id} and
 * {@code github_url} - left {@code null}/{@code false} by {@code StackTraceParser} (T18).
 *
 * <ul>
 *   <li>{@code in_project}: the resolved type's {@code code_unit.type = project}.</li>
 *   <li>{@code code_unit}: the resolved type's {@code code_unit.name}, whether project or dependency.</li>
 *   <li>{@code file_id}: for a project type, always derived from its {@code file_path} (0.7: every
 *       project source file is stored in {@code log2code-sources}); for a dependency type, only if that
 *       exact file actually exists there (0.7: dependency sources are stored only for files that have at
 *       least one log statement - checked via {@code dependencySourceExists}, cached per file id since
 *       the same dependency class recurs across many stack traces).</li>
 *   <li>{@code github_url}: via {@link GithubLinker}, whenever the type resolved and the frame has a
 *       line number (native/unknown-source frames have none) - independent of whether the file exists
 *       in {@code log2code-sources} (a GitHub link does not need our own cached copy of the file).</li>
 * </ul>
 *
 * <p>A frame whose class does not resolve (JDK classes, unselected dependencies, anything outside this
 * project's own applicable code) is returned unchanged.
 */
public final class StackFrameResolver {

    private final CatalogIndex catalogIndex;
    private final GithubLinker linker;
    private final Predicate<String> dependencySourceExists;
    private final Map<String, Boolean> dependencySourceExistsCache = new ConcurrentHashMap<>();

    public StackFrameResolver(CatalogIndex catalogIndex, GithubLinker linker, Predicate<String> dependencySourceExists) {
        this.catalogIndex = Objects.requireNonNull(catalogIndex, "catalogIndex");
        this.linker = Objects.requireNonNull(linker, "linker");
        this.dependencySourceExists = Objects.requireNonNull(dependencySourceExists, "dependencySourceExists");
    }

    /** Resolves every frame of {@code exception} (top-level and every {@code caused_by} link), or {@code null} if there is none. */
    public ExceptionInfo resolve(ExceptionInfo exception, String service) {
        if (exception == null) {
            return null;
        }
        List<CausedBy> causedBy = exception.causedBy().stream()
            .map(cb -> new CausedBy(cb.className(), cb.message(), resolveFrames(cb.frames(), service)))
            .toList();
        return new ExceptionInfo(exception.className(), exception.rootClass(), exception.message(),
            resolveFrames(exception.frames(), service), causedBy);
    }

    private List<StackFrame> resolveFrames(List<StackFrame> frames, String service) {
        return frames.stream().map(frame -> resolveFrame(frame, service)).toList();
    }

    private StackFrame resolveFrame(StackFrame frame, String service) {
        Optional<TypeInfo> resolved = catalogIndex.resolveType(frame.className(), service);
        if (resolved.isEmpty()) {
            return frame;
        }
        TypeInfo type = resolved.get();
        boolean inProject = CodeUnit.TYPE_PROJECT.equals(type.codeUnit().type());
        String candidateFileId = StableIds.fileId(type.codeUnit().name(), type.codeUnit().version(), type.filePath());
        String fileId = inProject || sourceExists(candidateFileId) ? candidateFileId : null;
        String githubUrl = frame.line() == null ? null
            : linker.link(type.codeUnit(), type.filePath(), frame.line(), frame.line());
        return new StackFrame(frame.className(), frame.method(), frame.file(), frame.line(),
            inProject, type.codeUnit().name(), fileId, githubUrl);
    }

    private boolean sourceExists(String fileId) {
        return dependencySourceExistsCache.computeIfAbsent(fileId, dependencySourceExists::test);
    }
}
