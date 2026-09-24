package org.log2code.ingester.parse;

import java.util.Optional;

/**
 * Recognizes and parses the line that starts a log event ("header"), for one log format
 * (0.9, 0.11). Implementations must not throw on a line that is not a header: they return
 * {@link Optional#empty()} instead, so that {@code EventAssembler} (T18) can treat it as a
 * continuation line (stack trace, multi-line message, ...).
 */
public interface LineParser {

    Optional<HeaderFields> parseHeader(String line);
}
