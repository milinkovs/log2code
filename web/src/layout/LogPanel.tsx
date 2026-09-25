import { Group, Panel, Separator } from 'react-resizable-panels';
import { LogDetailZone } from '../logs/LogDetailZone';
import { LogListZone } from '../logs/LogList';
import { LOG_GROUP, LOG_PANELS } from './panelIds';
import { usePersistentLayout } from './layoutStorage';

const [LIST, DETAIL] = LOG_PANELS;

/** Right side: filtered log list on top, detail of the selected log below (T27). */
export function LogPanel({ logId }: { logId: string | undefined }) {
  const layout = usePersistentLayout(LOG_GROUP, LOG_PANELS);
  return (
    <Group orientation="vertical" {...layout}>
      <Panel id={LIST} defaultSize="60%" minSize="15%">
        <LogListZone logId={logId} />
      </Panel>
      <Separator className="separator" />
      <Panel id={DETAIL} defaultSize="40%" minSize="10%">
        <LogDetailZone logId={logId} />
      </Panel>
    </Group>
  );
}
