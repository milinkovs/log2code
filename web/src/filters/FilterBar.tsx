import { Clock, Search, Waypoints, Zap } from 'lucide-react';
import { type FormEvent, useCallback, useEffect, useState } from 'react';
import { useSearchParams } from 'react-router';
import type { ConfidenceLevel, Level, MatchStatus } from '../api/types';
import { useServices } from '../api/queries';
import { Button, LevelBadge, MultiSelect, Popover, cx } from '../components/ui';
import { CONFIDENCES, LEVELS, PARAM, STATUSES } from './filters';
import { fromDateTimeLocal, toDateTimeLocal } from './time';
import { useLogFilters } from './useLogFilters';

/** Delay between the last keystroke in the search field and the URL/API update. */
export const SEARCH_DEBOUNCE_MS = 300;

const LEVEL_OPTIONS = LEVELS.map((value) => ({ value, label: <LevelBadge level={value} /> }));
const STATUS_OPTIONS = STATUSES.map((value) => ({ value }));
const CONFIDENCE_OPTIONS = CONFIDENCES.map((value) => ({ value }));

/** The filter controls in the top bar; active values are listed below it (ActiveFilters). */
export function FilterBar() {
  const { filters, update } = useLogFilters();
  const [searchParams] = useSearchParams();
  const { data: services } = useServices(searchParams.get(PARAM.datasetId) ?? undefined);
  const commitSearch = useCallback(
    (q: string, replace: boolean) => update({ q }, { replace }),
    [update],
  );

  // A service from the URL that the API does not list (other dataset) stays selectable.
  const serviceOptions = [...new Set([...(services ?? []), ...filters.service])]
    .sort()
    .map((value) => ({ value }));

  return (
    <div className="topbar__filters" role="search" aria-label="Filters">
      <SearchField value={filters.q} onCommit={commitSearch} />
      <MultiSelect
        label="Service"
        options={serviceOptions}
        selected={filters.service}
        onChange={(service) => update({ service })}
        emptyText="No services in this dataset."
      />
      <MultiSelect<Level>
        label="Level"
        options={LEVEL_OPTIONS}
        selected={filters.level}
        onChange={(level) => update({ level })}
      />
      <MultiSelect<MatchStatus>
        label="Status"
        options={STATUS_OPTIONS}
        selected={filters.status}
        onChange={(status) => update({ status })}
      />
      <MultiSelect<ConfidenceLevel>
        label="Confidence"
        options={CONFIDENCE_OPTIONS}
        selected={filters.confidence}
        onChange={(confidence) => update({ confidence })}
      />
      <TimeRangeFilter
        from={filters.from}
        to={filters.to}
        onApply={(from, to) => update({ from, to })}
      />
      <TraceIdFilter value={filters.traceId} onApply={(traceId) => update({ traceId })} />
      <Button
        className={cx('filter-trigger', filters.hasException && 'filter-trigger--active')}
        aria-pressed={filters.hasException}
        icon={<Zap size={14} aria-hidden="true" />}
        onClick={() => update({ hasException: !filters.hasException })}
      >
        <span className="filter-trigger__text">Exceptions only</span>
      </Button>
    </div>
  );
}

/**
 * Free-text search over messages. Typing updates the URL 300 ms after the last keystroke
 * (replacing the history entry); Enter applies at once. Outside changes (Clear, Back) win.
 */
function SearchField({
  value,
  onCommit,
}: {
  value: string;
  onCommit: (q: string, replace: boolean) => void;
}) {
  const [draft, setDraft] = useState(value);
  const [synced, setSynced] = useState(value);
  if (value !== synced) {
    // The URL changed from elsewhere: adopt it (state adjustment during render, no effect).
    setSynced(value);
    setDraft(value);
  }

  useEffect(() => {
    if (draft === value) return;
    const timer = setTimeout(() => onCommit(draft, true), SEARCH_DEBOUNCE_MS);
    return () => clearTimeout(timer);
  }, [draft, value, onCommit]);

  return (
    <label className="search-field">
      <Search size={14} aria-hidden="true" className="search-field__icon" />
      <input
        type="search"
        aria-label="Search messages"
        placeholder="Search messages"
        value={draft}
        onChange={(event) => setDraft(event.target.value)}
        onKeyDown={(event) => {
          if (event.key === 'Enter' && draft !== value) onCommit(draft, false);
        }}
      />
    </label>
  );
}

