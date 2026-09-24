package org.log2code.ingester.parse.logback;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.log2code.ingester.parse.LineParser;
import org.log2code.ingester.parse.LogFormatException;

/**
 * Compiles a Logback conversion pattern (0.11, {@code type: logback-pattern}) into a
 * {@link LineParser}: a matching regex plus, per captured group, which {@link HeaderFields} slot
 * it feeds. T17 step 4 lists the supported conversion words; anything else fails to load with a
 * clear message rather than silently mis-parsing.
 *
 * <p>Supported: {@code %d{fmt}}/{@code %date} (default {@value #DEFAULT_DATE_PATTERN} when no
 * {@code {fmt}} is given), {@code %p}/{@code %le}/{@code %level}, {@code %t}/{@code %thread},
 * {@code %c}/{@code %lo}/{@code %logger{n}}, {@code %C}/{@code %class}, {@code %M}/{@code %method},
 * {@code %L}/{@code %line}, {@code %m}/{@code %msg}/{@code %message}, {@code %X{key}} (matched but
 * not surfaced — {@link HeaderFields} has no generic MDC slot, same for class/method/line),
 * {@code %n} (dropped: a parsed line never contains an embedded newline), {@code %pid} and
 * {@code ${PID}}, {@code %clr(...){...}} (only its content is compiled, recursively), width
 * modifiers ({@code %-5p}, {@code %15.15t}: a fixed {@code min == max} width captures exactly that
 * many characters and trims padding; any other width falls back to a generic per-field pattern),
 * and literal text. A pattern must include a timestamp and a message conversion word.
 */
public final class LogbackPatternCompiler {

    static final String DEFAULT_DATE_PATTERN = "yyyy-MM-dd HH:mm:ss,SSS";

    private static final List<String> KEYWORDS_BY_LENGTH_DESC = List.of(
        "message", "method", "thread", "logger",
        "level", "class",
        "line", "date",
        "pid", "msg",
        "le", "lo",
        "C", "M", "L", "m", "c", "p", "t", "d", "X", "n");

    private static final Map<String, FieldType> KEYWORD_TYPES = Map.ofEntries(
        Map.entry("message", FieldType.MESSAGE),
        Map.entry("method", FieldType.METHOD),
        Map.entry("thread", FieldType.THREAD),
        Map.entry("logger", FieldType.LOGGER),
        Map.entry("level", FieldType.LEVEL),
        Map.entry("class", FieldType.CLASS),
        Map.entry("line", FieldType.LINE),
        Map.entry("date", FieldType.TIMESTAMP),
        Map.entry("pid", FieldType.PID),
        Map.entry("msg", FieldType.MESSAGE),
        Map.entry("le", FieldType.LEVEL),
        Map.entry("lo", FieldType.LOGGER),
        Map.entry("C", FieldType.CLASS),
        Map.entry("M", FieldType.METHOD),
        Map.entry("L", FieldType.LINE),
        Map.entry("m", FieldType.MESSAGE),
        Map.entry("c", FieldType.LOGGER),
        Map.entry("p", FieldType.LEVEL),
        Map.entry("t", FieldType.THREAD),
        Map.entry("d", FieldType.TIMESTAMP),
        Map.entry("X", FieldType.MDC));
    // "n" deliberately excluded from KEYWORD_TYPES: recognized (see NEWLINE_WORD) but produces no field.
    private static final String NEWLINE_WORD = "n";

    private LogbackPatternCompiler() {
    }

    public static LineParser compile(String pattern, ZoneId defaultZone) {
        if (pattern == null || pattern.isBlank()) {
            throw new LogFormatException("logback-pattern format: 'pattern' is required");
        }
        List<Token> tokens = new ArrayList<>();
        tokenizeInto(pattern, tokens);
        return build(pattern, tokens, defaultZone == null ? ZoneId.of("UTC") : defaultZone);
    }

    // ---- tokenizing -------------------------------------------------------------------------

    private sealed interface Token permits Literal, Field {
    }

    private record Literal(String text) implements Token {
    }

    private record Field(FieldType type, int minWidth, int maxWidth, String option) implements Token {
    }

