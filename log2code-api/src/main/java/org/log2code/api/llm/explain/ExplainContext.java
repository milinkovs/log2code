package org.log2code.api.llm.explain;

import java.util.ArrayList;
import java.util.List;

/**
 * The parts of the prompt for one log and level (10.1), before they are turned into text. A {@code null}
 * part (or an empty list) means "not in the prompt"; the reason is only kept in {@link #sections()}.
 * Built by {@link ExplainContextBuilder}, rendered (and, if needed, shrunk) by {@link ExplainPromptRenderer}.
 */
public record ExplainContext(
    ExplainLevel level,
    LogPart log,
    String stackTrace,
    StatementPart statement,
    CodeBlock method,
    FlowPart flow,
    List<StackCode> stackCode,
    List<CallerBlock> callers,
    List<NeighborLine> neighbors,
    List<ExplainSection> sections
) {

    /** {@code # Log zapis}. Any {@code null} field is left out of the text. */
    public record LogPart(String service, String timestampRaw, String level, String logger, String thread,
                          String traceId, String message) {
    }

    /** {@code # Mesto u kodu koje je napisalo ovaj log}. {@code codeUnit} and {@code classAndMethod} are already formatted. */
    public record StatementPart(String status, String confidenceLevel, Double confidence, String codeUnit,
                                String filePath, int line, String classAndMethod, String template) {
    }

    /** {@code lines} start at file line {@code firstLine}; {@code cutBefore}/{@code cutAfter} mark a window inside a longer method. */
    public record CodeBlock(int firstLine, List<String> lines, boolean cutBefore, boolean cutAfter) {
    }

    /** {@code # Uslovi i tok do loga}: one entry per bullet, already formatted (an empty list is omitted). */
    public record FlowPart(List<String> inside, List<String> earlyExits, List<String> preceding, List<String> callsBefore) {

        public boolean isEmpty() {
            return inside.isEmpty() && earlyExits.isEmpty() && preceding.isEmpty() && callsBefore.isEmpty();
        }
    }

    /** {@code ### <title>} plus the code around one project frame of the stack trace. */
    public record StackCode(String title, CodeBlock code) {
    }

    /** One caller at BFS level {@code depth} (1 = direct caller). {@code code} is {@code null} when its source is unavailable. */
    public record CallerBlock(int depth, String title, CodeBlock code) {
    }

    /** One line of the neighbor list; {@code current} marks the log being explained. */
    public record NeighborLine(String text, boolean current) {
    }

    int maxCallerDepth() {
        return callers.stream().mapToInt(CallerBlock::depth).max().orElse(0);
    }

    ExplainContext withoutNeighbors() {
        return new ExplainContext(level, log, stackTrace, statement, method, flow, stackCode, callers, List.of(),
            replaceSection(ExplainSection.omitted(ExplainSection.NEIGHBORS, ExplainSection.REASON_TRUNCATED)));
    }

    /** Drops the deepest level of callers; the section stays included (with reason {@code truncated}) while a shallower level remains. */
    ExplainContext withoutDeepestCallers() {
        int deepest = maxCallerDepth();
        List<CallerBlock> kept = callers.stream().filter(c -> c.depth() < deepest).toList();
        ExplainSection section = kept.isEmpty()
            ? ExplainSection.omitted(ExplainSection.CALLERS, ExplainSection.REASON_TRUNCATED)
            : new ExplainSection(ExplainSection.CALLERS, true, ExplainSection.REASON_TRUNCATED);
        return new ExplainContext(level, log, stackTrace, statement, method, flow, stackCode, kept, neighbors,
            replaceSection(section));
    }

    private List<ExplainSection> replaceSection(ExplainSection replacement) {
        List<ExplainSection> copy = new ArrayList<>(sections);
        copy.replaceAll(s -> s.id().equals(replacement.id()) ? replacement : s);
        return List.copyOf(copy);
    }
}
