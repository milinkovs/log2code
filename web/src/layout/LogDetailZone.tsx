import { ScrollText } from 'lucide-react';
import { isNotFound } from '../api/client';
import { useLog } from '../api/queries';
import { Callout, EmptyState, Spinner } from '../components/ui';
import { Zone } from './Zone';

/**
 * Detail of the selected log. Temporary (T26): shows the raw `LogDetail` JSON; T27 replaces it with
 * the formatted detail view.
 */
export function LogDetailZone({ logId }: { logId: string | undefined }) {
  const { data, error, isPending } = useLog(logId);

  let body;
  if (!logId) {
    body = (
      <EmptyState
        icon={ScrollText}
        title="No log selected"
        description="Details of the selected log appear here."
      />
    );
  } else if (error) {
    body = (
      <Callout tone="danger">
        {isNotFound(error) ? `Log not found: ${logId}` : `Could not load the log: ${error.message}`}
      </Callout>
    );
  } else if (isPending) {
    body = <Spinner label="Loading log…" />;
  } else {
    body = (
      <pre className="code-block" data-testid="log-detail-json">
        {JSON.stringify(data, null, 2)}
      </pre>
    );
  }

  return (
    <Zone title="Log detail" icon={ScrollText}>
      {body}
    </Zone>
  );
}
