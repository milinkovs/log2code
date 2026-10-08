package org.log2code.eval.report;

import java.util.List;
import java.util.Locale;

/** Small Markdown formatting helpers shared by the T34 reports (the T33 report keeps its own private ones). */
final class Md {

    private Md() {
    }

    static void table(StringBuilder out, List<String> header, List<List<String>> rows) {
        out.append("| ").append(String.join(" | ", header)).append(" |\n");
        out.append("|").append("---|".repeat(header.size())).append("\n");
        for (List<String> row : rows) {
            out.append("| ").append(String.join(" | ", row)).append(" |\n");
        }
        out.append("\n");
    }

    /** {@code 64.8%}, or an em dash for a ratio without a denominator. */
    static String pct(Double value) {
        return value == null ? "—" : String.format(Locale.ROOT, "%.1f%%", value * 100);
    }

    /** {@code 64.83%}: for ratios where one decimal would hide a difference. */
    static String pct2(Double value) {
        return value == null ? "—" : String.format(Locale.ROOT, "%.2f%%", value * 100);
    }

    /** Difference of two ratios in percentage points, signed: {@code +1.2 pp}. */
    static String pp(Double from, Double to) {
        if (from == null || to == null) {
            return "—";
        }
        return String.format(Locale.ROOT, "%+.2f pp", (to - from) * 100);
    }

    static String delta(int from, int to) {
        return String.format(Locale.ROOT, "%+d", to - from);
    }

    /** {@code 2203 / 3397}. */
    static String frac(int numerator, int denominator) {
        return numerator + " / " + denominator;
    }

    /** A value with at most 4 decimals and no trailing zeros: {@code 0.45}, {@code 15}, {@code -0.025}. */
    static String number(double value) {
        String text = String.format(Locale.ROOT, "%.4f", value);
        text = text.contains(".") ? text.replaceAll("0+$", "").replaceAll("\\.$", "") : text;
        return text.equals("-0") ? "0" : text;
    }

    static String code(String text) {
        return "`" + text + "`";
    }
}
