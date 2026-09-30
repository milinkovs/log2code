package org.log2code.api.service;

import java.util.List;

/**
 * Line-oriented helpers over the text of a source file, shared by {@link ContextBundleService}
 * ({@code /context}) and the LLM prompt builder ({@code org.log2code.api.llm.explain}, T41). All line
 * numbers are 1-based and inclusive.
 */
public final class SourceText {

    /** Minimum width of the line-number column in {@link #withLineNumbers}. */
    static final int MIN_NUMBER_WIDTH = 4;

    private SourceText() {
    }

    /** Lines {@code startLine..endLine} (clamped to the file), joined with {@code \n}; {@code null} when the range is empty or invalid. */
    public static String extractRange(String content, int startLine, int endLine) {
        if (content == null || startLine <= 0 || endLine < startLine) {
            return null;
        }
        List<String> lines = content.lines().toList();
        int from = Math.max(1, startLine);
        int to = Math.min(lines.size(), endLine);
        if (from > to) {
            return null;
        }
        return String.join("\n", lines.subList(from - 1, to));
    }

    /** {@code radius} lines either side of {@code line} (clamped to the file); {@code null} when {@code line} is not in the file. */
    public static String extractSnippet(String content, int line, int radius) {
        if (content == null || line <= 0) {
            return null;
        }
        List<String> lines = content.lines().toList();
        if (line > lines.size()) {
            return null;
        }
        int from = Math.max(1, line - radius);
        int to = Math.min(lines.size(), line + radius);
        return String.join("\n", lines.subList(from - 1, to));
    }

    /**
     * Prefixes every line of {@code code} with its number, right-aligned: {@code "  87 | code"}. The first
     * line of {@code code} is line {@code firstLine} of the file. The column is at least
     * {@value #MIN_NUMBER_WIDTH} wide, and wider only when the last number needs more digits.
     */
    public static String withLineNumbers(String code, int firstLine) {
        if (code == null) {
            return null;
        }
        List<String> lines = code.lines().toList();
        int width = Math.max(MIN_NUMBER_WIDTH, String.valueOf(firstLine + Math.max(0, lines.size() - 1)).length());
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) {
                out.append('\n');
            }
            String number = String.valueOf(firstLine + i);
            out.append(" ".repeat(width - number.length())).append(number).append(" | ").append(lines.get(i));
        }
        return out.toString();
    }
}
