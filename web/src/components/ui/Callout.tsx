import { CircleAlert, Info, TriangleAlert } from 'lucide-react';
import type { ReactNode } from 'react';
import { cx } from './cx';

export type CalloutTone = 'info' | 'warning' | 'danger';

const ICONS = { info: Info, warning: TriangleAlert, danger: CircleAlert } as const;

/** Inline message inside a zone: errors (`danger`, announced), warnings, notes. */
export function Callout({ tone = 'info', children }: { tone?: CalloutTone; children: ReactNode }) {
  const Icon = ICONS[tone];
  return (
    <div className={cx('callout', `callout--${tone}`)} role={tone === 'danger' ? 'alert' : 'note'}>
      <Icon size={16} aria-hidden="true" />
      <div>{children}</div>
    </div>
  );
}
