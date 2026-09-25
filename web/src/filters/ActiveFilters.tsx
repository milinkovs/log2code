import { Button, FilterChip } from '../components/ui';
import { hasActiveFilters } from './filters';
import { formatDateTime } from './time';
import { useLogFilters } from './useLogFilters';

/** Long ids are shortened in chips; the full value is in the title (docs/design.md §7). */
const shorten = (value: string, max = 16) =>
  value.length > max ? `${value.slice(0, max)}…` : value;

/**
 * Second row of the top bar: one removable chip per active filter and "Clear". Rendered only
 * while something is filtered, so the top bar is a single row otherwise.
 */
export function ActiveFilters() {
  const { filters: f, update, clear } = useLogFilters();
  if (!hasActiveFilters(f)) return null;

  return (
    <div className="topbar__chips" role="group" aria-label="Active filters">
      {f.q.trim() && (
        <FilterChip name="text" onRemove={() => update({ q: '' })}>
          “{shorten(f.q.trim(), 40)}”
        </FilterChip>
      )}
      {f.service.length > 0 && (
        <FilterChip name="service" onRemove={() => update({ service: [] })}>
          {f.service.join(', ')}
        </FilterChip>
      )}
      {f.level.length > 0 && (
        <FilterChip name="level" onRemove={() => update({ level: [] })}>
          {f.level.join(', ')}
        </FilterChip>
      )}
      {f.status.length > 0 && (
        <FilterChip name="status" onRemove={() => update({ status: [] })}>
          {f.status.join(', ')}
        </FilterChip>
      )}
      {f.confidence.length > 0 && (
        <FilterChip name="confidence" onRemove={() => update({ confidence: [] })}>
          {f.confidence.join(', ')}
        </FilterChip>
      )}
      {f.from && (
        <FilterChip name="from" onRemove={() => update({ from: '' })}>
          <span className="mono">{formatDateTime(f.from)} UTC</span>
        </FilterChip>
      )}
      {f.to && (
        <FilterChip name="to" onRemove={() => update({ to: '' })}>
          <span className="mono">{formatDateTime(f.to)} UTC</span>
        </FilterChip>
      )}
      {f.traceId && (
        <FilterChip name="trace" onRemove={() => update({ traceId: '' })}>
          <span className="mono" title={f.traceId}>
            {shorten(f.traceId)}
          </span>
        </FilterChip>
      )}
      {f.hasException && (
        <FilterChip name="exceptions" onRemove={() => update({ hasException: false })}>
          only
        </FilterChip>
      )}
      <Button variant="ghost" size="sm" onClick={clear}>
        Clear
      </Button>
    </div>
  );
}
