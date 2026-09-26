import { useCallback, useState } from 'react';

// The open context tab is a per-viewer convenience: kept in localStorage, never required. Every
// access is guarded, because storage can be missing or throw (private windows, blocked storage).

/** Tabs of the context zone: T29 (flow, callers) and T30 (stack trace, neighbors, same request). */
export const CONTEXT_TABS = ['flow', 'callers', 'stack', 'neighbors', 'request'] as const;
export type ContextTab = (typeof CONTEXT_TABS)[number];

export const CONTEXT_TAB_KEY = 'log2code:context-tab';
const DEFAULT_TAB: ContextTab = 'flow';

const isTab = (value: unknown): value is ContextTab =>
  (CONTEXT_TABS as readonly unknown[]).includes(value);

export function readContextTab(): ContextTab {
  try {
    const value = window.localStorage.getItem(CONTEXT_TAB_KEY);
    return isTab(value) ? value : DEFAULT_TAB;
  } catch {
    return DEFAULT_TAB;
  }
}

function writeContextTab(tab: ContextTab) {
  try {
    window.localStorage.setItem(CONTEXT_TAB_KEY, tab);
  } catch {
    // Not remembered; the tab still switches.
  }
}

/** The open tab and a setter for Radix `Tabs` (which reports plain strings). */
export function useContextTab(): [ContextTab, (value: string) => void] {
  const [tab, setTab] = useState(readContextTab);
  const select = useCallback((value: string) => {
    if (!isTab(value)) return;
    setTab(value);
    writeContextTab(value);
  }, []);
  return [tab, select];
}
