import {
  AlignVerticalSpaceAround,
  GitBranch,
  Layers,
  ListTree,
  Unlink,
  Waypoints,
  Zap,
  type LucideIcon,
} from 'lucide-react';
import { Tabs } from 'radix-ui';
import type { ReactNode } from 'react';
import { isNotFound } from '../api/client';
import { useLog } from '../api/queries';
import type { CatalogEntryDto, LogDetail } from '../api/types';
import { Callout, EmptyState, Spinner, Tooltip } from '../components/ui';
import { Zone } from '../layout/Zone';
import { CallersTab } from './CallersTab';
import { useNeighborSettings } from './contextPrefs';
import { useContextTab, type ContextTab } from './contextTab';
import { FlowTab } from './FlowTab';
import { NeighborsTab } from './NeighborsTab';
import { SameRequestTab } from './SameRequestTab';
import { StackTraceTab } from './StackTraceTab';
import { useShownStatement } from './useShownStatement';

const TABS: { value: ContextTab; label: string; icon: LucideIcon }[] = [
  { value: 'flow', label: 'Conditions and flow', icon: GitBranch },
  { value: 'callers', label: 'Callers', icon: ListTree },
  { value: 'stack', label: 'Stack trace', icon: Zap },
  { value: 'neighbors', label: 'Neighbors', icon: AlignVerticalSpaceAround },
  { value: 'request', label: 'Same request', icon: Waypoints },
];

const errorText = (error: unknown) => (error instanceof Error ? error.message : String(error));

/**
 * The "Context" zone: tabs about the statement shown in the code zone (T29: conditions and flow,
 * callers) and about the selected log (T30: stack trace, neighbors, same request). Radix renders
 * only the open tab, so a tab makes no request until it is opened. "Stack trace" exists only for a
 * log with an exception; for any other log the zone shows the first tab, while the remembered
 * choice stays for the next log with an exception.
 */
export function ContextZone({ logId }: { logId: string | undefined }) {
  const [tab, setTab] = useContextTab();
  const neighbors = useNeighborSettings();
  const log = useLog(logId);
  // While the log loads, keep a remembered "Stack trace" tab instead of flashing the first one.
  const hasStack = !!logId && (log.isPending ? tab === 'stack' : !!log.data?.exception);
  const shown: ContextTab = tab === 'stack' && !hasStack ? 'flow' : tab;
  const tabs = (
    <Tabs.List className="tabs" aria-label="Context">
      {TABS.filter(({ value }) => value !== 'stack' || hasStack).map(
        ({ value, label, icon: Icon }) => (
          // In a narrow zone only the icons show (context.css); the label stays for screen readers.
          <Tooltip key={value} content={label}>
            <Tabs.Trigger value={value} className="tabs__trigger">
              <Icon size={14} aria-hidden="true" />
              <span className="tabs__label">{label}</span>
            </Tabs.Trigger>
          </Tooltip>
        ),
      )}
    </Tabs.List>
  );
  const showThreadNeighbors = () => {
    neighbors.setScope('thread');
    setTab('neighbors');
  };
  return (
    <Tabs.Root className="context-zone" value={shown} onValueChange={setTab}>
      <Zone title="Context" icon={Layers} tabs={tabs}>
        <Tabs.Content value="flow" className="context-zone__tab">
          <StatementGate logId={logId}>{(entry) => <FlowTab entry={entry} />}</StatementGate>
        </Tabs.Content>
        <Tabs.Content value="callers" className="context-zone__tab">
          <StatementGate logId={logId}>{(entry) => <CallersTab entry={entry} />}</StatementGate>
        </Tabs.Content>
        {hasStack && (
          <Tabs.Content value="stack" className="context-zone__tab">
            <LogGate logId={logId}>
              {(detail) =>
                detail.exception && (
                  <StackTraceTab key={detail.logId} exception={detail.exception} />
                )
              }
            </LogGate>
          </Tabs.Content>
        )}
        <Tabs.Content value="neighbors" className="context-zone__tab">
          <LogIdGate logId={logId}>
            {(id) => (
              <NeighborsTab
                logId={id}
                scope={neighbors.scope}
                onScopeChange={neighbors.setScope}
                count={neighbors.count}
                onCountChange={neighbors.setCount}
              />
            )}
          </LogIdGate>
        </Tabs.Content>
        <Tabs.Content value="request" className="context-zone__tab">
          <LogIdGate logId={logId}>
            {(id) => <SameRequestTab logId={id} onShowThreadNeighbors={showThreadNeighbors} />}
          </LogIdGate>
        </Tabs.Content>
      </Zone>
    </Tabs.Root>
  );
}

const NoLogSelected = () => (
  <EmptyState
    icon={Layers}
    title="No log selected"
    description="Pick a log to see its stack trace, its neighbors and the rest of its request."
  />
);

/**
 * Shows a tab that needs only the id of the selected log (T30: neighbors, same request). Their own
 * requests report an unknown log, and they do not wait for the log detail, so selecting a neighbor
 * keeps the list on screen instead of flashing a spinner.
 */
function LogIdGate({
  logId,
  children,
}: {
  logId: string | undefined;
  children: (logId: string) => ReactNode;
}) {
  return logId ? children(logId) : <NoLogSelected />;
}

/**
 * Shows a tab about the log itself (T30: stack trace) once the log is loaded. Unlike `StatementGate`, it does
 * not need a statement: these tabs work for unmatched logs too.
 */
function LogGate({
  logId,
  children,
}: {
  logId: string | undefined;
  children: (log: LogDetail) => ReactNode;
}) {
  const log = useLog(logId);
  if (!logId) return <NoLogSelected />;
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
  return children(log.data);
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
