import { Group, Panel, Separator } from 'react-resizable-panels';
import { useParams } from 'react-router';
import { CodePanel } from './CodePanel';
import { LogPanel } from './LogPanel';
import { WORKSPACE_GROUP, WORKSPACE_PANELS } from './panelIds';
import { usePersistentLayout } from './layoutStorage';
import { TopBar } from './TopBar';

const [CODE, LOGS] = WORKSPACE_PANELS;

/**
 * The whole screen: top bar, then code on the LEFT and logs on the RIGHT, each split vertically
 * into two zones. Rendered by both routes, so panels stay mounted while the selected log changes.
 */
export function AppShell() {
  const { logId } = useParams();
  const layout = usePersistentLayout(WORKSPACE_GROUP, WORKSPACE_PANELS);
  return (
    <div className="app-shell">
      <TopBar />
      <main className="workspace">
        <Group orientation="horizontal" {...layout}>
          <Panel id={CODE} defaultSize="55%" minSize="20%">
            <CodePanel />
          </Panel>
          <Separator className="separator" />
          <Panel id={LOGS} defaultSize="45%" minSize="20%">
            <LogPanel logId={logId} />
          </Panel>
        </Group>
      </main>
    </div>
  );
}
