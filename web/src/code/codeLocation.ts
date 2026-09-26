import { useCallback } from 'react';
import { useSearchParams } from 'react-router';

// A place in another file, opened from the context tabs (a caller, the target of a call), lives in
// the URL as `?at=<fileId>:<line>` (ADR-031), like `?alt=` (ADR-030): a refresh or a shared link
// shows the same code, browser Back steps back through the places visited, and "Back to log"
// drops it. It belongs to the selected log, so selecting another log drops it too.

export const AT_PARAM = 'at';

/** A line in a stored source file (`log2code-sources`). */
export interface CodeLocationRef {
  fileId: string;
  line: number;
}

export function formatLocation({ fileId, line }: CodeLocationRef): string {
  return `${fileId}:${line}`;
}

/** `fileId:line` with a positive line, otherwise null (a malformed link shows the log instead). */
export function parseLocation(value: string | null): CodeLocationRef | null {
  const match = value?.trim().match(/^([^:\s]+):(\d+)$/);
  if (!match) return null;
  const line = Number(match[2]);
  return line > 0 ? { fileId: match[1], line } : null;
}

/** The opened location (or null) and a setter; `null` returns the editor to the log statement. */
export function useCodeLocation(): [
  CodeLocationRef | null,
  (location: CodeLocationRef | null) => void,
] {
  const [searchParams, setSearchParams] = useSearchParams();
  const location = parseLocation(searchParams.get(AT_PARAM));
  const setLocation = useCallback(
    (next: CodeLocationRef | null) =>
      setSearchParams((previous) => {
        const params = new URLSearchParams(previous);
        params.delete(AT_PARAM);
        if (next) params.set(AT_PARAM, formatLocation(next));
        return params;
      }),
    [setSearchParams],
  );
  return [location, setLocation];
}
