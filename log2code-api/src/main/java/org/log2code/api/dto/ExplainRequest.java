package org.log2code.api.dto;

import java.util.List;

/**
 * Body of {@code POST /api/logs/{logId}/explain} (T42). {@code turns} is the whole conversation after the first
 * (context) message, alternating {@code model} / {@code user}, starting with {@code model} and ending with
 * {@code user}; it is empty for the first explanation. The server keeps no conversation state.
 */
public record ExplainRequest(String level, String model, List<Turn> turns) {

    /** One conversation turn; {@code role} is {@code "model"} or {@code "user"}. */
    public record Turn(String role, String text) {
    }
}
