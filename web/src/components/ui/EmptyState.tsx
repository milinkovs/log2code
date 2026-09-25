import type { LucideIcon } from 'lucide-react';
import type { ReactNode } from 'react';

interface EmptyStateProps {
  icon: LucideIcon;
  /** Short, no trailing period: "No log selected". */
  title: string;
  /** One sentence that says what to do next, with a period. */
  description?: ReactNode;
  /** Optional action (a Button). */
  children?: ReactNode;
}

/** Centered placeholder for a zone with nothing to show yet. */
export function EmptyState({ icon: Icon, title, description, children }: EmptyStateProps) {
  return (
    <div className="empty-state">
      <span className="empty-state__icon" aria-hidden="true">
        <Icon size={20} />
      </span>
      <p className="empty-state__title">{title}</p>
      {description && <p className="empty-state__description">{description}</p>}
      {children}
    </div>
  );
}
