import { ToggleGroup } from 'radix-ui';
import { useEffect, useRef } from 'react';
import { isNotFound } from '../api/client';
import { useLogNeighbors } from '../api/queries';
import type { LogSummary, NeighborScope } from '../api/types';
import { Button, Callout, Spinner, Tooltip } from '../components/ui';
import { useSelectLog } from '../logs/useSelectLog';
import { ContextLogRow } from './ContextLogRow';
import { NEIGHBOR_COUNTS, type NeighborCount } from './contextPrefs';

const errorText = (error: unknown) => (error instanceof Error ? error.message : String(error));

const SCOPES: { value: NeighborScope; label: string; hint: string }[] = [
  { value: 'service', label: 'Service', hint: 'The same log file of the service, in file order' },
  { value: 'thread', label: 'Thread', hint: 'The same service and thread, by time' },
  { value: 'dataset', label: 'All services', hint: 'Every service of the dataset, by time' },
];

const EDGE: Record<NeighborScope, { before: string; after: string }> = {
  service: { before: 'Start of the log file.', after: 'End of the log file.' },
  thread: { before: 'No earlier logs in this thread.', after: 'No later logs in this thread.' },
  dataset: { before: 'No earlier logs in the dataset.', after: 'No later logs in the dataset.' },
};

/**
 * "Neighbors" (T30): the logs around the selected one, `count` on each side, in the chosen scope
 * (T24: `service` = the same file by `sequence`; `thread` and `dataset` by time). The current log
 * is the selected row; clicking another selects it for the whole app, and this list follows.
 */
export function NeighborsTab({
  logId,
  scope,
  onScopeChange,
  count,
  onCountChange,
}: {
  logId: string;
  scope: NeighborScope;
  onScopeChange: (scope: NeighborScope) => void;
  count: NeighborCount;
  onCountChange: (count: NeighborCount) => void;
}) {
  const query = useLogNeighbors(logId, scope, count);
  const selectLog = useSelectLog();
  const select = (id: string) => {
    if (id !== logId) selectLog(id);
  };

  return (
    <div className="neighbors">
      <div className="neighbors__toolbar">
        <ToggleGroup.Root
          type="single"
          className="segmented"
          aria-label="Scope"
          value={scope}
          // Clicking the active item would clear the value; a scope is always selected.
          onValueChange={(value) => value && onScopeChange(value as NeighborScope)}
        >
          {SCOPES.map(({ value, label, hint }) => (
            <Tooltip key={value} content={hint}>
              <ToggleGroup.Item value={value} className="segmented__item segmented__item--text">
                {label}
              </ToggleGroup.Item>
            </Tooltip>
          ))}
        </ToggleGroup.Root>
        <ToggleGroup.Root
          type="single"
          className="segmented"
          aria-label="Logs on each side"
          value={String(count)}
          onValueChange={(value) => value && onCountChange(Number(value) as NeighborCount)}
        >
          {NEIGHBOR_COUNTS.map((n) => (
            <ToggleGroup.Item
              key={n}
              value={String(n)}
              className="segmented__item segmented__item--text mono"
            >
              {n}
            </ToggleGroup.Item>
          ))}
        </ToggleGroup.Root>
        <span className="neighbors__hint">before and after · UTC</span>
        {query.isFetching && !query.isPending && <Spinner label="Updating…" />}
      </div>
      {query.isPending ? (
        <Spinner label="Loading neighbors…" />
      ) : query.isError ? (
        <Callout tone="danger">
          {isNotFound(query.error)
            ? `Log not found: ${logId}`
            : `Could not load the neighbors: ${errorText(query.error)}`}
          <Button size="sm" className="callout__action" onClick={() => void query.refetch()}>
            Retry
          </Button>
        </Callout>
      ) : (
        <NeighborList
          selectedId={logId}
          before={query.data.before}
          current={query.data.current}
          after={query.data.after}
          edge={EDGE[scope]}
          onSelect={select}
        />
      )}
    </div>
  );
}

/**
 * Rows before, at and after the log the answer is about. The selected log is marked wherever it
 * is: while another log's neighbors load, the previous answer stays and the clicked row is marked.
 */
function NeighborList({
  selectedId,
  before,
  current,
  after,
  edge,
  onSelect,
}: {
  selectedId: string;
  before: LogSummary[];
  current: LogSummary;
  after: LogSummary[];
  edge: { before: string; after: string };
  onSelect: (logId: string) => void;
}) {
  const listRef = useRef<HTMLOListElement>(null);

  // Center the selected log when it, the scope or the count changes: both sides stay in view.
  // Only the zone body scrolls; `scrollIntoView` would also move the layout around it.
  useEffect(() => {
    const row = listRef.current?.querySelector('[aria-current="true"]');
    const body = row?.closest('.zone__body');
    if (!row || !body) return;
    const rowBox = row.getBoundingClientRect();
    const bodyBox = body.getBoundingClientRect();
    body.scrollTop += rowBox.top - bodyBox.top - (body.clientHeight - rowBox.height) / 2;
  }, [selectedId, current.logId, before.length, after.length]);

  const row = (item: LogSummary) => (
    <ContextLogRow
      key={item.logId}
      item={item}
      current={item.logId === selectedId}
      onSelect={onSelect}
    />
  );
  return (
    <ol ref={listRef} className="context-logs" aria-label="Neighbor logs">
      {before.length === 0 && <li className="context-logs__edge">{edge.before}</li>}
      {before.map(row)}
      {row(current)}
      {after.map(row)}
      {after.length === 0 && <li className="context-logs__edge">{edge.after}</li>}
    </ol>
  );
}
