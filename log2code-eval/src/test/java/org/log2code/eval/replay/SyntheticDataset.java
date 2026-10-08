package org.log2code.eval.replay;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.log2code.core.model.AnalysisRun;
import org.log2code.core.model.CatalogEntry;
import org.log2code.core.model.CodeUnit;
import org.log2code.core.model.CodeVersion;
import org.log2code.core.model.EnclosingBlock;
import org.log2code.core.model.GroundTruth;
import org.log2code.core.model.Label;
import org.log2code.core.model.Level;
import org.log2code.core.model.LogEvent;
import org.log2code.core.model.ModuleInfo;
import org.log2code.eval.truth.CatalogView;
import org.log2code.ingester.catalog.CatalogIndex;

/**
 * A three-statement catalog and three oracle events whose outcome under every ablation variant is worked out by
 * hand (see {@code AblationRunnerTest}).
 *
 * <pre>
 * statement  class       template     line
 * s1         com.acme.A  Saving {}    10   (project, service svc)
 * s2         com.acme.B  Saving {}    20   same template, other class: only the logger tells them apart
 * s3         com.acme.C  {}           30   dynamic: no regex, scores at most 0.35 = min_score
 *
 * event  logger      message     oracle
 * e1     com.acme.A  Saving x    A:10 -> s1
 * e2     com.acme.B  Saving y    B:20 -> s2
 * e3     com.acme.C  whatever    C:30 -> s3
 * </pre>
 */
final class SyntheticDataset {

    static final String SERVICE = "svc";
    static final CodeUnit PROJECT = new CodeUnit(CodeUnit.TYPE_PROJECT, "synthetic", "v1");
    static final CodeVersion CODE = new CodeVersion("synthetic", "v1");

    private SyntheticDataset() {
    }

    static List<CatalogEntry> entries() {
        return List.of(
            entry("s1", "com.acme.A", "Saving {}", "placeholders", 10),
            entry("s2", "com.acme.B", "Saving {}", "placeholders", 20),
            entry("s3", "com.acme.C", "{}", "dynamic", 30));
    }

    static AnalysisRun run() {
        return new AnalysisRun("run-1", CodeUnit.TYPE_PROJECT, PROJECT, "https://example.invalid/synthetic", "test",
            Instant.parse("2026-10-08T10:00:00Z"), Instant.parse("2026-10-08T10:00:05Z"), 1000, Map.of(),
            List.of(new ModuleInfo("synthetic-mod", SERVICE, List.of("src/main/java"), List.of(), List.of())));
    }

    static CatalogIndex index() {
        return CatalogIndex.build(run(), entries(), List.of());
    }

    static CatalogView view() {
        return CatalogView.build(run(), entries());
    }

    static List<LogEvent> events() {
        return List.of(
            event(1, "com.acme.A", "Saving x", new GroundTruth("com.acme.A", "m", 10, true)),
            event(2, "com.acme.B", "Saving y", new GroundTruth("com.acme.B", "m", 20, true)),
            event(3, "com.acme.C", "whatever", new GroundTruth("com.acme.C", "m", 30, true)));
    }

    static ReplayDataset dataset(Map<String, Label> labels) {
        return new ReplayDataset("syn-01", CODE, index(), view(), events(), labels);
    }

    static LogEvent event(int line, String loggerRaw, String message, GroundTruth truth) {
        return new LogEvent("syn-01", "logs/svc.log", line, 1, line, Instant.parse("2026-10-08T10:00:00Z"), null, SERVICE,
            "synthetic-mod", null, null, null, Level.INFO, loggerRaw, null, message, message, null, null, null, CODE, truth,
            "spring-boot-default");
    }

    static CatalogEntry entry(String id, String classFqn, String template, String templateKind, int line) {
        return new CatalogEntry(
            id, id + "-logical", PROJECT, "synthetic-mod", SERVICE, "Fixture.java", "file-" + id, "com.acme", classFqn, classFqn,
            "m", "m()", null, false,
            line, line, 4, line - 5, line + 5,
            "slf4j", "typed", "log", classFqn, "class_literal",
            Level.INFO, false,
            "\"" + template + "\"", template, templateKind, null, null, List.of(), template.length(), 0, false,
            new EnclosingBlock("method", null, null, line - 5, line + 5), null,
            "    log.info(...);\n", line,
            null, "test", Instant.parse("2026-10-08T10:00:00Z"));
    }
}
