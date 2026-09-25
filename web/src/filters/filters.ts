import type { ConfidenceLevel, Level, LogSearchParams, MatchStatus, SortOrder } from '../api/types';

// Every filter lives in the query string under the same name as the `GET /api/logs` parameter
// (ADR-027), so the URL maps almost 1:1 onto LogSearchParams. Multi-valued filters are repeated
// parameters (`?level=ERROR&level=WARN`).

export const LEVELS: readonly Level[] = [
  'TRACE',
  'DEBUG',
  'INFO',
  'WARN',
  'ERROR',
  'FATAL',
  'UNKNOWN',
];
export const STATUSES: readonly MatchStatus[] = ['matched', 'ambiguous', 'unmatched'];
export const CONFIDENCES: readonly ConfidenceLevel[] = ['high', 'medium', 'low'];

export const PARAM = {
  datasetId: 'datasetId',
  service: 'service',
  level: 'level',
  status: 'status',
  confidence: 'confidence',
  q: 'q',
  from: 'from',
  to: 'to',
  traceId: 'traceId',
  hasException: 'hasException',
  order: 'order',
} as const;

/** The filters the user can set from the filter bar (dataset and sort order are separate). */
export interface LogFilters {
  service: string[];
  level: Level[];
  status: MatchStatus[];
  confidence: ConfidenceLevel[];
  q: string;
  /** ISO-8601 instants (UTC), inclusive; empty when not set. */
  from: string;
  to: string;
  traceId: string;
  /** true = only logs with an exception; the API parameter is omitted otherwise. */
  hasException: boolean;
}

export const EMPTY_FILTERS: LogFilters = {
  service: [],
  level: [],
  status: [],
  confidence: [],
  q: '',
  from: '',
  to: '',
  traceId: '',
  hasException: false,
};

/** Filter keys that `Clear` resets; `datasetId` and `order` are context, not filters. */
export const FILTER_KEYS = Object.keys(EMPTY_FILTERS) as (keyof LogFilters)[];

const unique = <T>(values: T[]) => [...new Set(values)];

function enumValues<T extends string>(params: URLSearchParams, key: string, allowed: readonly T[]) {
  return unique(
    params.getAll(key).filter((v): v is T => (allowed as readonly string[]).includes(v)),
  );
}

const text = (params: URLSearchParams, key: string) => params.get(key)?.trim() ?? '';

/** Reads the filters from the URL; unknown enum values and malformed instants are ignored. */
export function parseFilters(params: URLSearchParams): LogFilters {
  const instant = (key: string) => {
    const value = text(params, key);
    return value && !Number.isNaN(Date.parse(value)) ? value : '';
  };
  return {
    service: unique(params.getAll(PARAM.service).filter(Boolean)),
    level: enumValues(params, PARAM.level, LEVELS),
    status: enumValues(params, PARAM.status, STATUSES),
    confidence: enumValues(params, PARAM.confidence, CONFIDENCES),
    q: params.get(PARAM.q) ?? '',
    from: instant(PARAM.from),
    to: instant(PARAM.to),
    traceId: text(params, PARAM.traceId),
    hasException: params.get(PARAM.hasException) === 'true',
  };
}

/** Writes `patch` into a copy of `params`, dropping empty values; other parameters are kept. */
export function applyFilters(params: URLSearchParams, patch: Partial<LogFilters>): URLSearchParams {
  const next = new URLSearchParams(params);
  for (const [key, value] of Object.entries(patch) as [keyof LogFilters, unknown][]) {
    next.delete(key);
    if (Array.isArray(value)) value.forEach((v) => next.append(key, String(v)));
    else if (value === true) next.set(key, 'true');
    else if (typeof value === 'string' && value.trim() !== '') next.set(key, value);
  }
  return next;
}

/** Removes every filter but keeps the dataset and the sort order. */
export function clearFilters(params: URLSearchParams): URLSearchParams {
  const next = new URLSearchParams(params);
  FILTER_KEYS.forEach((key) => next.delete(key));
  return next;
}

export function hasActiveFilters(filters: LogFilters): boolean {
  return FILTER_KEYS.some((key) => {
    const value = filters[key];
    return Array.isArray(value) ? value.length > 0 : Boolean(value);
  });
}

export function parseOrder(params: URLSearchParams): SortOrder {
  return params.get(PARAM.order) === 'asc' ? 'asc' : 'desc';
}

/** Search parameters for `GET /api/logs` from the current URL (without paging). */
export function toSearchParams(params: URLSearchParams): LogSearchParams {
  const f = parseFilters(params);
  const trimmedQ = f.q.trim();
  return {
    datasetId: params.get(PARAM.datasetId) ?? undefined,
    service: f.service.length ? f.service : undefined,
    level: f.level.length ? f.level : undefined,
    status: f.status.length ? f.status : undefined,
    confidence: f.confidence.length ? f.confidence : undefined,
    q: trimmedQ || undefined,
    from: f.from || undefined,
    to: f.to || undefined,
    traceId: f.traceId || undefined,
    hasException: f.hasException || undefined,
    order: parseOrder(params),
  };
}

/**
 * Names of the active filters the given log does NOT satisfy (empty = it matches). The text
 * search `q` is not checked: the API uses simple_query_string, which the browser cannot reproduce
 * faithfully, so an unknown result must not raise a false warning (ADR-029 addendum).
 */
export function filterMismatches(
  log: {
    datasetId: string | null;
    service: string | null;
    level: string | null;
    timestamp: string | null;
    traceId: string | null;
    exception: unknown;
    match: { status: MatchStatus | null; confidenceLevel: ConfidenceLevel | null } | null;
  },
  params: URLSearchParams,
): string[] {
  const f = parseFilters(params);
  const out: string[] = [];
  const datasetId = params.get(PARAM.datasetId);
  const time = log.timestamp ? Date.parse(log.timestamp) : Number.NaN;
  if (datasetId && log.datasetId !== datasetId) out.push('dataset');
  if (f.service.length && !f.service.includes(log.service ?? '')) out.push('service');
  if (f.level.length && !(f.level as string[]).includes(log.level ?? '')) out.push('level');
  if (f.status.length && !(f.status as string[]).includes(log.match?.status ?? ''))
    out.push('status');
  if (f.confidence.length && !(f.confidence as string[]).includes(log.match?.confidenceLevel ?? ''))
    out.push('confidence');
  if ((f.from && !(time >= Date.parse(f.from))) || (f.to && !(time <= Date.parse(f.to))))
    out.push('time');
  if (f.traceId && log.traceId !== f.traceId) out.push('trace');
  if (f.hasException && !log.exception) out.push('exceptions');
  return out;
}
