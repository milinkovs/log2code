import { ChevronRight, Library, ListTree } from 'lucide-react';
import { useState, type CSSProperties } from 'react';
import { isNotFound } from '../api/client';
import { useMethod, useMethodCallers } from '../api/queries';
import type { CatalogEntryDto } from '../api/types';
import { memberLabel } from '../code/labels';
import { useCodeLocation } from '../code/codeLocation';
import { Badge, Button, Callout, EmptyState, Spinner, cx } from '../components/ui';
import { useJumpToLine, useOpenLocation } from './navigation';

const errorText = (error: unknown) => (error instanceof Error ? error.message : String(error));

/** A REST endpoint: Spring's `@GetMapping`, `@PostMapping`, `@RequestMapping`, … */
const isRestEntry = (annotations: string[]) => annotations.some((a) => a.endsWith('Mapping'));

interface TreeNode {
  methodId: string;
  classFqn: string;
  methodName: string;
  callerCount: number;
  annotations: string[];
  /** The call in the caller: file and line. Absent for the root (the statement's own method). */
  call?: { fileId: string; line: number };
}

/**
 * "Callers" (T29): who calls the method that contains the statement, as a tree over the project's
 * call graph (T13). The root is expanded when the tab opens; deeper levels load on ▸ (one
 * `/methods/{id}/callers` request per node). Library code has no call graph.
 */
export function CallersTab({ entry }: { entry: CatalogEntryDto }) {
  if (entry.codeUnit.type !== 'project' || !entry.methodId) {
    return (
      <EmptyState
        icon={Library}
        title="No call graph for library code"
        description="The call graph is not available for library code. It covers the project's own methods."
      />
    );
  }
  return <ProjectCallers entry={entry} methodId={entry.methodId} />;
}

function ProjectCallers({ entry, methodId }: { entry: CatalogEntryDto; methodId: string }) {
  const method = useMethod(methodId);
  const jump = useJumpToLine();

  if (method.isPending) return <Spinner label="Loading callers…" />;
  if (method.isError) {
    return isNotFound(method.error) ? (
      <EmptyState
        icon={ListTree}
        title="No call graph for this method"
        description="Run the analyzer with the resolved dependencies to build the call graph."
      />
    ) : (
      <Callout tone="danger">
        Could not load the method: {errorText(method.error)}
        <Button size="sm" className="callout__action" onClick={() => void method.refetch()}>
          Retry
        </Button>
      </Callout>
    );
  }

  const root: TreeNode = {
    methodId,
    classFqn: method.data.classFqn,
    methodName: method.data.methodName,
    callerCount: method.data.callerCount,
    annotations: method.data.annotations,
  };
  return (
    <ul className="tree" aria-label="Callers">
      <CallerNode
        node={root}
        depth={0}
        ancestors={[]}
        initiallyOpen
        onOpenRoot={() => jump(entry.methodStartLine)}
      />
    </ul>
  );
}

function CallerNode({
  node,
  depth,
  ancestors,
  initiallyOpen = false,
  onOpenRoot,
}: {
  node: TreeNode;
  depth: number;
  /** Methods on the path from the root, to stop at recursion. */
  ancestors: string[];
  initiallyOpen?: boolean;
  onOpenRoot?: () => void;
}) {
  const [open, setOpen] = useState(initiallyOpen);
  const [location] = useCodeLocation();
  const openLocation = useOpenLocation();

  const recursive = ancestors.includes(node.methodId);
  const expandable = node.callerCount > 0 && !recursive;
  const callers = useMethodCallers(node.methodId, open && expandable);

  const label = memberLabel(node.classFqn, node.methodName);
  const call = node.call;
  const current =
    call !== undefined && location?.fileId === call.fileId && location.line === call.line;
  const openNode = () => (call ? openLocation(call) : onOpenRoot?.());

  return (
    <li>
      <div
        className={cx('tree-row', current && 'tree-row--current')}
        style={{ '--depth': depth } as CSSProperties}
      >
        {expandable ? (
          <button
            type="button"
            className="tree-row__toggle"
            aria-expanded={open}
            aria-label={`${open ? 'Collapse' : 'Expand'} callers of ${label}`}
            onClick={() => setOpen(!open)}
          >
            <ChevronRight size={14} aria-hidden="true" />
          </button>
        ) : (
          <span className="tree-row__toggle" aria-hidden="true" />
        )}
        <button
          type="button"
          className="tree-row__main"
          aria-current={current ? 'location' : undefined}
          title={`${node.classFqn}#${node.methodName}`}
          onClick={openNode}
        >
          <span className="tree-row__name mono">{label}</span>
          {call && <span className="tree-row__line mono">:{call.line}</span>}
        </button>
        {recursive ? (
          <Badge>recursive</Badge>
        ) : node.callerCount === 0 && isRestEntry(node.annotations) ? (
          <Badge>REST entry</Badge>
        ) : node.callerCount === 0 ? (
          <span className="tree-row__note">no callers in the project</span>
        ) : (
          <span className="tree-row__note">
            {node.callerCount} {node.callerCount === 1 ? 'caller' : 'callers'}
          </span>
        )}
      </div>
      {open && expandable && (
        <CallerChildren
          query={callers}
          depth={depth + 1}
          ancestors={[...ancestors, node.methodId]}
        />
      )}
    </li>
  );
}

function CallerChildren({
  query,
  depth,
  ancestors,
}: {
  query: ReturnType<typeof useMethodCallers>;
  depth: number;
  ancestors: string[];
}) {
  const indent = { '--depth': depth } as CSSProperties;
  if (query.isPending) {
    return (
      <div className="tree-row tree-row--status" style={indent}>
        <Spinner label="Loading callers…" />
      </div>
    );
  }
  if (query.isError) {
    return (
      <div className="tree-row tree-row--status" style={indent}>
        <Callout tone="danger">
          Could not load the callers: {errorText(query.error)}
          <Button size="sm" className="callout__action" onClick={() => void query.refetch()}>
            Retry
          </Button>
        </Callout>
      </div>
    );
  }
  return (
    <ul className="tree__children">
      {query.data.map((caller) => (
        <CallerNode
          key={`${caller.methodId}-${caller.line}`}
          node={{ ...caller, call: { fileId: caller.fileId, line: caller.line } }}
          depth={depth}
          ancestors={ancestors}
        />
      ))}
    </ul>
  );
}
