import { useCallback } from 'react';
import { useNavigate, useSearchParams } from 'react-router';
import { withoutAlternative } from '../code/alternative';

/**
 * Selects a log for the whole app: navigates to `/logs/:id`, keeping the filters and the dataset.
 * An alternative statement (`?alt=`, T28) and a location opened from the context tabs (`?at=`,
 * T29) belong to the previously selected log, so they are dropped. `replace` swaps the history
 * entry (↑/↓ in the list) instead of adding one (a click).
 */
export function useSelectLog() {
  const [searchParams] = useSearchParams();
  const navigate = useNavigate();
  return useCallback(
    (logId: string, options: { replace?: boolean } = {}) => {
      const search = withoutAlternative(searchParams).toString();
      navigate(
        { pathname: `/logs/${encodeURIComponent(logId)}`, search: search ? `?${search}` : '' },
        { replace: options.replace ?? false },
      );
    },
    [searchParams, navigate],
  );
}
