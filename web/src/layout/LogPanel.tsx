import { Rows3 } from 'lucide-react';
import { Group, Panel, Separator } from 'react-resizable-panels';
import { EmptyState } from '../components/ui';
import { LogDetailZone } from './LogDetailZone';
import { LOG_GROUP, LOG_PANELS } from './panelIds';
import { usePersistentLayout } from './layoutStorage';
import { Zone } from './Zone';

const [LIST, DETAIL] = LOG_PANELS;

/** Right side: filtered log list on top (T27), detail of the selected log below. */
export function LogPanel({ logId }: { logId: string | undefined }) {
  const layout = usePersistentLayout(LOG_GROUP, LOG_PANELS);
  return (
    <Group orientation="vertical" {...layout}>
      <Panel id={LIST} defaultSize="60%" minSize="15%">
        <Zone title="Logs" icon={Rows3}>
          <EmptyState
            icon={Rows3}
            title="Log list"
            description="The filtered log list will appear here."
          />
        </Zone>
      </Panel>
      <Separator className="separator" />
      <Panel id={DETAIL} defaultSize="40%" minSize="10%">
        <LogDetailZone logId={logId} />
      </Panel>
    </Group>
  );
}
