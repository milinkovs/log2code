package org.log2code.api.dto;

import java.util.List;

/** Level 2 control-flow context (mirrors {@link org.log2code.core.model.ControlContext}). */
public record ControlContextDto(
    List<ConditionDto> conditions,
    List<EarlyExitDto> earlyExits,
    List<PrecedingStatementDto> preceding,
    List<CallSiteDto> callsBefore
) {
}
