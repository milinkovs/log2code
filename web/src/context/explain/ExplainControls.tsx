import { Check, ChevronDown, FileText, Sparkles, Square } from 'lucide-react';
import { DropdownMenu } from 'radix-ui';
import type { Ref } from 'react';
import type { ExplainLevel, LlmModel } from '../../api/types';
import { Button } from '../../components/ui';
import { EXPLAIN_LEVELS } from './explainPrefs';

interface ExplainControlsProps {
  level: ExplainLevel;
  onLevelChange: (level: ExplainLevel) => void;
  models: LlmModel[];
  model: string;
  onModelChange: (model: string) => void;
  streaming: boolean;
  /** No API key: "Explain" is off, the callout below says why (its id). */
  disabledReasonId?: string;
  onExplain: () => void;
  onStop: () => void;
  onShowPrompt: () => void;
  showPromptRef?: Ref<HTMLButtonElement>;
}

/**
 * The row on top of the explain tab (variant A, ADR-042): level, model, "Explain" (which becomes
 * "Stop" while an answer streams) and, on the right, "Show prompt".
 */
export function ExplainControls({
  level,
  onLevelChange,
  models,
  model,
  onModelChange,
  streaming,
  disabledReasonId,
  onExplain,
  onStop,
  onShowPrompt,
  showPromptRef,
}: ExplainControlsProps) {
  const description = EXPLAIN_LEVELS.find((l) => l.value === level)?.description;
  const modelLabel = models.find((m) => m.id === model)?.label ?? model;
  return (
    <div className="explain__toolbar">
      <DropdownMenu.Root modal={false}>
        <DropdownMenu.Trigger asChild>
          <Button
            size="sm"
            className="filter-trigger explain__level"
            aria-label={`Context level: ${level} ${description ?? ''}`.trim()}
          >
            <span className="mono">{level}</span>
            <span className="explain__level-text">{description}</span>
            <ChevronDown size={14} aria-hidden="true" className="filter-trigger__chevron" />
          </Button>
        </DropdownMenu.Trigger>
        <DropdownMenu.Portal>
          <DropdownMenu.Content
            className="menu explain-menu"
            aria-label="Context level"
            align="start"
            sideOffset={4}
            collisionPadding={8}
          >
            <DropdownMenu.RadioGroup
              value={level}
              onValueChange={(value) => onLevelChange(value as ExplainLevel)}
            >
              {EXPLAIN_LEVELS.map((option) => (
                <DropdownMenu.RadioItem
                  key={option.value}
                  value={option.value}
                  className="menu__item"
                >
                  <span className="menu__check" aria-hidden="true">
                    <DropdownMenu.ItemIndicator>
                      <Check size={14} />
                    </DropdownMenu.ItemIndicator>
                  </span>
                  <span className="mono">{option.value}</span>
                  <span className="explain-menu__hint">{option.description}</span>
                </DropdownMenu.RadioItem>
              ))}
            </DropdownMenu.RadioGroup>
          </DropdownMenu.Content>
        </DropdownMenu.Portal>
      </DropdownMenu.Root>

      <DropdownMenu.Root modal={false}>
        <DropdownMenu.Trigger asChild>
          <Button size="sm" className="filter-trigger" aria-label={`Model: ${modelLabel}`}>
            <span className="explain__model-text">{modelLabel}</span>
            <ChevronDown size={14} aria-hidden="true" className="filter-trigger__chevron" />
          </Button>
        </DropdownMenu.Trigger>
        <DropdownMenu.Portal>
          <DropdownMenu.Content
            className="menu explain-menu"
            aria-label="Model"
            align="start"
            sideOffset={4}
            collisionPadding={8}
          >
            <DropdownMenu.RadioGroup value={model} onValueChange={onModelChange}>
              {models.map((option) => (
                <DropdownMenu.RadioItem key={option.id} value={option.id} className="menu__item">
                  <span className="menu__check" aria-hidden="true">
                    <DropdownMenu.ItemIndicator>
                      <Check size={14} />
                    </DropdownMenu.ItemIndicator>
                  </span>
                  <span>{option.label}</span>
                  {option.label !== option.id && (
                    <span className="explain-menu__hint mono">{option.id}</span>
                  )}
                </DropdownMenu.RadioItem>
              ))}
            </DropdownMenu.RadioGroup>
          </DropdownMenu.Content>
        </DropdownMenu.Portal>
      </DropdownMenu.Root>

      {streaming ? (
        <Button size="sm" icon={<Square size={14} aria-hidden="true" />} onClick={onStop}>
          Stop
        </Button>
      ) : (
        <Button
          size="sm"
          variant="primary"
          icon={<Sparkles size={14} aria-hidden="true" />}
          disabled={!!disabledReasonId}
          aria-describedby={disabledReasonId}
          onClick={onExplain}
        >
          Explain
        </Button>
      )}

      <Button
        size="sm"
        variant="ghost"
        ref={showPromptRef}
        className="explain__prompt-button"
        icon={<FileText size={14} aria-hidden="true" />}
        onClick={onShowPrompt}
      >
        Show prompt
      </Button>
    </div>
  );
}
