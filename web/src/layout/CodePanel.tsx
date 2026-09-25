import { Layers } from 'lucide-react';
import { Group, Panel, Separator } from 'react-resizable-panels';
import { CodeZone } from '../code/CodeZone';
import { EmptyState } from '../components/ui';
import { CODE_GROUP, CODE_PANELS } from './panelIds';
import { usePersistentLayout } from './layoutStorage';
import { Zone } from './Zone';

const [VIEWER, CONTEXT] = CODE_PANELS;

/** Left side: source code on top (T28), context tabs below (T29, T30). */
export function CodePanel({ logId }: { logId: string | undefined }) {
  const layout = usePersistentLayout(CODE_GROUP, CODE_PANELS);
  return (
    <Group orientation="vertical" {...layout}>
      <Panel id={VIEWER} defaultSize="65%" minSize="15%">
        <CodeZone logId={logId} />
      </Panel>
      <Separator className="separator" />
      <Panel id={CONTEXT} defaultSize="35%" minSize="10%">
        <Zone title="Context" icon={Layers}>
          <EmptyState
            icon={Layers}
            title="Context"
            description="Conditions, callers, stack trace and logs from the same request appear here."
          />
        </Zone>
      </Panel>
    </Group>
  );
}
