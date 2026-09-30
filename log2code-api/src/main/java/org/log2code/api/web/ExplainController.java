package org.log2code.api.web;

import org.log2code.api.dto.ExplainPromptDto;
import org.log2code.api.dto.ExplainSectionDto;
import org.log2code.api.llm.explain.ExplainLevel;
import org.log2code.api.llm.explain.ExplainPrompt;
import org.log2code.api.llm.explain.ExplainPromptService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** {@code /api/logs/{logId}/explain}: the LLM prompt for a log (T41). */
@RestController
@RequestMapping("/api/logs/{logId}/explain")
public class ExplainController {

    private final ExplainPromptService promptService;

    public ExplainController(ExplainPromptService promptService) {
        this.promptService = promptService;
    }

    /** The exact system and user prompt for {@code level} (default {@code L2}); calls no model. */
    @GetMapping("/prompt")
    public ExplainPromptDto prompt(
        @PathVariable("logId") String logId,
        @RequestParam(name = "level", defaultValue = "L2") String level
    ) {
        return toDto(promptService.build(logId, ExplainLevel.parse(level)));
    }

    static ExplainPromptDto toDto(ExplainPrompt prompt) {
        return new ExplainPromptDto(prompt.promptVersion(), prompt.level().name(), prompt.systemPrompt(),
            prompt.userPrompt(), prompt.promptChars(),
            prompt.sections().stream().map(s -> new ExplainSectionDto(s.id(), s.included(), s.reason())).toList());
    }
}
