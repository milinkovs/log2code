import { Zap } from 'lucide-react';
import type { ReactNode } from 'react';
import type { LogSummary } from '../api/types';
import { LevelBadge, cx } from '../components/ui';
import { formatDateTime, formatTime } from '../filters/time';

/**
 * A log in the "Neighbors" and "Same request" tabs (T30): like a row of the log list (design.md
 * §5.2), as a button that selects the log for the whole app. The current log is marked as the
 * selected row (`aria-current`); `lead` replaces the time column (the offset in "Same request").
 */
export function ContextLogRow({
  item,
  current,
  lead,
  onSelect,
}: {
  item: LogSummary;
  current: boolean;
  lead?: ReactNode;
  onSelect: (logId: string) => void;
}) {
  return (
    <li>
      <button
        type="button"
        className={cx('context-log', current && 'context-log--current')}
        aria-current={current ? 'true' : undefined}
        onClick={() => onSelect(item.logId)}
      >
        {lead ?? (
          <span
            className="context-log__time mono"
            title={item.timestamp ? `${formatDateTime(item.timestamp)} UTC` : undefined}
          >
            {formatTime(item.timestamp)}
          </span>
        )}
        <LevelBadge level={item.level} />
        <span className="context-log__service" title={item.service ?? undefined}>
          {item.service}
        </span>
        <span className="context-log__message">{item.message}</span>
        <span className="context-log__exception">
          {item.hasException && <Zap size={14} role="img" aria-label="Has exception" />}
        </span>
      </button>
    </li>
  );
}
