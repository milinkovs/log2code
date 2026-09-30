import { Check, Copy, X } from 'lucide-react';
import { Dialog } from 'radix-ui';
import { useEffect, useState, type RefObject } from 'react';
import { useExplainPrompt } from '../../api/queries';
import type { ExplainLevel, ExplainPrompt } from '../../api/types';
import { Badge, Button, Callout, IconButton, Spinner } from '../../components/ui';
import { reasonLabel, sectionLabel } from './sections';

interface PromptDialogProps {
  logId: string;
  level: ExplainLevel;
  open: boolean;
  onOpenChange: (open: boolean) => void;
  /** Gets the focus back on close (the dialog is opened from outside, without a Radix trigger). */
  returnFocusRef?: RefObject<HTMLElement | null>;
}

const errorText = (error: unknown) => (error instanceof Error ? error.message : String(error));

/**
 * "Show prompt" (D8): the system and user prompt exactly as the model gets them for this log and
 * level, with their size and which sections are in or out (and why). Loaded only while open.
 */
export function PromptDialog({
  logId,
  level,
  open,
  onOpenChange,
  returnFocusRef,
}: PromptDialogProps) {
  const prompt = useExplainPrompt(logId, level, open);
  return (
    <Dialog.Root open={open} onOpenChange={onOpenChange}>
      <Dialog.Portal>
        <Dialog.Overlay className="dialog-overlay" />
        <Dialog.Content
          className="dialog prompt-dialog"
          // Focus the dialog itself: focusing its first button would pop that button's tooltip.
          onOpenAutoFocus={(event) => {
            event.preventDefault();
            (event.currentTarget as HTMLElement | null)?.focus();
          }}
          onCloseAutoFocus={(event) => {
            if (!returnFocusRef?.current) return;
            event.preventDefault();
            returnFocusRef.current.focus();
          }}
        >
          <div className="dialog__header">
            <Dialog.Title className="dialog__title">Prompt · {level}</Dialog.Title>
            {prompt.data && <CopyButton prompt={prompt.data} />}
            <Dialog.Close asChild>
              <IconButton label="Close" icon={<X size={14} />} />
            </Dialog.Close>
          </div>
          <Dialog.Description className="sr-only">
            The system and user prompt sent to the model for this log at level {level}.
          </Dialog.Description>
          <div className="dialog__body">
            {prompt.isPending && <Spinner label="Loading prompt…" />}
            {prompt.isError && (
              <Callout tone="danger">Could not load the prompt: {errorText(prompt.error)}</Callout>
            )}
            {prompt.data && <PromptContent prompt={prompt.data} />}
          </div>
        </Dialog.Content>
      </Dialog.Portal>
    </Dialog.Root>
  );
}

function PromptContent({ prompt }: { prompt: ExplainPrompt }) {
  const chars = (n: number) => `${n.toLocaleString('en-US')} characters`;
  return (
    <>
      <ul className="prompt-sections" aria-label="Sections">
        {prompt.sections.map((section) => (
          <li key={section.id} className="prompt-sections__item">
            {section.included ? (
              <Badge tone="accent">{sectionLabel(section.id)}</Badge>
            ) : (
              <span className="prompt-sections__omitted">{sectionLabel(section.id)}</span>
            )}
            <span className="prompt-sections__reason">
              {section.included
                ? section.reason
                  ? reasonLabel(section.reason)
                  : 'included'
                : `left out: ${reasonLabel(section.reason)}`}
            </span>
          </li>
        ))}
      </ul>
      <section className="prompt-block" aria-label="System prompt">
        <h3 className="prompt-block__title">
          System prompt{' '}
          <span className="prompt-block__size">{chars(prompt.systemPrompt.length)}</span>
        </h3>
        <pre className="code-block prompt-block__text">{prompt.systemPrompt}</pre>
      </section>
      <section className="prompt-block" aria-label="User prompt">
        <h3 className="prompt-block__title">
          User prompt <span className="prompt-block__size">{chars(prompt.promptChars)}</span>
        </h3>
        <pre className="code-block prompt-block__text">{prompt.userPrompt}</pre>
      </section>
    </>
  );
}

/** Copies both prompts, as they would be pasted into another chat, and confirms for a moment. */
function CopyButton({ prompt }: { prompt: ExplainPrompt }) {
  const [copied, setCopied] = useState(false);
  useEffect(() => {
    if (!copied) return;
    const timer = window.setTimeout(() => setCopied(false), 1500);
    return () => window.clearTimeout(timer);
  }, [copied]);
  const copy = async () => {
    try {
      await navigator.clipboard.writeText(`${prompt.systemPrompt}\n\n---\n\n${prompt.userPrompt}`);
      setCopied(true);
    } catch {
      // No clipboard (insecure context, denied): the text can still be selected by hand.
    }
  };
  return (
    <Button
      size="sm"
      variant="ghost"
      className="dialog__action"
      icon={copied ? <Check size={14} aria-hidden="true" /> : <Copy size={14} aria-hidden="true" />}
      onClick={() => void copy()}
    >
      {copied ? 'Copied' : 'Copy'}
    </Button>
  );
}
