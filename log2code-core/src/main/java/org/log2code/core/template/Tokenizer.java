package org.log2code.core.template;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Splits text into lowercase word tokens, used both for the literal parts of a
 * {@link MessageTemplate} (its {@link MessageTemplate#constantTokens()}) and for log messages
 * being matched against candidate templates (0.10, Korak 2: {@code topK_tokens}).
 *
 * <p>Rules: the text is lower-cased, then split on runs of characters that are neither a Unicode
 * letter ({@code \p{L}}) nor a Unicode digit ({@code \p{N}}). A resulting token is kept only if
 * its length is at least 2 and it does not consist entirely of digits. Duplicate tokens are
 * removed, keeping the first occurrence's position (insertion order).
 */
public final class Tokenizer {

    private static final Pattern NON_WORD = Pattern.compile("[^\\p{L}\\p{N}]+");
    private static final int MIN_TOKEN_LENGTH = 2;

    private Tokenizer() {
    }

    /**
     * Tokenizes the given text per the rules described on the class.
     *
     * @param text arbitrary text, possibly {@code null} or empty
     * @return tokens in first-occurrence order, without duplicates; never {@code null}
     */
    public static List<String> tokens(String text) {
        if (text == null || text.isEmpty()) {
            return List.of();
        }
        String lower = text.toLowerCase(Locale.ROOT);
        Set<String> result = new LinkedHashSet<>();
        for (String candidate : NON_WORD.split(lower)) {
            if (candidate.length() >= MIN_TOKEN_LENGTH && !isPurelyNumeric(candidate)) {
                result.add(candidate);
            }
        }
        return List.copyOf(result);
    }

    private static boolean isPurelyNumeric(String token) {
        for (int i = 0; i < token.length(); i++) {
            if (!Character.isDigit(token.charAt(i))) {
                return false;
            }
        }
        return true;
    }
}
