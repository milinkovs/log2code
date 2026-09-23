package org.log2code.analyzer.template;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.log2code.core.template.MessageTemplate.Hole;
import org.log2code.core.template.MessageTemplate.Literal;
import org.log2code.core.template.MessageTemplate.Part;

/**
 * Scans {@code java.text.MessageFormat} pattern syntax (T09 rule 5: JUL {@code log(Level, msg, param(s))},
 * JBoss {@code *v}): a placeholder ({@code {0}}, {@code {1,number}}, {@code {2,date,short}}...) becomes
 * a hole; {@code ''} becomes a literal single quote.
 */
final class MessageFormatScanner {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\d+(,[^{}]*)?}");

    private MessageFormatScanner() {
    }

    static List<Part> scan(String text) {
        List<Part> parts = new ArrayList<>();
        StringBuilder literal = new StringBuilder();
        Matcher matcher = PLACEHOLDER.matcher(text);
        int i = 0;
        int n = text.length();
        while (i < n) {
            char c = text.charAt(i);
            if (c == '\'' && i + 1 < n && text.charAt(i + 1) == '\'') {
                literal.append('\'');
                i += 2;
                continue;
            }
            if (c == '{') {
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
