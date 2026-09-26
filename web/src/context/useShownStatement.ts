import { useCatalogEntry, useLog } from '../api/queries';
import { useAlternative } from '../code/alternative';

/**
 * The statement the code zone shows for a log: the alternative from `?alt=` or the top match
 * (ADR-030). The context tabs follow it. Both queries are shared with the code zone through the
 * React Query cache, so this makes no request of its own.
 */
export function useShownStatement(logId: string | undefined) {
  const log = useLog(logId);
  const [alternative] = useAlternative();
  const shownId = alternative ?? log.data?.match?.statementId ?? null;
  const entry = useCatalogEntry(shownId);
  return { log, shownId, entry };
}
