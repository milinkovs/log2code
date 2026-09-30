package org.log2code.api.web;

import org.log2code.api.dto.ExplainRequest;
import org.log2code.api.llm.explain.ExplainStreamService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** {@code POST /api/logs/{logId}/explain}: the model's explanation of a log as an SSE stream (T42). */
@RestController
@RequestMapping("/api/logs/{logId}/explain")
public class ExplainStreamController {

    private final ExplainStreamService streamService;

    public ExplainStreamController(ExplainStreamService streamService) {
        this.streamService = streamService;
    }

    /**
     * Events, in order: {@code meta}, {@code delta}..., {@code done}; on failure {@code error} replaces
     * {@code done}. Problems found before the stream starts are ordinary HTTP errors (404, 400, 503).
     */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter explain(@PathVariable("logId") String logId, @RequestBody ExplainRequest request) {
        return streamService.start(logId, request);
    }
}
