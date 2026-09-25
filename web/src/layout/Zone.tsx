import type { LucideIcon } from 'lucide-react';
import type { ReactNode } from 'react';
import { cx } from '../components/ui';

interface ZoneProps {
  /** Accessible name of the region; also shown as the zone header (sentence case). */
  title: string;
  /** Lucide icon shown before the title. */
  icon?: LucideIcon;
  /** Short secondary info right after the title, e.g. a count. */
  meta?: ReactNode;
  /** Optional controls rendered at the right end of the header. */
  actions?: ReactNode;
  /** No padding and no scrolling in the body: the child manages its own scroll area (lists). */
  flush?: boolean;
  children?: ReactNode;
}

/** One of the four work areas of the layout: a titled card with an independently scrolling body. */
export function Zone({ title, icon: Icon, meta, actions, flush, children }: ZoneProps) {
  return (
    <section className="zone" aria-label={title}>
      <header className="zone__header">
        {Icon && <Icon size={14} className="zone__icon" aria-hidden="true" />}
        <h2 className="zone__title">{title}</h2>
        {meta !== undefined && <span className="zone__meta">{meta}</span>}
        {actions && <div className="zone__actions">{actions}</div>}
      </header>
      <div className={cx('zone__body', flush && 'zone__body--flush')}>{children}</div>
    </section>
  );
}
