package org.log2code.core.model;

import java.util.List;

/** Level 2 context (T11): control flow around a log statement within its method. */
public record ControlContext(
    List<Condition> conditions,
    List<EarlyExit> earlyExits,
    List<PrecedingStatement> preceding,
    List<CallSite> callsBefore
) {
}
