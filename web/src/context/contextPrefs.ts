import { useCallback, useState } from 'react';
import type { NeighborScope } from '../api/types';

// Per-viewer choices of the T30 tabs, kept in localStorage like the open tab (contextTab.ts).
// Never required: every access is guarded, and an unknown or unreadable value means the default.

function readChoice<T>(key: string, allowed: readonly T[], fallback: T): T {
  try {
    const raw = window.localStorage.getItem(key);
    const value = allowed.find((option) => String(option) === raw);
    return value ?? fallback;
  } catch {
    return fallback;
  }
}

function writeChoice(key: string, value: unknown) {
  try {
    window.localStorage.setItem(key, String(value));
  } catch {
    // Not remembered; the choice still applies until the page is reloaded.
  }
}

function useStoredChoice<T>(
  key: string,
  allowed: readonly T[],
  fallback: T,
): [T, (value: T) => void] {
  const [value, setValue] = useState(() => readChoice(key, allowed, fallback));
  const set = useCallback(
    (next: T) => {
      if (!allowed.includes(next)) return;
      setValue(next);
      writeChoice(key, next);
    },
    [key, allowed],
  );
  return [value, set];
}

export const NEIGHBOR_SCOPES = ['service', 'thread', 'dataset'] as const satisfies NeighborScope[];
/** Logs shown on each side of the current one (before and after). */
export const NEIGHBOR_COUNTS = [10, 20, 50] as const;
export type NeighborCount = (typeof NEIGHBOR_COUNTS)[number];

export const NEIGHBOR_SCOPE_KEY = 'log2code:neighbors-scope';
export const NEIGHBOR_COUNT_KEY = 'log2code:neighbors-count';
export const HIDE_LIBRARY_KEY = 'log2code:stack-hide-library';

/** Scope and count of the "Neighbors" tab; "Same request" can switch the scope to the thread. */
export function useNeighborSettings() {
  const [scope, setScope] = useStoredChoice<NeighborScope>(
    NEIGHBOR_SCOPE_KEY,
    NEIGHBOR_SCOPES,
    'service',
  );
  const [count, setCount] = useStoredChoice<NeighborCount>(NEIGHBOR_COUNT_KEY, NEIGHBOR_COUNTS, 10);
  return { scope, setScope, count, setCount };
}

const BOOLEANS = [true, false] as const;

/** "Hide library frames" of the stack trace tab; on by default (decided with the user, ADR-032). */
export function useHideLibraryFrames() {
  return useStoredChoice<boolean>(HIDE_LIBRARY_KEY, BOOLEANS, true);
}
