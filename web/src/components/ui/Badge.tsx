import type { ReactNode } from 'react';
import type { Level } from '../../api/types';
import { cx } from './cx';
import { asLevel } from './level';
import type { MatchConfidence } from './matchConfidence';

export type BadgeTone = 'neutral' | 'accent' | 'success' | 'warning' | 'danger';

/** Small tinted label for categorical metadata (a filter chip, "library", "showing alternative"). */
export function Badge({ tone = 'neutral', children }: { tone?: BadgeTone; children: ReactNode }) {
  return <span className={cx('badge', `badge--${tone}`)}>{children}</span>;
}

const LEVEL_TONE: Record<Level, string> = {
  TRACE: 'trace',
  DEBUG: 'debug',
  INFO: 'info',
  WARN: 'warn',
  ERROR: 'error',
  FATAL: 'error',
  UNKNOWN: 'trace',
};

/** Log level as a fixed-width colored pill; the text carries the meaning, color only helps. */
export function LevelBadge({ level }: { level: string | null }) {
  const known = asLevel(level);
  return <span className={cx('level', `level--${LEVEL_TONE[known]}`)}>{known}</span>;
}

/** Match confidence: colored dot + word (+ optional score). Hollow dot when nothing matched. */
export function ConfidenceBadge({ value, score }: { value: MatchConfidence; score?: number }) {
  return (
    <span className={cx('confidence', `confidence--${value}`)}>
      <span className="confidence__dot" aria-hidden="true" />
      {value}
      {score !== undefined && <span className="confidence__score mono">{score.toFixed(2)}</span>}
    </span>
  );
}
