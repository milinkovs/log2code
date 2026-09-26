import { GitBranch, Layers, ListTree, Unlink, type LucideIcon } from 'lucide-react';
import { Tabs } from 'radix-ui';
import type { ReactNode } from 'react';
import { isNotFound } from '../api/client';
import type { CatalogEntryDto } from '../api/types';
import { Callout, EmptyState, Spinner } from '../components/ui';
import { Zone } from '../layout/Zone';
import { CallersTab } from './CallersTab';
import { useContextTab, type ContextTab } from './contextTab';
import { FlowTab } from './FlowTab';
import { useShownStatement } from './useShownStatement';

const TABS: { value: ContextTab; label: string; icon: LucideIcon }[] = [
  { value: 'flow', label: 'Conditions and flow', icon: GitBranch },
  { value: 'callers', label: 'Callers', icon: ListTree },
];

const errorText = (error: unknown) => (error instanceof Error ? error.message : String(error));

/**
 * The "Context" zone (T29): tabs about the statement shown in the code zone. Radix renders only
 * the open tab, so a tab makes no request until it is opened.
 */
export function ContextZone({ logId }: { logId: string | undefined }) {
  const [tab, setTab] = useContextTab();
  const tabs = (
    <Tabs.List className="tabs" aria-label="Context">
      {TABS.map(({ value, label, icon: Icon }) => (
        <Tabs.Trigger key={value} value={value} className="tabs__trigger">
          <Icon size={14} aria-hidden="true" />
          {label}
        </Tabs.Trigger>
      ))}
    </Tabs.List>
  );
  return (
    <Tabs.Root className="context-zone" value={tab} onValueChange={setTab}>
      <Zone title="Context" icon={Layers} tabs={tabs}>
        <Tabs.Content value="flow" className="context-zone__tab">
          <StatementGate logId={logId}>{(entry) => <FlowTab entry={entry} />}</StatementGate>
        </Tabs.Content>
        <Tabs.Content value="callers" className="context-zone__tab">
          <StatementGate logId={logId}>{(entry) => <CallersTab entry={entry} />}</StatementGate>
        </Tabs.Content>
      </Zone>
    </Tabs.Root>
  );
}

/** Shows the tab once the shown statement is known; otherwise the reason it cannot be shown. */
function StatementGate({
  logId,
  children,
}: {
  logId: string | undefined;
  children: (entry: CatalogEntryDto) => ReactNode;
}) {
  const { log, shownId, entry } = useShownStatement(logId);

  if (!logId) {
    return (
      <EmptyState
        icon={Layers}
        title="No log selected"
        description="Pick a log to see how its code is reached and who calls it."
      />
    );
  }
  if (log.isPending) return <Spinner label="Loading log…" />;
  if (log.isError) {
    return (
      <Callout tone="danger">
        {isNotFound(log.error)
          ? `Log not found: ${logId}`
          : `Could not load the log: ${errorText(log.error)}`}
      </Callout>
    );
  }
  if (!shownId) {
    return (
      <EmptyState
        icon={Unlink}
        title="No statement for this log"
        description="Nothing matched this log. If the code zone lists candidates, pick one to see its context."
      />
    );
  }
  if (entry.isPending) return <Spinner label="Loading statement…" />;
  if (entry.isError) {
    return isNotFound(entry.error) ? (
      <Callout tone="warning">
        This statement is no longer in the catalog: <span className="mono">{shownId}</span>.
      </Callout>
    ) : (
      <Callout tone="danger">Could not load the statement: {errorText(entry.error)}</Callout>
    );
  }
  return children(entry.data);
}
