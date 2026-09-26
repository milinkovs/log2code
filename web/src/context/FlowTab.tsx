import { ArrowUpRight, GitBranch } from 'lucide-react';
import type { CSSProperties, ReactNode } from 'react';
import type { CatalogEntryDto } from '../api/types';
import { Badge, Callout, EmptyState } from '../components/ui';
import { useJumpToLine, useOpenMethod } from './navigation';

/** Snake-case kinds from the analyzer (`var_decl`, `switch_case`) as short labels. */
const kindLabel = (kind: string) => kind.replaceAll('_', ' ');

/**
 * "Conditions and flow" (T29): the control context of the statement (level 2, T11), from the
 * catalog entry already loaded for the code zone, so the tab makes no request. Four groups, as
 * the analyzer stores them: enclosing conditions (outer to inner), earlier exits, preceding
 * statements (nearest first) and the calls in them. A click jumps to the line in the editor.
 */
export function FlowTab({ entry }: { entry: CatalogEntryDto }) {
  const jump = useJumpToLine();
  const { openMethod, error } = useOpenMethod();
  const control = entry.control;

  if (!control) {
    return (
      <EmptyState
        icon={GitBranch}
        title="No control flow for this statement"
        description="The catalog has no conditions or preceding statements for it. Re-run the analyzer to add them."
      />
    );
  }

  return (
    <div className="flow">
      {error && <Callout tone="danger">Could not open the method: {error}</Callout>}

      <FlowSection
        title="Conditions"
        empty="The statement is not inside a condition, loop, or handler."
        count={control.conditions.length}
      >
        {control.conditions.map((condition, depth) => (
          <FlowRow
            key={`${condition.line}-${depth}`}
            line={condition.line}
            depth={depth}
            onClick={() => jump(condition.line)}
          >
            <Badge>{kindLabel(condition.kind)}</Badge>
            {condition.text ? (
              <span className="flow-row__text mono" title={condition.text}>
                {condition.negated && <span className="flow-row__not">not: </span>}
                {condition.text}
              </span>
            ) : null}
          </FlowRow>
        ))}
      </FlowSection>

      <FlowSection
        title="Reached only if not"
        empty="No earlier return, throw, break, or continue in the way."
        count={control.earlyExits.length}
      >
        {control.earlyExits.map((exit) => (
          <FlowRow
            key={`${exit.line}-${exit.text}`}
            line={exit.line}
            onClick={() => jump(exit.line)}
          >
            <span className="flow-row__text mono" title={exit.text}>
              {exit.text}
            </span>
            <Badge>{exit.exitKind}</Badge>
          </FlowRow>
        ))}
      </FlowSection>

      <FlowSection
        title="Preceding statements"
        hint="nearest first"
        empty="The statement is the first one in its block."
        count={control.preceding.length}
      >
        {control.preceding.map((statement) => (
          <FlowRow
            key={`${statement.line}-${statement.text}`}
            line={statement.line}
            onClick={() => jump(statement.line)}
          >
            <Badge>{kindLabel(statement.kind)}</Badge>
            <span className="flow-row__text mono" title={statement.text}>
              {statement.text}
            </span>
          </FlowRow>
        ))}
      </FlowSection>

      <FlowSection
        title="Calls before"
        empty="No method is called before the statement."
        count={control.callsBefore.length}
      >
        {control.callsBefore.map((call, i) => {
          const target = call.targetMethodId;
          const name = call.target ?? call.text;
          return target ? (
            <FlowRow
              key={`${call.line}-${i}`}
              line={call.line}
              label={`Open ${name}`}
              onClick={() => void openMethod(target)}
            >
              <span className="flow-row__text flow-row__text--link mono" title={call.text}>
                {name}
              </span>
              <ArrowUpRight size={14} className="flow-row__open" aria-hidden="true" />
            </FlowRow>
          ) : (
            <FlowRow key={`${call.line}-${i}`} line={call.line} onClick={() => jump(call.line)}>
              <span className="flow-row__text mono" title={call.text}>
                {name}
              </span>
            </FlowRow>
          );
        })}
      </FlowSection>
    </div>
  );
}

function FlowSection({
  title,
  hint,
  empty,
  count,
  children,
}: {
  title: string;
  hint?: string;
  empty: string;
  count: number;
  children: ReactNode;
}) {
  return (
    <section className="flow-section" aria-label={title}>
      <h3 className="flow-section__title">
        {title}
        {hint && count > 0 && <span className="flow-section__hint"> · {hint}</span>}
      </h3>
      {count === 0 ? (
        <p className="flow-section__empty">{empty}</p>
      ) : (
        <ul className="flow-section__list">{children}</ul>
      )}
    </section>
  );
}

/** One clickable line: line number, then the content; `depth` indents nested conditions. */
function FlowRow({
  line,
  depth = 0,
  label,
  onClick,
  children,
}: {
  line: number;
  depth?: number;
  /** Accessible name when the click does more than jump to the line. */
  label?: string;
  onClick: () => void;
  children: ReactNode;
}) {
  return (
    <li>
      <button
        type="button"
        className="flow-row"
        style={{ '--depth': depth } as CSSProperties}
        aria-label={label}
        onClick={onClick}
      >
        <span className="flow-row__line mono">{line}</span>
        {children}
      </button>
    </li>
  );
}
