import { RotateCw } from 'lucide-react';
import { memo } from 'react';
import Markdown, { type Components } from 'react-markdown';
import type { ExplainLevel } from '../../api/types';
import { Button, Callout, Spinner } from '../../components/ui';
import { failureText } from './failures';
import { canRetry, type ExplainAnswer as Answer } from './useExplainSession';

// Links in an answer leave the app; raw HTML is not rendered (no rehype-raw).
const MARKDOWN_COMPONENTS: Components = {
  a: ({ href, title, children }) => (
    <a href={href} title={title} target="_blank" rel="noopener noreferrer">
      {children}
    </a>
  ),
};

const formatNumber = (n: number | null) => (n === null ? '?' : n.toLocaleString('en-US'));

interface ExplainAnswerProps {
  answer: Answer;
  model: string;
  level: ExplainLevel;
  /** Only the last answer offers "Retry" (it repeats the request that produced it). */
  onRetry?: () => void;
}

/**
 * One answer of the model: markdown, a blinking cursor while it streams, then its numbers. Memoized:
 * a delta changes only the last answer, so earlier ones are not parsed again on every delta.
 */
export const ExplainAnswer = memo(function ExplainAnswer({
  answer,
  model,
  level,
  onRetry,
}: ExplainAnswerProps) {
  const busy = answer.state === 'waiting' || answer.state === 'streaming';
  const { done, failure } = answer;
  return (
    <div className="explain-answer">
      {answer.state === 'waiting' && <Spinner label="Thinking…" />}
      {(answer.text || answer.state === 'streaming') && (
        <div className="explain-answer__text" aria-busy={busy} aria-live="polite">
          <Markdown components={MARKDOWN_COMPONENTS}>{answer.text}</Markdown>
          {busy && <span className="explain-answer__cursor" aria-hidden="true" />}
        </div>
      )}
      {done && (
        <>
          {done.finishReason === 'MAX_TOKENS' && (
            <Callout tone="warning">The answer was cut off.</Callout>
          )}
          <p className="explain-answer__meta mono">
            {model} · {level} · {formatNumber(done.promptTokens)} in /{' '}
            {formatNumber(done.outputTokens)} out · {(done.durationMs / 1000).toFixed(1)} s
          </p>
        </>
      )}
      {answer.state === 'stopped' && <p className="explain-answer__note">Stopped.</p>}
      {failure && (
        <Callout tone={failure.kind === 'interrupted' ? 'warning' : 'danger'}>
          {failureText(failure)}
          {onRetry && canRetry(failure) && (
            <Button
              size="sm"
              className="callout__action"
              icon={<RotateCw size={14} aria-hidden="true" />}
              onClick={onRetry}
            >
              Retry
            </Button>
          )}
        </Callout>
      )}
    </div>
  );
});
