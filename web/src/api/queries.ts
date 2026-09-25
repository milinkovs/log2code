import { QueryClient, useInfiniteQuery, useQuery } from '@tanstack/react-query';
import { ApiError, api } from './client';
import type { LogSearchParams } from './types';

/** Page size of the log list; a short page means there is nothing more to load. */
export const LOG_PAGE_SIZE = 100;

/** Central query keys, so later tasks (T27–T31) invalidate and share cache entries consistently. */
export const queryKeys = {
  datasets: () => ['meta', 'datasets'] as const,
  services: (datasetId: string | undefined) => ['meta', 'services', datasetId ?? null] as const,
  log: (logId: string) => ['logs', 'detail', logId] as const,
  logSearch: (params: LogSearchParams) => ['logs', 'search', params] as const,
  logCandidates: (logId: string) => ['logs', 'candidates', logId] as const,
  catalogEntry: (statementId: string) => ['catalog', statementId] as const,
  source: (fileId: string) => ['sources', fileId] as const,
};

export function createQueryClient(): QueryClient {
  return new QueryClient({
    defaultOptions: {
      queries: {
        // 4xx answers (unknown id, bad parameter) will not change on retry; only retry 5xx/network.
        retry: (failureCount, error) =>
          !(error instanceof ApiError && error.status < 500) && failureCount < 2,
        refetchOnWindowFocus: false,
        staleTime: 30_000,
      },
    },
  });
}

export function useDatasets() {
  return useQuery({
    queryKey: queryKeys.datasets(),
    queryFn: ({ signal }) => api.getDatasets(signal),
    staleTime: 5 * 60_000,
  });
}

export function useServices(datasetId: string | undefined) {
  return useQuery({
    queryKey: queryKeys.services(datasetId),
    queryFn: ({ signal }) => api.getServices(datasetId, signal),
    staleTime: 5 * 60_000,
  });
}

export function useLog(logId: string | undefined) {
  return useQuery({
    queryKey: queryKeys.log(logId ?? ''),
    queryFn: ({ signal }) => api.getLog(logId as string, signal),
    enabled: !!logId,
    // Changes only when a dataset is re-ingested, which does not happen while someone browses.
    staleTime: 5 * 60_000,
  });
}

/**
 * The filtered log list, one page per `searchAfter` cursor (infinite scrolling). The cursor is
 * passed through raw; the client encodes it exactly once (ADR-024 point 5).
 */
export function useLogSearch(params: LogSearchParams) {
  return useInfiniteQuery({
    queryKey: queryKeys.logSearch(params),
    queryFn: ({ pageParam, signal }) =>
      api.searchLogs({ ...params, size: LOG_PAGE_SIZE, searchAfter: pageParam }, signal),
    initialPageParam: undefined as string | undefined,
    getNextPageParam: (last) =>
      last.items.length < LOG_PAGE_SIZE || !last.nextSearchAfter ? undefined : last.nextSearchAfter,
  });
}

/** Top candidates of a log's match, joined with the catalog (T24); for the alternatives menu. */
export function useLogCandidates(logId: string | undefined, enabled = true) {
  return useQuery({
    queryKey: queryKeys.logCandidates(logId ?? ''),
    queryFn: ({ signal }) => api.getLogCandidates(logId as string, signal),
    enabled: !!logId && enabled,
    staleTime: 5 * 60_000,
  });
}

/** One catalog statement. A statement id names one version of the code, so it never changes. */
export function useCatalogEntry(statementId: string | null | undefined) {
  return useQuery({
    queryKey: queryKeys.catalogEntry(statementId ?? ''),
    queryFn: ({ signal }) => api.getCatalogEntry(statementId as string, signal),
    enabled: !!statementId,
    staleTime: Infinity,
  });
}

/** A whole source file; immutable for a given file id (the API sends `Cache-Control: immutable`). */
export function useSource(fileId: string | null | undefined) {
  return useQuery({
    queryKey: queryKeys.source(fileId ?? ''),
    queryFn: ({ signal }) => api.getSource(fileId as string, signal),
    enabled: !!fileId,
    staleTime: Infinity,
  });
}