    private static void tokenizeInto(String pattern, List<Token> out) {
        int i = 0;
        int len = pattern.length();
        StringBuilder literal = new StringBuilder();
        while (i < len) {
            char c = pattern.charAt(i);
            if (c == '%') {
                if (i + 1 < len && pattern.charAt(i + 1) == '%') {
                    literal.append('%');
                    i += 2;
                    continue;
                }
                flushLiteral(literal, out);
                i = parseConversion(pattern, i, out);
            } else if (c == '$' && pattern.startsWith("${", i)) {
                flushLiteral(literal, out);
                i = parsePropertyPlaceholder(pattern, i, out);
            } else {
                literal.append(c);
                i++;
            }
        }
        flushLiteral(literal, out);
    }

    private static void flushLiteral(StringBuilder sb, List<Token> out) {
        if (!sb.isEmpty()) {
            out.add(new Literal(sb.toString()));
            sb.setLength(0);
        }
    }

    private static int parsePropertyPlaceholder(String pattern, int i, List<Token> out) {
        int close = pattern.indexOf('}', i + 2);
        if (close < 0) {
            throw new LogFormatException("pattern \"" + pattern + "\": unterminated \"${\" at position " + i);
        }
        String body = pattern.substring(i + 2, close);
        int colon = body.indexOf(':');
        String name = colon >= 0 ? body.substring(0, colon) : body;
        if (!name.equalsIgnoreCase("PID")) {
            throw new LogFormatException(
                "pattern \"" + pattern + "\": unsupported property placeholder \"${" + body + "}\"");
        }
        out.add(new Field(FieldType.PID, -1, -1, null));
        return close + 1;
    }

    private static int parseConversion(String pattern, int i, List<Token> out) {
        int len = pattern.length();
        int p = i + 1;
        if (p < len && pattern.charAt(p) == '-') {
            p++;
        }
        int minWidth = -1;
        int start = p;
        while (p < len && Character.isDigit(pattern.charAt(p))) {
            p++;
        }
        if (p > start) {
            minWidth = Integer.parseInt(pattern.substring(start, p));
        }
        int maxWidth = -1;
        if (p < len && pattern.charAt(p) == '.') {
            p++;
            start = p;
            while (p < len && Character.isDigit(pattern.charAt(p))) {
                p++;
            }
            if (p == start) {
                throw new LogFormatException("pattern \"" + pattern + "\": expected digits after '.' at position " + p);
            }
            maxWidth = Integer.parseInt(pattern.substring(start, p));
        }

        if (pattern.startsWith("clr(", p)) {
            return parseColorWrapper(pattern, p, out);
        }

        String word = matchKeyword(pattern, p);
        if (word == null) {
            int end = Math.min(len, i + 12);
            throw new LogFormatException(
                "pattern \"" + pattern + "\": unsupported conversion word at position " + i
                    + " (\"" + pattern.substring(i, end) + (end < len ? "..." : "") + "\")");
        }
        p += word.length();

        String option = null;
        if (p < len && pattern.charAt(p) == '{') {
            int close = findMatchingBrace(pattern, p);
            option = pattern.substring(p + 1, close);
            p = close + 1;
        }

        if (word.equals(NEWLINE_WORD)) {
            return p; // %n: no HeaderFields slot, lines are already split.
        }
        FieldType type = KEYWORD_TYPES.get(word);
        if (type == FieldType.MDC && option == null) {
            throw new LogFormatException("pattern \"" + pattern + "\": \"%X\" requires an MDC key, e.g. \"%X{traceId}\"");
        }
        out.add(new Field(type, minWidth, maxWidth, option));
        return p;
    }

    /** {@code %clr(inner){colorName}}: keep only {@code inner}, recursively tokenized in place. */
    private static int parseColorWrapper(String pattern, int p, List<Token> out) {
        int parenStart = p + 4; // skip "clr("
        int depth = 1;
        int q = parenStart;
        while (q < pattern.length() && depth > 0) {
            char ch = pattern.charAt(q);
            if (ch == '(') {
                depth++;
            } else if (ch == ')') {
                depth--;
            }
            q++;
        }
        if (depth != 0) {
            throw new LogFormatException("pattern \"" + pattern + "\": unterminated \"%clr(\" at position " + p);
        }
        String inner = pattern.substring(parenStart, q - 1);
        int after = q;
        if (after < pattern.length() && pattern.charAt(after) == '{') {
            int close = pattern.indexOf('}', after + 1);
            if (close < 0) {
                throw new LogFormatException("pattern \"" + pattern + "\": unterminated \"%clr(...){\" at position " + after);
            }
            after = close + 1;
        }
        tokenizeInto(inner, out);
        return after;
    }

