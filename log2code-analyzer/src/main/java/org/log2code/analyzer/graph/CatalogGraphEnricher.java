package org.log2code.analyzer.graph;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.log2code.core.model.CallEdge;
import org.log2code.core.model.CallSite;
import org.log2code.core.model.CatalogEntry;
import org.log2code.core.model.ControlContext;
import org.log2code.core.model.MethodInfo;

/**
 * Back-fills {@code control.calls_before[].target_method_id}/{@code resolved} (T13 step 5) once the
 * call graph exists: {@code catalog.method_id} is already set at catalog-build time (T10/T11's own
 * {@code CatalogEntryBuilder}, a pure function of already-known fields), but whether a {@code
 * CallSite}'s {@code target} actually reaches a project method can only be known after the graph is
 * built. A {@code CallSite} is matched to the {@code MethodInfo.calls[]} entry for the exact same
 * physical call by {@code (line, text)}: both were produced from the same {@code
 * ControlContextExtractor.truncate(node.toString())} convention (ADR-011/ADR-013), so the same source
 * call yields the same pair in both places. Where a call resolved to both a project interface method
 * and, via T13 step 3, its single implementation, the interface edge (added first) is treated as
 * primary here - {@code CallSite} has room for one target, matching the call as actually written.
 */
public final class CatalogGraphEnricher {

    private CatalogGraphEnricher() {
    }

    public static List<CatalogEntry> enrich(List<CatalogEntry> catalog, List<MethodInfo> methods) {
        Map<String, MethodInfo> methodsById = new LinkedHashMap<>();
        for (MethodInfo method : methods) {
            methodsById.put(method.methodId(), method);
        }
        List<CatalogEntry> result = new ArrayList<>(catalog.size());
        for (CatalogEntry entry : catalog) {
            result.add(enrichEntry(entry, methodsById));
        }
        return result;
    }

    private static CatalogEntry enrichEntry(CatalogEntry entry, Map<String, MethodInfo> methodsById) {
        ControlContext control = entry.control();
        if (control == null || control.callsBefore().isEmpty() || entry.methodId() == null) {
            return entry;
        }
        MethodInfo method = methodsById.get(entry.methodId());
        if (method == null) {
            return entry;
        }

        Map<LineText, CallEdge> primaryEdgeByLineText = new LinkedHashMap<>();
        for (CallEdge edge : method.calls()) {
            primaryEdgeByLineText.putIfAbsent(new LineText(edge.line(), edge.text()), edge);
        }

        boolean changed = false;
        List<CallSite> updated = new ArrayList<>(control.callsBefore().size());
        for (CallSite site : control.callsBefore()) {
            CallEdge match = primaryEdgeByLineText.get(new LineText(site.line(), site.text()));
            if (match != null && (match.resolved() != site.resolved() || !Objects.equals(match.targetMethodId(), site.targetMethodId()))) {
                updated.add(new CallSite(site.line(), site.text(), site.target(), match.targetMethodId(), match.resolved()));
                changed = true;
            } else {
                updated.add(site);
            }
        }
        if (!changed) {
            return entry;
        }
        return withControl(entry, new ControlContext(control.conditions(), control.earlyExits(), control.preceding(), List.copyOf(updated)));
    }

    private record LineText(int line, String text) {
    }

    private static CatalogEntry withControl(CatalogEntry entry, ControlContext control) {
        return new CatalogEntry(entry.statementId(), entry.logicalId(), entry.codeUnit(), entry.module(), entry.service(),
            entry.filePath(), entry.fileId(), entry.packageName(), entry.classFqn(), entry.classBinary(),
            entry.methodName(), entry.methodSignature(), entry.methodId(), entry.inLambda(), entry.line(), entry.endLine(),
            entry.column(), entry.methodStartLine(), entry.methodEndLine(), entry.loggingApi(), entry.detection(),
            entry.loggerExpr(), entry.loggerName(), entry.loggerNameKind(), entry.level(), entry.levelDynamic(),
            entry.templateRaw(), entry.template(), entry.templateKind(), entry.unsupportedReason(), entry.regex(),
            entry.constantTokens(), entry.literalLength(), entry.placeholderCount(), entry.hasThrowableArg(),
            entry.enclosing(), control, entry.snippet(), entry.snippetStartLine(), entry.githubUrl(),
            entry.analyzerVersion(), entry.analyzedAt());
    }
}
