import { useCallback, useMemo } from 'react';
import { useSearchParams } from 'react-router';
import { type LogFilters, applyFilters, clearFilters, parseFilters } from './filters';

/**
 * The filters from the URL plus setters. Updates keep every other query parameter (dataset, sort
 * order) and the path, so the selected log stays selected while the filters change.
 */
export function useLogFilters() {
  const [searchParams, setSearchParams] = useSearchParams();
  const filters = useMemo(() => parseFilters(searchParams), [searchParams]);

  const update = useCallback(
    (patch: Partial<LogFilters>, options?: { replace?: boolean }) =>
      setSearchParams((previous) => applyFilters(previous, patch), options),
    [setSearchParams],
  );
  const clear = useCallback(
    () => setSearchParams((previous) => clearFilters(previous)),
    [setSearchParams],
  );

  return { filters, update, clear };
}
