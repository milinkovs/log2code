import { Group, Panel, Separator } from 'react-resizable-panels';
import { CodeFocusProvider } from '../code/CodeFocusProvider';
import { CodeZone } from '../code/CodeZone';
import { ContextZone } from '../context/ContextZone';
import { CODE_GROUP, CODE_PANELS } from './panelIds';
import { usePersistentLayout } from './layoutStorage';

const [VIEWER, CONTEXT] = CODE_PANELS;

/**
 * Left side: source code on top (T28), context tabs below (T29, T30). Clicks in the tabs move the
 * editor, through the URL (`?at=`) or a focus request shared by both zones.
 */
export function CodePanel({ logId }: { logId: string | undefined }) {
  const layout = usePersistentLayout(CODE_GROUP, CODE_PANELS);
  return (
    <CodeFocusProvider logId={logId}>
      <Group orientation="vertical" {...layout}>
        <Panel id={VIEWER} defaultSize="65%" minSize="15%">
          <CodeZone logId={logId} />
        </Panel>
        <Separator className="separator" />
        <Panel id={CONTEXT} defaultSize="35%" minSize="10%">
          <ContextZone logId={logId} />
        </Panel>
      </Group>
    </CodeFocusProvider>
  );
}
