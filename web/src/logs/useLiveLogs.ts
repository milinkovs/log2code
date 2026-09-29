import { type InfiniteData, useQueryClient } from '@tanstack/react-query';
import { useCallback, useEffect, useMemo, useState } from 'react';
import { queryKeys, useLogHead } from '../api/queries';
import type { LogSearchParams, LogSearchResponse, LogSummary } from '../api/types';

// "Live" list (decided with the user after T32, variant A): while on and the list is newest
// first, the newest page is polled; at the top of the list it replaces what is shown, scrolled
// down it only offers "N new logs", so the rows never move under the reader.

export const LIVE_KEY = 'log2code:live';

function readLive(): boolean {
  try {
    return window.localStorage.getItem(LIVE_KEY) !== 'false';
  } catch {
    return true;
  }
}

/** Whether "Live" is on; a per-viewer choice kept in localStorage, on by default. */
export function useLivePreference(): [boolean, (live: boolean) => void] {
  const [live, setLiveState] = useState(readLive);
  const setLive = useCallback((next: boolean) => {
    setLiveState(next);
    try {
      window.localStorage.setItem(LIVE_KEY, String(next));
    } catch {
      // Not remembered; the choice still applies until the page is reloaded.
    }
  }, []);
  return [live, setLive];
}

type LogPages = InfiniteData<LogSearchResponse, string | undefined>;

/** True when the list starts with exactly the polled page (nothing new, nothing removed). */
function startsWith(items: LogSummary[], head: LogSummary[]): boolean {
  if (items.length < head.length) return false;
  return head.every((item, i) => items[i].logId === item.logId);
}

interface LiveLogsOptions {
  params: LogSearchParams;
  /** What the list shows now (all loaded pages). */
  items: LogSummary[];
  /** The list's first page once it has loaded; polling starts from it. */
  firstPage: LogSearchResponse | undefined;
  /** When the list was last fetched (React Query's dataUpdatedAt). */
  updatedAt: number;
  /** The list is scrolled to (or near) its first row. */
  atTop: boolean;
  live: boolean;
}

export function useLiveLogs({ params, items, firstPage, updatedAt, atTop, live }: LiveLogsOptions) {
  const queryClient = useQueryClient();
  const enabled = live && params.order === 'desc' && !!firstPage;
  const head = useLogHead(params, enabled, firstPage && { page: firstPage, updatedAt });
  // Never show a poll that is older than the list itself (e.g. right after the list refetched).
  const headPage = enabled && head.dataUpdatedAt >= updatedAt ? head.data : undefined;

  const newCount = useMemo(() => {
    if (!headPage) return 0;
    const loaded = new Set(items.map((item) => item.logId));
    return headPage.items.filter((item) => !loaded.has(item.logId)).length;
  }, [headPage, items]);
  const changed = !!headPage && !startsWith(items, headPage.items);

  /**
   * Shows the polled page as the whole list. Older pages are dropped and load again on scroll;
   * this also handles logs that were deleted (the live dataset is reset when PetClinic starts).
   */
  const showNew = useCallback(() => {
    if (!headPage) return;
    queryClient.setQueryData<LogPages>(queryKeys.logSearch(params), {
      pages: [headPage],
      pageParams: [undefined],
    });
    // The dataset menu shows counts per dataset.
    void queryClient.invalidateQueries({ queryKey: queryKeys.datasets() });
  }, [headPage, params, queryClient]);

  useEffect(() => {
    if (atTop && changed) showNew();
  }, [atTop, changed, showNew]);

  return { newCount, showNew };
}
