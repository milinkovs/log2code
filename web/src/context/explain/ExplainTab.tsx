import { Sparkles } from 'lucide-react';
import { useId, useRef, useState } from 'react';
import { ApiError } from '../../api/client';
import { useLlmModels } from '../../api/queries';
import { Badge, Button, Callout, EmptyState, Spinner } from '../../components/ui';
import { ExplainAnswer } from './ExplainAnswer';
import { ExplainControls } from './ExplainControls';
import { ExplainFollowUp } from './ExplainFollowUp';
import { useExplainLevel, useExplainModel } from './explainPrefs';
import { failureText } from './failures';
import { PromptDialog } from './PromptDialog';
import { includedSections, reasonLabel, sectionLabel } from './sections';
import { useExplainSession } from './useExplainSession';

const NOT_CONFIGURED = failureText({ kind: 'not_configured', message: '' });

/**
 * The "Explain" tab (T43, D11): asks Gemini, through log2code-api, what the selected log means.
 * Works for every log (matched or not, project or library). Nothing is sent until "Explain" is
 * clicked. Mount it with `key={logId}`: another log starts from an empty conversation.
 */
export function ExplainTab({ logId }: { logId: string }) {
  const models = useLlmModels();
  const [level, setLevel] = useExplainLevel();
  const [storedModel, setModel] = useExplainModel();
  const session = useExplainSession(logId);
  const [promptOpen, setPromptOpen] = useState(false);
  const calloutId = useId();
  const showPromptRef = useRef<HTMLButtonElement>(null);

  if (models.isPending) return <Spinner label="Loading models…" />;
  if (models.isError) {
    return (
      <Callout tone="danger">
        {models.error instanceof ApiError
          ? `Could not load the models: ${models.error.message}`
          : "Couldn't reach log2code-api."}
        <Button size="sm" className="callout__action" onClick={() => void models.refetch()}>
          Try again
        </Button>
      </Callout>
    );
  }

  const { configured, defaultModel, models: list } = models.data;
  const model = list.some((m) => m.id === storedModel) ? (storedModel as string) : defaultModel;
  // A 503 `llm_not_configured` from the explain request itself shows the same text in the answer.
  const notConfigured = !configured;
  const streaming = session.status === 'streaming';
  const sections = session.meta ? includedSections(session.meta.sections) : [];

  return (
    <div className="explain">
      <ExplainControls
        level={level}
        onLevelChange={setLevel}
        models={list}
        model={model}
        onModelChange={setModel}
        streaming={streaming}
        disabledReasonId={notConfigured ? calloutId : undefined}
        onExplain={() => session.explain(level, model)}
        onStop={session.stop}
        onShowPrompt={() => setPromptOpen(true)}
        showPromptRef={showPromptRef}
      />

      {notConfigured && (
        <div id={calloutId}>
          <Callout tone="info">{NOT_CONFIGURED}</Callout>
        </div>
      )}

      {sections.length > 0 && (
        <p className="explain__sections">
          <span className="explain__sections-label">Included:</span>
          {sections.map((section) => (
            <Badge key={section.id} tone="accent">
              {sectionLabel(section.id)}
              {section.reason && (
                <span className="explain__section-note"> · {reasonLabel(section.reason)}</span>
              )}
            </Badge>
          ))}
        </p>
      )}

      {session.status === 'idle' ? (
        !notConfigured && (
          <EmptyState
            icon={Sparkles}
            title="Explain this log"
            description="Pick how much context to send and click Explain. Nothing is sent to Gemini before that."
          />
        )
      ) : (
        <>
          <div className="explain__conversation" aria-label="Explanation">
            {session.messages.map((message, i) =>
              message.role === 'user' ? (
                <p key={i} className="explain-question">
                  {message.text}
                </p>
              ) : (
                <ExplainAnswer
                  key={i}
                  answer={message}
                  model={session.model ?? model}
                  level={session.level ?? level}
                  onRetry={i === session.messages.length - 1 ? session.retry : undefined}
                />
              ),
            )}
          </div>
          <ExplainFollowUp
            asked={session.followUps}
            disabled={session.status === 'streaming' || session.status === 'error'}
            onAsk={session.ask}
          />
        </>
      )}

      <PromptDialog
        logId={logId}
        level={session.level ?? level}
        open={promptOpen}
        onOpenChange={setPromptOpen}
        returnFocusRef={showPromptRef}
      />
    </div>
  );
}
