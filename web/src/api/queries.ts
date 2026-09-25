import { QueryClient, useQuery } from '@tanstack/react-query';
import { ApiError, api } from './client';

/** Central query keys, so later tasks (T27–T31) invalidate and share cache entries consistently. */
export const queryKeys = {
  datasets: () => ['meta', 'datasets'] as const,
  log: (logId: string) => ['logs', 'detail', logId] as const,
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

export function useLog(logId: string | undefined) {
  return useQuery({
    queryKey: queryKeys.log(logId ?? ''),
    queryFn: ({ signal }) => api.getLog(logId as string, signal),
    enabled: !!logId,
    // Changes only when a dataset is re-ingested, which does not happen while someone browses.
    staleTime: 5 * 60_000,
  });
}