function TimeRangeFilter({
  from,
  to,
  onApply,
}: {
  from: string;
  to: string;
  onApply: (from: string, to: string) => void;
}) {
  const [open, setOpen] = useState(false);
  const [draftFrom, setDraftFrom] = useState('');
  const [draftTo, setDraftTo] = useState('');

  const onOpenChange = (next: boolean) => {
    if (next) {
      setDraftFrom(toDateTimeLocal(from));
      setDraftTo(toDateTimeLocal(to));
    }
    setOpen(next);
  };
  const submit = (event: FormEvent) => {
    event.preventDefault();
    onApply(fromDateTimeLocal(draftFrom, 'from'), fromDateTimeLocal(draftTo, 'to'));
    setOpen(false);
  };

  const active = Boolean(from || to);
  return (
    <Popover
      label="Time range"
      open={open}
      onOpenChange={onOpenChange}
      trigger={
        <Button
          className={cx('filter-trigger', active && 'filter-trigger--active')}
          icon={<Clock size={14} aria-hidden="true" />}
        >
          <span className="filter-trigger__text">Time</span>
        </Button>
      }
    >
      <form className="popover-form" onSubmit={submit}>
        <label className="field">
          <span className="field__label">From (UTC)</span>
          <input
            className="input mono"
            type="datetime-local"
            step={1}
            value={draftFrom}
            onChange={(e) => setDraftFrom(e.target.value)}
          />
        </label>
        <label className="field">
          <span className="field__label">To (UTC)</span>
          <input
            className="input mono"
            type="datetime-local"
            step={1}
            value={draftTo}
            onChange={(e) => setDraftTo(e.target.value)}
          />
        </label>
        <p className="popover-form__hint">
          Both ends are inclusive. Leave one empty for an open range.
        </p>
        <div className="popover-form__actions">
          <Button
            variant="ghost"
            size="sm"
            onClick={() => {
              setDraftFrom('');
              setDraftTo('');
            }}
          >
            Reset
          </Button>
          <Button variant="primary" size="sm" type="submit">
            Apply
          </Button>
        </div>
      </form>
    </Popover>
  );
}

function TraceIdFilter({ value, onApply }: { value: string; onApply: (traceId: string) => void }) {
  const [open, setOpen] = useState(false);
  const [draft, setDraft] = useState('');

  const onOpenChange = (next: boolean) => {
    if (next) setDraft(value);
    setOpen(next);
  };
  const submit = (event: FormEvent) => {
    event.preventDefault();
    onApply(draft.trim());
    setOpen(false);
  };

  return (
    <Popover
      label="Trace id"
      open={open}
      onOpenChange={onOpenChange}
      trigger={
        <Button
          className={cx('filter-trigger', value && 'filter-trigger--active')}
          icon={<Waypoints size={14} aria-hidden="true" />}
        >
          <span className="filter-trigger__text">Trace id</span>
        </Button>
      }
    >
      <form className="popover-form" onSubmit={submit}>
        <label className="field">
          <span className="field__label">Trace id</span>
          <input
            className="input mono"
            placeholder="6a3f0c9e1b2c4d5e"
            value={draft}
            onChange={(e) => setDraft(e.target.value)}
            autoFocus
          />
        </label>
        <div className="popover-form__actions">
          <Button variant="primary" size="sm" type="submit">
            Apply
          </Button>
        </div>
      </form>
    </Popover>
  );
}
