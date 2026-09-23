package org.log2code.analyzer.template;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.log2code.core.template.MessageTemplate.Hole;
import org.log2code.core.template.MessageTemplate.Literal;
import org.log2code.core.template.MessageTemplate.Part;

/**
 * Scans {@code java.util.Formatter} conversion syntax (T09 rule 4: {@code String.format}/
 * {@code .formatted}/{@code LogMessage.format}, JBoss {@code *f}): a {@code %} conversion
 * ({@code %s}, {@code %d}, {@code %5.2f}, {@code %x}, {@code %b}, {@code %c}, {@code %e},
 * {@code %tY}, {@code %1$s}...) becomes a hole; {@code %%} becomes a literal {@code %}; {@code %n}
 * becomes a literal newline.
 */
final class FormatScanner {

    /** {@code %[argument_index$][flags][width][.precision]conversion}; a {@code t}/{@code T} conversion takes a second letter. */
    private static final Pattern SPECIFIER =
        Pattern.compile("%(\\d+\\$)?([-#+ 0,(]*)(\\d+)?(\\.\\d+)?([tT][a-zA-Z]|[a-zA-Z])");

    private FormatScanner() {
    }

    static List<Part> scan(String text) {
        List<Part> parts = new ArrayList<>();
        StringBuilder literal = new StringBuilder();
        Matcher matcher = SPECIFIER.matcher(text);
        int i = 0;
        int n = text.length();
        while (i < n) {
            char c = text.charAt(i);
            if (c == '%' && i + 1 < n) {
                char next = text.charAt(i + 1);
                if (next == '%') {
                    literal.append('%');
                    i += 2;
                    continue;
                }
                if (next == 'n') {
                    literal.append('\n');
                    i += 2;
                    continue;
                }
                matcher.region(i, n);
                if (matcher.lookingAt()) {
                    flush(parts, literal);
                    parts.add(new Hole());
                    i = matcher.end();
                    continue;
                }
            }
            literal.append(c);
            i++;
        }
        flush(parts, literal);
        return parts;
    }

    private static void flush(List<Part> parts, StringBuilder literal) {
        if (literal.length() > 0) {
            parts.add(new Literal(literal.toString()));
            literal.setLength(0);
        }
    }
}