    private static String matchKeyword(String pattern, int p) {
        for (String candidate : KEYWORDS_BY_LENGTH_DESC) {
            if (pattern.regionMatches(p, candidate, 0, candidate.length())) {
                return candidate;
            }
        }
        return null;
    }

    private static int findMatchingBrace(String pattern, int openIndex) {
        boolean inQuote = false;
        for (int q = openIndex + 1; q < pattern.length(); q++) {
            char ch = pattern.charAt(q);
            if (ch == '\'') {
                inQuote = !inQuote;
            } else if (ch == '}' && !inQuote) {
                return q;
            }
        }
        throw new LogFormatException("pattern \"" + pattern + "\": unterminated \"{\" at position " + openIndex);
    }

    // ---- building the matcher ----------------------------------------------------------------

    record CapturedField(int group, FieldType type, boolean exactWidth) {
    }

    private static LineParser build(String sourcePattern, List<Token> tokens, ZoneId defaultZone) {
        StringBuilder regex = new StringBuilder("^");
        List<CapturedField> captured = new ArrayList<>();
        DateTimeFormatter dateFormatter = null;
        boolean hasMessage = false;
        int group = 0;
        for (Token t : tokens) {
            if (t instanceof Literal lit) {
                regex.append(Pattern.quote(lit.text()));
                continue;
            }
            Field f = (Field) t;
            boolean exactWidth = f.minWidth() > 0 && f.minWidth() == f.maxWidth();
            String fieldRegex;
            if (f.type() == FieldType.TIMESTAMP) {
                String fmt = f.option() != null ? f.option() : DEFAULT_DATE_PATTERN;
                fieldRegex = DatePatternTranslator.toRegex(fmt);
                dateFormatter = DateTimeFormatter.ofPattern(fmt, Locale.ROOT);
            } else if (exactWidth) {
                fieldRegex = ".{" + f.maxWidth() + "}";
            } else {
                fieldRegex = defaultRegexFor(f.type());
            }
            // A min-only width (e.g. "%-5level") still pads shorter values with spaces, but doesn't
            // bound the field's length the way "min == max" does, so it can't become a fixed-width
            // capture; instead tolerate the padding as optional whitespace outside the group
            // (matching, not generating, so it's fine that we don't know which side it lands on).
            boolean padTolerant = f.minWidth() > 0 && !exactWidth && f.type() != FieldType.TIMESTAMP;
            if (padTolerant) {
                regex.append("\\s*");
            }
            regex.append('(').append(fieldRegex).append(')');
            if (padTolerant) {
                regex.append("\\s*");
            }
            group++;
            captured.add(new CapturedField(group, f.type(), exactWidth));
            hasMessage |= f.type() == FieldType.MESSAGE;
        }
        regex.append('$');

        if (dateFormatter == null) {
            throw new LogFormatException(
                "pattern \"" + sourcePattern + "\": must include a timestamp conversion word (%d or %date)");
        }
        if (!hasMessage) {
            throw new LogFormatException(
                "pattern \"" + sourcePattern + "\": must include a message conversion word (%m, %msg or %message)");
        }
        return new CompiledLineParser(Pattern.compile(regex.toString()), captured, dateFormatter, defaultZone);
    }

    private static String defaultRegexFor(FieldType type) {
        return switch (type) {
            case LEVEL -> "[A-Za-z]+";
            case THREAD, LOGGER, CLASS, METHOD -> ".+?";
            case LINE, PID -> "\\d+";
            case MDC -> "\\S*";
            case MESSAGE -> ".*";
            case TIMESTAMP -> throw new IllegalStateException("TIMESTAMP is handled separately");
        };
    }
}
