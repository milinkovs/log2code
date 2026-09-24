package org.log2code.ingester.assemble;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.log2code.core.model.CausedBy;
import org.log2code.core.model.ExceptionInfo;
import org.log2code.core.model.StackFrame;

/**
 * Parses the lines of a log event that make up a Java stack trace (T18 step 3): an optional
 * leading {@code class[: message]} line (absent when the event's message already carries the
 * description and the frames follow directly, see {@code docs/log-format.md} §4), {@code at ...}
 * frames, {@code Caused by:} chains and {@code ... N more} / {@code N common frames omitted}
 * elisions.
 *
 * <p>Reactor "checkpoint" blocks ({@code Suppressed:}, "Error has been observed...", "Original
 * Stack Trace:" and its re-listed frames) are deliberately not parsed into {@link StackFrame}s
 * (T18 step 3 bullet 3): {@link #parse(List)} simply stops at the first line that is neither a
 * frame nor a {@code Caused by:}, and everything from there on stays only in {@code LogEvent.raw}.
 */
final class StackTraceParser {

    /** The exact pattern T18 step 2 specifies for a stand-alone exception class line. */
    static final Pattern CLASS_LINE = Pattern.compile("^([\\w$]+\\.)+[\\w$]+(: .*)?$");

    private static final Pattern FRAME_LINE = Pattern.compile(
        "^\\s*at\\s+(?:[\\w.]+/)?([\\w.$]+)\\(([^)]*)\\)(?:\\s+~?\\[[^\\]]*\\])?\\s*$");
    private static final Pattern MORE_LINE = Pattern.compile("^\\s*\\.\\.\\.\\s+\\d+\\s+(?:more|common frames omitted)\\s*$");
    private static final Pattern CAUSED_BY = Pattern.compile("^Caused by:\\s?(.*)$");

    private StackTraceParser() {
    }

    static boolean isFrameLine(String line) {
        return FRAME_LINE.matcher(line).matches();
    }

    static ExceptionInfo parse(List<String> lines) {
        int i = 0;
        String className = null;
        String message = null;
        if (i < lines.size() && !isFrameLine(lines.get(i)) && CLASS_LINE.matcher(lines.get(i)).matches()) {
            String[] parts = splitClassMessage(lines.get(i));
            className = parts[0];
            message = parts[1];
            i++;
        }

        int[] cursor = {i};
        List<StackFrame> frames = consumeFrames(lines, cursor);

        List<CausedBy> causedBy = new ArrayList<>();
        while (cursor[0] < lines.size()) {
            Matcher m = CAUSED_BY.matcher(lines.get(cursor[0]));
            if (!m.matches()) {
                break;
            }
            String[] parts = splitClassMessage(m.group(1));
            cursor[0]++;
            List<StackFrame> causeFrames = consumeFrames(lines, cursor);
            causedBy.add(new CausedBy(parts[0], parts[1], causeFrames));
        }

        String rootClass = causedBy.isEmpty() ? className : causedBy.get(causedBy.size() - 1).className();
        return new ExceptionInfo(className, rootClass, message, frames, causedBy);
    }

    private static List<StackFrame> consumeFrames(List<String> lines, int[] cursor) {
        List<StackFrame> frames = new ArrayList<>();
        while (cursor[0] < lines.size() && isFrameLine(lines.get(cursor[0]))) {
            frames.add(parseFrame(lines.get(cursor[0])));
            cursor[0]++;
        }
        if (cursor[0] < lines.size() && MORE_LINE.matcher(lines.get(cursor[0])).matches()) {
            cursor[0]++;
        }
        return frames;
    }

    private static StackFrame parseFrame(String line) {
        Matcher m = FRAME_LINE.matcher(line);
        if (!m.matches()) {
            throw new IllegalStateException("not a frame line: " + line);
        }
        String classMethod = m.group(1);
        String location = m.group(2);
        int lastDot = classMethod.lastIndexOf('.');
        String className = lastDot < 0 ? null : classMethod.substring(0, lastDot);
        String method = lastDot < 0 ? classMethod : classMethod.substring(lastDot + 1);

        String file = null;
        Integer lineNumber = null;
        if (!location.equals("Native Method") && !location.equals("Unknown Source")) {
            int colon = location.lastIndexOf(':');
            if (colon < 0) {
                file = location.isEmpty() ? null : location;
            } else {
                file = location.substring(0, colon);
                try {
                    lineNumber = Integer.parseInt(location.substring(colon + 1));
                } catch (NumberFormatException e) {
                    lineNumber = null;
                }
            }
        }
        return new StackFrame(className, method, file, lineNumber, false, null, null, null);
    }

    private static String[] splitClassMessage(String text) {
        int idx = text.indexOf(": ");
        if (idx < 0) {
            String trimmed = text.trim();
            return new String[] {trimmed.isEmpty() ? null : trimmed, null};
        }
        String cls = text.substring(0, idx).trim();
        String msg = text.substring(idx + 2);
        return new String[] {cls.isEmpty() ? null : cls, msg.isEmpty() ? null : msg};
    }
}
