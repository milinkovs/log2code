import { FileCode2, Layers } from 'lucide-react';
import { Group, Panel, Separator } from 'react-resizable-panels';
import { EmptyState } from '../components/ui';
import { CODE_GROUP, CODE_PANELS } from './panelIds';
import { usePersistentLayout } from './layoutStorage';
import { Zone } from './Zone';

const [VIEWER, CONTEXT] = CODE_PANELS;

/** Left side: source code on top (T28), context tabs below (T29, T30). */
export function CodePanel() {
  const layout = usePersistentLayout(CODE_GROUP, CODE_PANELS);
  return (
    <Group orientation="vertical" {...layout}>
      <Panel id={VIEWER} defaultSize="65%" minSize="15%">
        <Zone title="Code" icon={FileCode2}>
          <EmptyState
            icon={FileCode2}
            title="No log selected"
            description="Pick a log on the right to see the code that produced it."
          />
        </Zone>
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
