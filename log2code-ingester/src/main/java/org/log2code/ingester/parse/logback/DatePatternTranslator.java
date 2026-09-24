package org.log2code.ingester.parse.logback;

import org.log2code.ingester.parse.LogFormatException;

/**
 * Translates a {@code java.time.format.DateTimeFormatter} pattern (the same syntax {@code %d{...}}
 * takes, per Logback/SLF4J) into a regex fragment that matches whatever that pattern would print.
 * Values are still parsed with the real {@link java.time.format.DateTimeFormatter}
 * ({@code DateTimeFormatter.ofPattern}, in {@link LogbackPatternCompiler}) — this class only needs
 * to bound each field tightly enough for matching, not reproduce formatting semantics.
 *
 * <p>Covers the letters realistic timestamp patterns use (year, month, day, hour, minute, second,
 * fraction, am/pm, day name, zone offset/name) plus quoted literals (Logback/SimpleDateFormat
 * {@code 'literal'} and {@code ''} for a literal quote). An unrecognized pattern letter is a config
 * error (0.13): it is either a typo or a symbol this translator does not yet support.
 */
final class DatePatternTranslator {

    private DatePatternTranslator() {
    }

    static String toRegex(String fmt) {
        StringBuilder regex = new StringBuilder();
        int i = 0;
        int len = fmt.length();
        while (i < len) {
            char c = fmt.charAt(i);
            if (c == '\'') {
                int end = fmt.indexOf('\'', i + 1);
                if (end == i + 1) {
                    // '' => literal quote
                    regex.append(java.util.regex.Pattern.quote("'"));
                    i += 2;
                } else if (end < 0) {
                    throw new LogFormatException("date pattern \"" + fmt + "\": unterminated quoted literal");
                } else {
                    regex.append(java.util.regex.Pattern.quote(fmt.substring(i + 1, end)));
                    i = end + 1;
                }
            } else if (Character.isLetter(c)) {
                int start = i;
                while (i < len && fmt.charAt(i) == c) {
                    i++;
                }
                regex.append(regexForLetter(c, i - start, fmt));
            } else {
                int start = i;
                while (i < len && !Character.isLetter(fmt.charAt(i)) && fmt.charAt(i) != '\'') {
                    i++;
                }
                regex.append(java.util.regex.Pattern.quote(fmt.substring(start, i)));
            }
        }
        return regex.toString();
    }

    private static String regexForLetter(char letter, int count, String fmt) {
        return switch (letter) {
            case 'y', 'u' -> count >= 3 ? "\\d{" + count + "}" : "\\d{1,4}";
            case 'M', 'L' -> count <= 2 ? "\\d{1,2}" : "[A-Za-z]+";
            case 'd' -> "\\d{1,2}";
            case 'H', 'h', 'k', 'K' -> "\\d{1,2}";
            case 'm' -> "\\d{1,2}";
            case 's' -> "\\d{1,2}";
            case 'S' -> "\\d{" + count + "}";
            case 'a' -> "[AaPp][Mm]";
            case 'E', 'e', 'c' -> "[A-Za-z]+";
            case 'z' -> "[A-Za-z0-9+:/ ]+";
            case 'Z' -> "[+-]\\d{4}";
            case 'X', 'x', 'O', 'V' -> "(?:Z|[A-Za-z0-9+:/_-]+)";
            default -> throw new LogFormatException(
                "date pattern \"" + fmt + "\": unsupported letter '" + letter + "'");
        };
    }
}
