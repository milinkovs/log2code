// Readable form of `match.score_breakdown` (the scoring components of the matcher, 0.10 step 3).

/** Components in the order of the specification, each with a label for people. */
const COMPONENTS: readonly (readonly [key: string, label: string])[] = [
  ['regex_full', 'Message matches the template'],
  ['regex_prefix', 'Template matches the start of the message'],
  ['specificity', 'Template specificity'],
  ['logger_exact', 'Logger is this class'],
  ['logger_hierarchy', 'Logger is a subclass of this class'],
  ['logger_abbrev_multi', 'Abbreviated logger fits this class'],
  ['logger_conflict', 'Logger does not fit this class'],
  ['level_equal', 'Level matches'],
  ['level_dynamic', 'Level is set at runtime'],
  ['level_conflict', 'Level differs'],
  ['throwable_consistent', 'Exception fits the statement'],
  ['throwable_inconsistent', 'Exception does not fit the statement'],
];

const LABELS = new Map(COMPONENTS);
const ORDER = new Map(COMPONENTS.map(([key], i) => [key, i]));

export interface BreakdownRow {
  key: string;
  label: string;
  points: number;
}

export interface Breakdown {
  rows: BreakdownRow[];
  /** Sum of the components, clamped to 0–1: the score of the statement. */
  score: number;
}

/** Rows in specification order (unknown keys last, by name); zero-point components are kept. */
export function breakdownRows(breakdown: Record<string, number> | null | undefined): Breakdown {
  const rows = Object.entries(breakdown ?? {})
    .filter(([, points]) => Number.isFinite(points))
    .map(([key, points]) => ({ key, label: LABELS.get(key) ?? key.replaceAll('_', ' '), points }))
    .sort(
      (a, b) =>
        (ORDER.get(a.key) ?? COMPONENTS.length) - (ORDER.get(b.key) ?? COMPONENTS.length) ||
        a.key.localeCompare(b.key),
    );
  const sum = rows.reduce((total, row) => total + row.points, 0);
  return { rows, score: Math.min(1, Math.max(0, sum)) };
}

/** `+0.45`, `−0.30`, `0.00`: signed, two decimals, a real minus sign. */
export function formatPoints(points: number): string {
  const rounded = Math.round(points * 100) / 100;
  if (rounded === 0) return '0.00';
  return `${rounded > 0 ? '+' : '−'}${Math.abs(rounded).toFixed(2)}`;
}
