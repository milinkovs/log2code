import { X } from 'lucide-react';
import type { ReactNode } from 'react';

interface FilterChipProps {
  /** Filter name, e.g. "level". */
  name: string;
  /** Current value(s), e.g. "ERROR, WARN". */
  children: ReactNode;
  onRemove: () => void;
}

/** An active filter: accent badge with the value and a remove button (docs/design.md §5.2). */
export function FilterChip({ name, children, onRemove }: FilterChipProps) {
  return (
    <span className="badge badge--accent chip">
      <span className="chip__name">{name}:</span>
      <span className="chip__value">{children}</span>
      <button
        type="button"
        className="chip__remove"
        aria-label={`Remove ${name} filter`}
        onClick={onRemove}
      >
        <X size={12} aria-hidden="true" />
      </button>
    </span>
  );
}
