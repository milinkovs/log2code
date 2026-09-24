package org.log2code.ingester.parse;

import java.util.regex.Pattern;

/**
 * Strips ANSI/VT100 escape sequences (e.g. {@code ESC[0;39m}) that {@code %clr(...)} would emit
 * on a real terminal. Docker's log capture never sees a terminal, so production log files don't
 * contain them (0.9), but text-based parsers strip them defensively before matching anyway.
 */
public final class AnsiCodes {

    // CSI sequences (ESC '[' ... final byte in @-~), the only kind Logback's ANSIConstants use.
    private static final Pattern ANSI_CSI = Pattern.compile("\u001B\\[[0-9;]*[a-zA-Z]");

    private AnsiCodes() {
    }

    public static String strip(String line) {
        // The common case (T18 processes every physical line of a multi-hundred-thousand-line
        // file, and production Docker logs never contain ANSI, see class javadoc): skip building
        // a Matcher and scanning with the regex engine when there is no ESC byte to find at all.
        // Matcher.replaceAll() always allocates a new String even with zero matches, so this
        // check is the difference between one indexOf() scan and one indexOf() scan *plus* a full
        // regex pass and a copy, for every single line.
        if (line.indexOf('\u001B') < 0) {
            return line;
        }
        return ANSI_CSI.matcher(line).replaceAll("");
    }
}
