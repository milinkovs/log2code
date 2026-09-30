import { useCallback, useState } from 'react';
import type { ExplainLevel } from '../../api/types';

// Chosen level and model are per-viewer conveniences, like the other context tab settings: kept in
// localStorage, never required. Every access is guarded (private windows, blocked storage).

export const EXPLAIN_LEVEL_KEY = 'log2code:explain-level';
export const EXPLAIN_MODEL_KEY = 'log2code:explain-model';
export const DEFAULT_LEVEL: ExplainLevel = 'L2';

/** Levels of 10.1, with the short description the level menu shows. */
export const EXPLAIN_LEVELS: { value: ExplainLevel; description: string }[] = [
  { value: 'L0', description: 'Log only' },
  { value: 'L1', description: '+ method source' },
  { value: 'L2', description: '+ conditions and stack trace code' },
  { value: 'L3', description: '+ direct callers' },
  { value: 'L4', description: '+ callers up to 3 levels and neighbor logs' },
];

const isLevel = (value: unknown): value is ExplainLevel =>
  EXPLAIN_LEVELS.some((level) => level.value === value);

function read(key: string): string | null {
  try {
    return window.localStorage.getItem(key);
  } catch {
    return null;
  }
}

function write(key: string, value: string) {
  try {
    window.localStorage.setItem(key, value);
  } catch {
    // Not remembered; the choice still applies until the page is left.
  }
}

/** The remembered level, L2 when there is none (D7). */
export function useExplainLevel(): [ExplainLevel, (level: ExplainLevel) => void] {
  const [level, setLevel] = useState<ExplainLevel>(() => {
    const stored = read(EXPLAIN_LEVEL_KEY);
    return isLevel(stored) ? stored : DEFAULT_LEVEL;
  });
  const select = useCallback((value: ExplainLevel) => {
    setLevel(value);
    write(EXPLAIN_LEVEL_KEY, value);
  }, []);
  return [level, select];
}

/**
 * The remembered model id, or null when none was chosen. The caller falls back to the API's
 * `defaultModel` when the value is null or no longer in the configured list.
 */
export function useExplainModel(): [string | null, (model: string) => void] {
  const [model, setModel] = useState<string | null>(() => read(EXPLAIN_MODEL_KEY));
  const select = useCallback((value: string) => {
    setModel(value);
    write(EXPLAIN_MODEL_KEY, value);
  }, []);
  return [model, select];
}
