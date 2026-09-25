import { useCallback } from 'react';
import { useSearchParams } from 'react-router';

// The statement shown instead of the top match lives in the URL as `?alt=<statementId>` (ADR-030):
// a refresh or a shared link shows the same code, and Back returns to the top match. It belongs to
// the selected log, so selecting another log drops it; clearing the filters keeps it.

export const ALT_PARAM = 'alt';

/** `params` without the alternative; used when another log gets selected. */
export function withoutAlternative(params: URLSearchParams): URLSearchParams {
  const next = new URLSearchParams(params);
  next.delete(ALT_PARAM);
  return next;
}

/** The alternative from the URL (or null) and a setter; `null` returns to the top match. */
export function useAlternative(): [string | null, (statementId: string | null) => void] {
  const [searchParams, setSearchParams] = useSearchParams();
  const alternative = searchParams.get(ALT_PARAM)?.trim() || null;
  const setAlternative = useCallback(
    (statementId: string | null) =>
      setSearchParams((previous) => {
        const next = withoutAlternative(previous);
        if (statementId) next.set(ALT_PARAM, statementId);
        return next;
      }),
    [setSearchParams],
  );
  return [alternative, setAlternative];
}
