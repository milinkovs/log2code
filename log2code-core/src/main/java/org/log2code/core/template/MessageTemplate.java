package org.log2code.core.template;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * An immutable log message template: a sequence of {@link Literal} and {@link Hole} parts.
 *
 * <p>A template has a canonical <b>normalized representation</b> (see {@link #toNormalized()}):
 * a hole is written as an opening brace directly followed by a closing brace, a literal opening
 * brace is written as a backslash followed by an opening brace, and a literal backslash is
 * written as two backslashes. No other character is escaped. Adjacent holes collapse into a
 * single hole, and leading/trailing whitespace of the whole template is stripped. {@link #of(List)} and
 * {@link #parse(String)} both apply this normalization, so two templates built from equivalent
 * input are {@link #equals(Object) equal} and produce the same {@link #toNormalized()} string.
 *
 * <p>Instances are immutable and thread-safe: the regular expressions used by {@link #toRegex()},
 * {@link #matchFull(String)} and {@link #matchPrefix(String)} are compiled once, at construction.
 */
public final class MessageTemplate {

    /**
     * Protects matching from pathologically long input: messages longer than this are truncated
     * (from the right) before {@link #matchFull(String)} or {@link #matchPrefix(String)} runs.
     */
    public static final int MAX_MATCH_LENGTH = 4096;

    /** A part of a {@link MessageTemplate}: either a {@link Literal} or a {@link Hole}. */
    public sealed interface Part permits Literal, Hole {
    }

    /** A fixed, literal fragment of a template. {@code text} is never {@code null}, may be empty. */
    public record Literal(String text) implements Part {
        public Literal {
            Objects.requireNonNull(text, "text");
        }
    }

    /** A placeholder ({@code "{}"}) that matches an arbitrary substring of the log message. */
    public record Hole() implements Part {
    }

    private final List<Part> parts;
    private final String normalized;
    private final String regexSource;
    private final Pattern fullPattern;
    private final Pattern prefixPattern;

    private MessageTemplate(List<Part> normalizedParts) {
        this.parts = List.copyOf(normalizedParts);
        this.normalized = buildNormalized(this.parts);
        this.regexSource = buildRegex(this.parts, true);
        this.fullPattern = Pattern.compile(regexSource, Pattern.DOTALL);
        this.prefixPattern = Pattern.compile(buildRegex(this.parts, false), Pattern.DOTALL);
    }

    /**
     * Builds a template from an explicit list of parts, applying the same normalization as
     * {@link #parse(String)} (merging adjacent holes, trimming leading/trailing whitespace,
     * dropping empty literals).
     */
    public static MessageTemplate of(List<Part> parts) {
        Objects.requireNonNull(parts, "parts");
        return new MessageTemplate(normalize(parts));
    }

    /**
     * Parses a string in the normalized representation (see the class javadoc) into a template.
     * The result is itself normalized, so {@code parse(x)} and {@code parse(x).toNormalized()}
     * agree even if {@code x} was not already canonical (e.g. contained adjacent holes).
     */
    public static MessageTemplate parse(String normalized) {
        Objects.requireNonNull(normalized, "normalized");
        return new MessageTemplate(normalize(scan(normalized)));
    }

    /** The parts of this template, in order. Immutable. */
    public List<Part> parts() {
        return parts;
    }

    /** The canonical normalized representation of this template (see the class javadoc). */
    public String toNormalized() {
        return normalized;
    }

    /**
     * The regular expression that matches a message produced by this template in full:
     * {@code ^} + each literal part quoted with {@link Pattern#quote(String)} + each hole as a
     * non-greedy capturing group {@code (.*?)} + {@code $}, compiled with {@link Pattern#DOTALL}
     * so holes can span multiple lines.
     */
    public String toRegex() {
        return regexSource;
    }

    /**
     * Matches {@code message} against this template in its entirety. The message is truncated to
     * {@link #MAX_MATCH_LENGTH} characters (from the right) before matching.
     *
     * @return the hole values, in order, if the whole (truncated) message matches; otherwise
     *     {@link Optional#empty()}
     */
    public Optional<List<String>> matchFull(String message) {
        Objects.requireNonNull(message, "message");
        Matcher matcher = fullPattern.matcher(truncate(message));
        return matcher.matches() ? Optional.of(groupValues(matcher)) : Optional.empty();
    }

    /**
     * Matches this template against the beginning of {@code message}, without requiring the whole
     * message to be consumed. The message is truncated to {@link #MAX_MATCH_LENGTH} characters
     * (from the right) before matching. Because holes are non-greedy, a trailing hole captures no
     * more than necessary to satisfy the parts that follow it.
     *
     * @return the hole values, in order, if this template matches a prefix of the (truncated)
     *     message; otherwise {@link Optional#empty()}
     */
    public Optional<List<String>> matchPrefix(String message) {
        Objects.requireNonNull(message, "message");
        Matcher matcher = prefixPattern.matcher(truncate(message));
        return matcher.lookingAt() ? Optional.of(groupValues(matcher)) : Optional.empty();
    }

    /**
     * Tokenizes every literal part with {@link Tokenizer#tokens(String)} and merges the results,
     * removing duplicates and keeping first-occurrence order across parts.
     */
    public List<String> constantTokens() {
        Set<String> tokens = new LinkedHashSet<>();
        for (Part part : parts) {
            if (part instanceof Literal literal) {
                tokens.addAll(Tokenizer.tokens(literal.text()));
            }
        }
        return List.copyOf(tokens);
    }

    /** The number of non-whitespace characters across all literal parts. */
    public int literalLength() {
        int count = 0;
        for (Part part : parts) {
            if (part instanceof Literal literal) {
                for (int i = 0; i < literal.text().length(); i++) {
                    if (!Character.isWhitespace(literal.text().charAt(i))) {
                        count++;
                    }
                }
            }
        }
        return count;
    }

    /** The number of holes in this template. */
    public int holeCount() {
        int count = 0;
        for (Part part : parts) {
            if (part instanceof Hole) {
                count++;
            }
        }
        return count;
    }

    /** {@code true} if this template has no non-whitespace literal character ({@link #literalLength()} is 0). */
    public boolean isDynamicOnly() {
        return literalLength() == 0;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof MessageTemplate other && normalized.equals(other.normalized);
    }

    @Override
    public int hashCode() {
        return normalized.hashCode();
    }

    @Override
    public String toString() {
        return normalized;
    }

    private static String truncate(String message) {
        return message.length() > MAX_MATCH_LENGTH ? message.substring(0, MAX_MATCH_LENGTH) : message;
    }

    private static List<String> groupValues(Matcher matcher) {
        List<String> values = new ArrayList<>(matcher.groupCount());
        for (int i = 1; i <= matcher.groupCount(); i++) {
            values.add(matcher.group(i));
        }
        return List.copyOf(values);
    }

    private static String buildRegex(List<Part> parts, boolean anchorEnd) {
        StringBuilder regex = new StringBuilder("^");
        for (Part part : parts) {
            if (part instanceof Literal literal) {
                regex.append(Pattern.quote(literal.text()));
            } else {
                regex.append("(.*?)");
            }
        }
        if (anchorEnd) {
            regex.append('$');
        }
        return regex.toString();
    }

    private static String buildNormalized(List<Part> parts) {
        StringBuilder sb = new StringBuilder();
        for (Part part : parts) {
            if (part instanceof Literal literal) {
                sb.append(escape(literal.text()));
            } else {
                sb.append("{}");
            }
        }
        return sb.toString();
    }

    private static String escape(String text) {
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\\' || c == '{') {
                sb.append('\\');
            }
            sb.append(c);
        }
        return sb.toString();
    }

    /**
     * Scans a normalized string into raw parts, interpreting a hole marker, an escaped literal
     * opening brace and an escaped literal backslash (see the class javadoc).
     */
    private static List<Part> scan(String text) {
        List<Part> result = new ArrayList<>();
        StringBuilder literal = new StringBuilder();
        int i = 0;
        int n = text.length();
        while (i < n) {
            char c = text.charAt(i);
            if (c == '\\' && i + 1 < n && (text.charAt(i + 1) == '{' || text.charAt(i + 1) == '\\')) {
                literal.append(text.charAt(i + 1));
                i += 2;
            } else if (c == '{' && i + 1 < n && text.charAt(i + 1) == '}') {
                if (literal.length() > 0) {
                    result.add(new Literal(literal.toString()));
                    literal.setLength(0);
                }
                result.add(new Hole());
                i += 2;
            } else {
                literal.append(c);
                i += 1;
            }
        }
        if (literal.length() > 0) {
            result.add(new Literal(literal.toString()));
        }
        return result;
    }

    /**
     * Canonicalizes a raw part list: drops empty literals, merges adjacent same-type parts
     * (adjacent holes into one; adjacent literals by concatenation), then strips leading
     * whitespace from a leading literal and trailing whitespace from a trailing literal (dropping
     * either if it becomes empty).
     */
    private static List<Part> normalize(List<Part> rawParts) {
        List<Part> withoutEmptyLiterals = new ArrayList<>();
        for (Part part : rawParts) {
            if (part instanceof Literal literal && literal.text().isEmpty()) {
                continue;
            }
            withoutEmptyLiterals.add(part);
        }

        List<Part> merged = new ArrayList<>();
        for (Part part : withoutEmptyLiterals) {
            if (!merged.isEmpty()) {
                Part last = merged.get(merged.size() - 1);
                if (part instanceof Hole && last instanceof Hole) {
                    continue;
                }
                if (part instanceof Literal next && last instanceof Literal prev) {
                    merged.set(merged.size() - 1, new Literal(prev.text() + next.text()));
                    continue;
                }
            }
            merged.add(part);
        }

        if (!merged.isEmpty() && merged.get(0) instanceof Literal first) {
            String trimmed = first.text().stripLeading();
            if (trimmed.isEmpty()) {
                merged.remove(0);
            } else {
                merged.set(0, new Literal(trimmed));
            }
        }
        if (!merged.isEmpty() && merged.get(merged.size() - 1) instanceof Literal last) {
            int idx = merged.size() - 1;
            String trimmed = last.text().stripTrailing();
            if (trimmed.isEmpty()) {
                merged.remove(idx);
            } else {
                merged.set(idx, new Literal(trimmed));
            }
        }
        return merged;
    }
}
