package org.log2code.api.llm.explain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.log2code.api.llm.explain.ExplainFixtures.INDEX;

import org.junit.jupiter.api.Test;
import org.log2code.api.web.LogNotFoundException;
import org.log2code.core.model.EnrichedLog;

class ExplainPromptServiceTest {

    private static ExplainPromptService service(ExplainFixtures.Store store, EnrichedLog log) throws Exception {
        var reader = store.reader();
        return new ExplainPromptService(reader, INDEX, new ExplainContextBuilder(reader, INDEX, ExplainFixtures.neighbors(log)),
            new ExplainPromptRenderer());
    }

    @Test
    void buildsTheWholePromptForAnExistingLog() throws Exception {
        EnrichedLog log = ExplainFixtures.stageLog();
        ExplainFixtures.Store store = ExplainFixtures.petLifeStageStore();
        store.put(INDEX.logs(), "log-stage", log);

        ExplainPrompt prompt = service(store, log).build("log-stage", ExplainLevel.L2);

        assertThat(prompt.promptVersion()).isEqualTo(1);
        assertThat(prompt.level()).isEqualTo(ExplainLevel.L2);
        assertThat(prompt.userPrompt()).startsWith("# Log zapis\n").contains("# Uslovi i tok do loga");
        assertThat(prompt.systemPrompt()).startsWith("Ti si iskusan Java i Spring Boot inženjer.");
    }

    @Test
    void theSystemPromptIsTheV1FileWithoutItsFinalLineBreak() throws Exception {
        EnrichedLog log = ExplainFixtures.stageLog();
        ExplainFixtures.Store store = ExplainFixtures.petLifeStageStore();
        store.put(INDEX.logs(), "log-stage", log);

        String system = service(store, log).build("log-stage", ExplainLevel.L0).systemPrompt();

        assertThat(system).endsWith("odgovaraj slobodno, bez obavezne strukture, kratko i na srpskom.");
        assertThat(system).contains("\n   ### Šta se desilo\n   ### Verovatan uzrok\n   ### Šta proveriti i kako popraviti\n");
        assertThat(system.lines().filter(l -> l.matches("^\\d\\. .*"))).hasSize(8);
    }

    @Test
    void anUnknownLogIsNotFound() throws Exception {
        EnrichedLog log = ExplainFixtures.stageLog();

        assertThatThrownBy(() -> service(new ExplainFixtures.Store(), log).build("nope", ExplainLevel.L2))
            .isInstanceOf(LogNotFoundException.class)
            .hasMessage("log not found: nope");
    }
}
