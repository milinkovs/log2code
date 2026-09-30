package org.log2code.api.llm.explain;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.log2code.api.llm.explain.ExplainContext.CallerBlock;
import org.log2code.api.llm.explain.ExplainContext.CodeBlock;
import org.log2code.api.llm.explain.ExplainContext.FlowPart;
import org.log2code.api.llm.explain.ExplainContext.LogPart;
import org.log2code.api.llm.explain.ExplainContext.NeighborLine;
import org.log2code.api.llm.explain.ExplainContext.StackCode;
import org.log2code.api.llm.explain.ExplainContext.StatementPart;
import org.log2code.api.service.SourceText;

/**
 * Turns an {@link ExplainContext} into the user prompt (template in T41 step 4) and keeps it within
 * {@value #MAX_USER_PROMPT_CHARS} characters: when it is longer, the neighbor logs are dropped first, then the
 * deepest level of callers, then the next one.
 */
public final class ExplainPromptRenderer {

    public static final int MAX_USER_PROMPT_CHARS = 60_000;

    static final String CUT_MARKER = "// … (skraćeno)";
    static final String CLOSING_LINE = "Objasni ovaj log zapis prema uputstvu.";

    public ExplainPrompt render(ExplainContext context, String systemPrompt) {
        ExplainContext current = context;
        String text = userPrompt(current);
        if (text.length() > MAX_USER_PROMPT_CHARS && !current.neighbors().isEmpty()) {
            current = current.withoutNeighbors();
            text = userPrompt(current);
        }
        while (text.length() > MAX_USER_PROMPT_CHARS && current.maxCallerDepth() > 0) {
            current = current.withoutDeepestCallers();
            text = userPrompt(current);
        }
        return new ExplainPrompt(ExplainPrompt.PROMPT_VERSION, current.level(), systemPrompt, text, text.length(), current.sections());
    }

    static String userPrompt(ExplainContext context) {
        List<String> blocks = new ArrayList<>();
        blocks.add(logBlock(context.log(), context.stackTrace()));
        if (context.statement() != null) {
            blocks.add(statementBlock(context.statement(), context.method()));
        }
        if (context.flow() != null) {
            blocks.add(flowBlock(context.flow()));
        }
        if (!context.stackCode().isEmpty()) {
            blocks.add(stackCodeBlock(context.stackCode()));
        }
        if (!context.callers().isEmpty()) {
            blocks.add(callersBlock(context.callers()));
        }
        if (!context.neighbors().isEmpty()) {
            blocks.add(neighborsBlock(context.neighbors()));
        }
        blocks.add(ExplainPromptRenderer.CLOSING_LINE);
        return String.join("\n\n", blocks);
    }

    private static String logBlock(LogPart log, String stackTrace) {
        List<String> lines = new ArrayList<>();
        lines.add("# Log zapis");
        addField(lines, "Servis", log.service());
        addField(lines, "Vreme", log.timestampRaw());
        addField(lines, "Nivo", log.level());
        addField(lines, "Logger", log.logger());
        addField(lines, "Nit", log.thread());
        addField(lines, "Trace ID", log.traceId());
        addField(lines, "Poruka", log.message());
        String block = String.join("\n", lines);
        if (stackTrace != null) {
            block += "\n\n## Izuzetak\n" + fenced("text", stackTrace);
        }
        return block;
    }

    private static void addField(List<String> lines, String label, String value) {
        if (value != null) {
            lines.add("- " + label + ": " + value);
        }
    }

    private static String statementBlock(StatementPart statement, CodeBlock method) {
        List<String> lines = new ArrayList<>();
        lines.add("# Mesto u kodu koje je napisalo ovaj log");
        String confidence = statement.confidence() == null ? "" : String.format(Locale.ROOT, " (%.2f)", statement.confidence());
        lines.add("- Povezivanje: " + statement.status() + ", pouzdanost " + statement.confidenceLevel() + confidence);
        addField(lines, "Kod", statement.codeUnit());
        lines.add("- Fajl: " + statement.filePath() + ", linija " + statement.line());
        lines.add("- Klasa i metoda: " + statement.classAndMethod());
        addField(lines, "Šablon poruke", statement.template());
        String block = String.join("\n", lines);
        if (method != null) {
            block += "\n\n## Metoda u kojoj je log\n" + fenced("java", numbered(method));
        }
        return block;
    }

    private static String flowBlock(FlowPart flow) {
        List<String> lines = new ArrayList<>();
        lines.add("# Uslovi i tok do loga");
        flow.inside().forEach(text -> lines.add("- Unutar: " + text));
        flow.earlyExits().forEach(text -> lines.add("- Stiže se samo ako NIJE: " + text));
        addList(lines, "Prethodne naredbe (od najbliže)", flow.preceding());
        addList(lines, "Pozivi pre loga", flow.callsBefore());
        return String.join("\n", lines);
    }

    private static void addList(List<String> lines, String label, List<String> items) {
        if (!items.isEmpty()) {
            lines.add("- " + label + ":");
            items.forEach(item -> lines.add("  - " + item));
        }
    }

    private static String stackCodeBlock(List<StackCode> frames) {
        List<String> parts = new ArrayList<>();
        parts.add("# Kod iz stack trace-a");
        for (StackCode frame : frames) {
            parts.add("### " + frame.title() + "\n" + fenced("java", numbered(frame.code())));
        }
        return String.join("\n\n", parts);
    }

    private static String callersBlock(List<CallerBlock> callers) {
        List<String> parts = new ArrayList<>();
        parts.add("# Pozivaoci");
        for (CallerBlock caller : callers) {
            String heading = "### Nivo " + caller.depth() + ": " + caller.title();
            parts.add(caller.code() == null ? heading : heading + "\n" + fenced("java", numbered(caller.code())));
        }
        return String.join("\n\n", parts);
    }

    private static String neighborsBlock(List<NeighborLine> neighbors) {
        List<String> lines = new ArrayList<>();
        for (NeighborLine line : neighbors) {
            lines.add(line.current() ? "▶ " + line.text() : line.text());
        }
        return "# Susedni logovi istog servisa\n" + fenced("text", String.join("\n", lines));
    }

    /** The lines of a block with their file line numbers, and {@link #CUT_MARKER} where the method continues. */
    static String numbered(CodeBlock block) {
        String code = SourceText.withLineNumbers(String.join("\n", block.lines()), block.firstLine());
        StringBuilder out = new StringBuilder();
        if (block.cutBefore()) {
            out.append(CUT_MARKER).append('\n');
        }
        out.append(code);
        if (block.cutAfter()) {
            out.append('\n').append(CUT_MARKER);
        }
        return out.toString();
    }

    private static String fenced(String language, String body) {
        return "```" + language + "\n" + body + "\n```";
    }
}
