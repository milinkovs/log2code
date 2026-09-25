import type {
  CallerDto,
  CandidateDetailDto,
  CatalogEntryDto,
  CodeUnitSummary,
  ContextBundleDto,
  DatasetSummary,
  LabelDto,
  LabelListParams,
  LabelRequest,
  LabelSearchResponse,
  LogDetail,
  LogSearchParams,
  LogSearchResponse,
  LogSummary,
  MethodDetailDto,
  NeighborScope,
  NeighborsResponse,
  ProblemDetail,
  ReviewQueueParams,
  SourceFileDto,
  SourceLookupResponse,
  TraceResponse,
} from './types';

/** Every API route lives under /api; Vite proxies it in dev, log2code-api serves it in prod. */
export const API_BASE = '/api';

type QueryValue = string | number | boolean | readonly (string | number | boolean)[] | undefined;
export type Query = Record<string, QueryValue | null>;

/** Non-2xx response; carries the RFC 7807 body when the API sent one. */
export class ApiError extends Error {
  readonly status: number;
  readonly problem: ProblemDetail | undefined;

  constructor(status: number, message: string, problem?: ProblemDetail) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
    this.problem = problem;
  }
}

export function isNotFound(error: unknown): boolean {
  return error instanceof ApiError && error.status === 404;
}

/**
 * Builds `/api<path>?<query>`. Arrays become repeated parameters, `undefined`/`null`/empty strings
 * are dropped. Values are encoded exactly once by URLSearchParams, so opaque values such as the
 * `searchAfter` cursor must be passed raw (docs/api.md, ADR-024 point 5).
 */
export function buildUrl(path: string, query?: Query): string {
  const params = new URLSearchParams();
  for (const [key, value] of Object.entries(query ?? {})) {
    const values = Array.isArray(value) ? value : [value];
    for (const v of values) {
      if (v === undefined || v === null || v === '') continue;
      params.append(key, String(v));
    }
  }
  const qs = params.toString();
  return `${API_BASE}${path}${qs ? `?${qs}` : ''}`;
}

/** Encodes one path segment (ids are hex today, but the client must not rely on that). */
const seg = (value: string) => encodeURIComponent(value);

interface RequestOptions {
  query?: Query;
  method?: 'GET' | 'PUT' | 'DELETE';
  body?: unknown;
  signal?: AbortSignal;
}

async function readProblem(response: Response): Promise<ProblemDetail | undefined> {
  const type = response.headers.get('Content-Type') ?? '';
  if (!type.includes('json')) return undefined;
  try {
    return (await response.json()) as ProblemDetail;
  } catch {
    return undefined;
  }
}

export async function request<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const { query, method = 'GET', body, signal } = options;
  const headers: Record<string, string> = { Accept: 'application/json' };
  if (body !== undefined) headers['Content-Type'] = 'application/json';

  const response = await fetch(buildUrl(path, query), {
    method,
    headers,
    body: body === undefined ? undefined : JSON.stringify(body),
    signal,
  });

  if (!response.ok) {
    const problem = await readProblem(response);
    const message = problem?.detail ?? `${method} ${path} failed with HTTP ${response.status}`;
    throw new ApiError(response.status, message, problem);
  }
  if (response.status === 204) return undefined as T;
  return (await response.json()) as T;
}

// ---- endpoints (docs/api.md) ----

export const api = {
  searchLogs: (params: LogSearchParams = {}, signal?: AbortSignal) =>
    request<LogSearchResponse>('/logs', { query: { ...params }, signal }),

  getLog: (logId: string, signal?: AbortSignal) =>
    request<LogDetail>(`/logs/${seg(logId)}`, { signal }),

  getLogCandidates: (logId: string, signal?: AbortSignal) =>
    request<CandidateDetailDto[]>(`/logs/${seg(logId)}/candidates`, { signal }),

  getLogNeighbors: (
    logId: string,
    params: { before?: number; after?: number; scope?: NeighborScope } = {},
    signal?: AbortSignal,
  ) => request<NeighborsResponse>(`/logs/${seg(logId)}/neighbors`, { query: params, signal }),

  getLogTrace: (logId: string, params: { limit?: number } = {}, signal?: AbortSignal) =>
    request<TraceResponse>(`/logs/${seg(logId)}/trace`, { query: params, signal }),

  getLogContext: (logId: string, params: { neighbors?: number } = {}, signal?: AbortSignal) =>
    request<ContextBundleDto>(`/logs/${seg(logId)}/context`, { query: params, signal }),

  getDatasets: (signal?: AbortSignal) => request<DatasetSummary[]>('/meta/datasets', { signal }),

  getServices: (datasetId?: string, signal?: AbortSignal) =>
    request<string[]>('/meta/services', { query: { datasetId }, signal }),

  getCodeUnits: (signal?: AbortSignal) =>
    request<CodeUnitSummary[]>('/meta/code-units', { signal }),

  getCatalogEntry: (statementId: string, signal?: AbortSignal) =>
    request<CatalogEntryDto>(`/catalog/${seg(statementId)}`, { signal }),

  getSource: (fileId: string, signal?: AbortSignal) =>
    request<SourceFileDto>(`/sources/${seg(fileId)}`, { signal }),

  lookupSource: (
    params: { codeUnit: string; version: string; path: string },
    signal?: AbortSignal,
  ) => request<SourceLookupResponse>('/sources/lookup', { query: { ...params }, signal }),

  getMethod: (methodId: string, signal?: AbortSignal) =>
    request<MethodDetailDto>(`/methods/${seg(methodId)}`, { signal }),

  getMethodCallers: (methodId: string, signal?: AbortSignal) =>
    request<CallerDto[]>(`/methods/${seg(methodId)}/callers`, { signal }),

  putLabel: (logId: string, body: LabelRequest, signal?: AbortSignal) =>
    request<LabelDto>(`/labels/${seg(logId)}`, { method: 'PUT', body, signal }),

  getLabel: (logId: string, signal?: AbortSignal) =>
    request<LabelDto>(`/labels/${seg(logId)}`, { signal }),

  deleteLabel: (logId: string, signal?: AbortSignal) =>
    request<undefined>(`/labels/${seg(logId)}`, { method: 'DELETE', signal }),

  listLabels: (params: LabelListParams = {}, signal?: AbortSignal) =>
    request<LabelSearchResponse>('/labels', { query: { ...params }, signal }),

  getReviewQueue: (params: ReviewQueueParams = {}, signal?: AbortSignal) =>
    request<LogSummary[]>('/review-queue', { query: { ...params }, signal }),
};
