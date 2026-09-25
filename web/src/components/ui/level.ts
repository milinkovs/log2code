import type { Level } from '../../api/types';

const KNOWN: readonly string[] = ['TRACE', 'DEBUG', 'INFO', 'WARN', 'ERROR', 'FATAL', 'UNKNOWN'];

/** Normalizes a level string from the API (may be null or unexpected) to a known Level. */
export function asLevel(level: string | null | undefined): Level {
  return level && KNOWN.includes(level) ? (level as Level) : 'UNKNOWN';
}
