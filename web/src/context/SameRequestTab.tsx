import { AlignVerticalSpaceAround, ListFilter, Waypoints } from 'lucide-react';
import type { CSSProperties } from 'react';
import { isNotFound } from '../api/client';
import { useLogTrace } from '../api/queries';
import { Button, Callout, EmptyState, Spinner } from '../components/ui';
import { formatDateTime } from '../filters/time';
import { useLogFilters } from '../filters/useLogFilters';
import { useSelectLog } from '../logs/useSelectLog';
import { ContextLogRow } from './ContextLogRow';
import { formatOffset, serviceColors } from './sameRequest';

const errorText = (error: unknown) => (error instanceof Error ? error.message : String(error));

/** Default `limit` of `GET /api/logs/{id}/trace` (T24); a full answer may have been cut. */
const TRACE_LIMIT = 200;

const shortId = (id: string) => (id.length > 12 ? `${id.slice(0, 12)}…` : id);

/**
 * "Same request" (T30): the logs that share the selected log's trace id, in its dataset, across
 * services, by time. Each service has its own color (`--service-N`). PetClinic does not propagate
 * the trace id through the gateway (ADR-005), so this promises only what the trace id links.
 * Without a trace id, it explains why and offers the thread neighbors instead.
 */
export function SameRequestTab({
  logId,
  onShowThreadNeighbors,
}: {
  logId: string;
  onShowThreadNeighbors: () => void;
}) {
  const query = useLogTrace(logId);
  const selectLog = useSelectLog();
  const { update } = useLogFilters();

  if (query.isPending) return <Spinner label="Loading the request…" />;
  if (query.isError) {
    return (
      <Callout tone="danger">
        {isNotFound(query.error)
          ? `Log not found: ${logId}`
          : `Could not load the request: ${errorText(query.error)}`}
        <Button size="sm" className="callout__action" onClick={() => void query.refetch()}>
          Retry
        </Button>
      </Callout>
    );
  }

  const { items, reason } = query.data;
  if (reason !== null) {
    return (
      <EmptyState
        icon={Waypoints}
        title="No trace id on this log"
        description="Logs of one request can only be grouped by a trace id. The logs of the same thread around this one are the closest substitute."
      >
        <Button
          icon={<AlignVerticalSpaceAround size={14} aria-hidden="true" />}
          onClick={onShowThreadNeighbors}
        >
          Show neighbors in this thread
        </Button>
      </EmptyState>
    );
  }

  const traceId = items.find((item) => item.logId === logId)?.traceId ?? items[0]?.traceId ?? null;
  const colors = serviceColors(items.map((item) => item.service));
  const first = items[0]?.timestamp ?? null;

  return (
    <div className="same-request">
      <div className="same-request__toolbar">
        <p className="same-request__summary">
          {traceId && (
            <span className="mono" title={traceId}>
              trace {shortId(traceId)}
            </span>
          )}
          {` · ${items.length} ${items.length === 1 ? 'log' : 'logs'} · ${colors.size} ${colors.size === 1 ? 'service' : 'services'}`}
          {first && ` · from ${formatDateTime(first)} UTC`}
        </p>
        {traceId && (
          <Button
            size="sm"
            variant="ghost"
            icon={<ListFilter size={14} aria-hidden="true" />}
            onClick={() => update({ traceId })}
          >
            Show in log list
          </Button>
        )}
      </div>
      {items.length === 0 ? (
        <p className="context-logs__edge">No logs share this trace id.</p>
      ) : (
        <ol className="context-logs" aria-label="Logs of the same request">
          {items.map((item) => (
            <ContextLogRow
              key={item.logId}
              item={item}
              current={item.logId === logId}
              onSelect={(id) => id !== logId && selectLog(id)}
              lead={
                <>
                  <span
                    className="context-log__offset mono"
                    title={item.timestamp ? `${formatDateTime(item.timestamp)} UTC` : undefined}
                  >
                    {formatOffset(first, item.timestamp)}
                  </span>
                  <span
                    className="service-dot"
                    style={{ '--service-color': colors.get(item.service ?? '') } as CSSProperties}
                    aria-hidden="true"
                  />
                </>
              }
            />
          ))}
        </ol>
      )}
      {items.length >= TRACE_LIMIT && (
        <p className="context-logs__edge">
          Showing the first {TRACE_LIMIT} logs of this request. Use “Show in log list” to see all.
        </p>
      )}
    </div>
  );
}
