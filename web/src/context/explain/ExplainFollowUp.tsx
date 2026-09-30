import { CornerDownLeft } from 'lucide-react';
import { useState, type FormEvent, type KeyboardEvent } from 'react';
import { Button } from '../../components/ui';
import { MAX_FOLLOW_UPS } from './useExplainSession';

/** Longest question the API accepts (T42: `user` text 1–2000 characters). */
export const MAX_QUESTION_LENGTH = 2000;

interface ExplainFollowUpProps {
  /** Questions already asked in this conversation. */
  asked: number;
  /** An answer is streaming, or the last one failed: no question until it is settled. */
  disabled: boolean;
  onAsk: (question: string) => void;
}

/** The question box under the answer: Enter sends, Shift+Enter starts a new line. */
export function ExplainFollowUp({ asked, disabled, onAsk }: ExplainFollowUpProps) {
  const [text, setText] = useState('');
  if (asked >= MAX_FOLLOW_UPS) {
    return <p className="explain-follow-up__limit">Start a new explanation to continue.</p>;
  }
  const canSend = !disabled && text.trim() !== '';
  const send = () => {
    if (!canSend) return;
    onAsk(text.trim());
    setText('');
  };
  const onSubmit = (event: FormEvent) => {
    event.preventDefault();
    send();
  };
  const onKeyDown = (event: KeyboardEvent<HTMLTextAreaElement>) => {
    if (event.key === 'Enter' && !event.shiftKey && !event.nativeEvent.isComposing) {
      event.preventDefault();
      send();
    }
  };
  return (
    <form className="explain-follow-up" onSubmit={onSubmit}>
      <textarea
        className="input explain-follow-up__input"
        aria-label="Follow-up question"
        placeholder="Ask a follow-up question"
        rows={2}
        maxLength={MAX_QUESTION_LENGTH}
        value={text}
        disabled={disabled}
        onChange={(event) => setText(event.target.value)}
        onKeyDown={onKeyDown}
      />
      <Button
        type="submit"
        size="sm"
        disabled={!canSend}
        icon={<CornerDownLeft size={14} aria-hidden="true" />}
      >
        Ask
      </Button>
    </form>
  );
}
