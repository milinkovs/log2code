import type { ExplainSection } from '../../api/types';

/** Names of the prompt sections (T41 ids), as the tab shows them. */
const SECTION_LABELS: Record<string, string> = {
  log: 'log',
  exception: 'exception',
  statement: 'statement',
  method: 'method source',
  flow: 'conditions and flow',
  stackCode: 'stack trace code',
  callers: 'callers',
  neighbors: 'neighbor logs',
};

/** Why a section is left out or cut (docs/llm-explain.md). The set is open: unknown ones show as is. */
const REASON_LABELS: Record<string, string> = {
  level: 'not in this level',
  unmatched: 'the log is not matched',
  library: 'library code has no call graph',
  noException: 'the log has no exception',
  noCallers: 'no callers in the project',
  noControl: 'no conditions or flow before the log',
  noProjectFrames: 'no project frames in the stack trace',
  noNeighbors: 'no neighbor logs',
  unavailable: 'the source is not available',
  truncated: 'cut to fit the prompt limit',
};

export const sectionLabel = (id: string) => SECTION_LABELS[id] ?? id;

export const reasonLabel = (reason: string | null) =>
  reason === null ? '' : (REASON_LABELS[reason] ?? reason);

export const includedSections = (sections: ExplainSection[]) =>
  sections.filter((section) => section.included);
