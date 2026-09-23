package org.log2code.analyzer.graph;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.log2code.core.model.CallEdge;
import org.log2code.core.model.CallSite;
import org.log2code.core.model.CatalogEntry;
import org.log2code.core.model.CodeUnit;
import org.log2code.core.model.ControlContext;
import org.log2code.core.model.Level;
import org.log2code.core.model.MethodInfo;

/** T13 step 5: matches a {@code control.calls_before} {@code CallSite} to the {@code
 * MethodInfo.calls[]} entry for the same physical call by {@code (line, text)}, and fills in only
 * {@code target_method_id}/{@code resolved} - nothing else about the entry changes. */
class CatalogGraphEnricherTest {

    private static final CodeUnit CODE_UNIT = new CodeUnit(CodeUnit.TYPE_PROJECT, "p", "v1");

    @Test
    void fillsInTargetMethodIdAndResolvedForAMatchingCallSite() {
        CallSite unresolved = new CallSite(10, "repo.save(owner)", "repo.save", null, false);
        CatalogEntry entry = catalogEntry("method-1", List.of(unresolved));

        CallEdge resolvedEdge = new CallEdge(10, "repo.save(owner)", "target-method-1", null, true, false);
        MethodInfo method = methodInfo("method-1", List.of(resolvedEdge));

        List<CatalogEntry> enriched = CatalogGraphEnricher.enrich(List.of(entry), List.of(method));

        CallSite result = enriched.get(0).control().callsBefore().get(0);
        assertThat(result.targetMethodId()).isEqualTo("target-method-1");
        assertThat(result.resolved()).isTrue();
        // everything else about the call site is untouched
        assertThat(result.line()).isEqualTo(10);
        assertThat(result.text()).isEqualTo("repo.save(owner)");
        assertThat(result.target()).isEqualTo("repo.save");
    }

    @Test
    void leavesACallSiteWithNoMatchingCallEdgeUntouched() {
        CallSite unresolved = new CallSite(10, "repo.save(owner)", "repo.save", null, false);
        CatalogEntry entry = catalogEntry("method-1", List.of(unresolved));
        MethodInfo method = methodInfo("method-1", List.of()); // no calls recorded for this method at all

        List<CatalogEntry> enriched = CatalogGraphEnricher.enrich(List.of(entry), List.of(method));

        assertThat(enriched.get(0).control().callsBefore().get(0)).isEqualTo(unresolved);
    }

    @Test
    void leavesEntriesWithNoControlContextOrNoMethodIdUntouched() {
        CatalogEntry noControl = catalogEntryWithControl("method-1", null);
        CatalogEntry noMethodId = catalogEntry(null, List.of(new CallSite(1, "x()", "x", null, false)));

        List<CatalogEntry> enriched = CatalogGraphEnricher.enrich(List.of(noControl, noMethodId), List.of());

        assertThat(enriched).containsExactly(noControl, noMethodId);
    }

    @Test
    void picksThePrimaryEdgeWhenTwoEdgesShareTheSameLineAndText() {
        // the interface + single-implementation case (T13 step 3): the primary (non-via_interface) edge
        // is added first and is the one a CallSite (which has room for only one target) should reflect.
        CallSite unresolved = new CallSite(5, "g.greet(\"x\")", "g.greet", null, false);
        CatalogEntry entry = catalogEntry("method-1", List.of(unresolved));

        CallEdge primary = new CallEdge(5, "g.greet(\"x\")", "interface-method-id", null, true, false);
        CallEdge viaInterface = new CallEdge(5, "g.greet(\"x\")", "impl-method-id", null, true, true);
        MethodInfo method = methodInfo("method-1", List.of(primary, viaInterface));

        List<CatalogEntry> enriched = CatalogGraphEnricher.enrich(List.of(entry), List.of(method));

        assertThat(enriched.get(0).control().callsBefore().get(0).targetMethodId()).isEqualTo("interface-method-id");
    }

    private static CatalogEntry catalogEntry(String methodId, List<CallSite> callsBefore) {
        ControlContext control = new ControlContext(List.of(), List.of(), List.of(), callsBefore);
        return catalogEntryWithMethodId(methodId, control);
    }

    private static CatalogEntry catalogEntryWithControl(String methodId, ControlContext control) {
        return catalogEntryWithMethodId(methodId, control);
    }

    private static CatalogEntry catalogEntryWithMethodId(String methodId, ControlContext control) {
        return new CatalogEntry(
            "stmt-1", "logical-1", CODE_UNIT, "module-a", "service-a", "A.java", "file-1", "a.b",
            "a.b.A", "a.b.A", "method", "method()", methodId, false, 10, 10, 5, 5, 15,
            "slf4j", "typed", "log", "a.b.A", "class_literal", Level.INFO, false,
            "\"msg\"", "msg", "literal", null, null, List.of(), 3, 0, false,
            null, control, "snippet", 8, null, "test-analyzer", Instant.parse("2026-09-23T10:00:00Z")
        );
    }

    private static MethodInfo methodInfo(String methodId, List<CallEdge> calls) {
        return new MethodInfo(methodId, CODE_UNIT, "module-a", "service-a", "file-1", "A.java",
            "a.b.A", "a.b.A", "method", "method()", 5, 15, List.of(), false, calls, List.of(), 0);
    }
}
