import { useQueryClient } from '@tanstack/react-query';
import { useCallback, useState } from 'react';
import { methodQuery } from '../api/queries';
import { useCodeFocus } from '../code/codeFocus';
import { useCodeLocation, type CodeLocationRef } from '../code/codeLocation';

// Two kinds of clicks in the context tabs (T29, ADR-031):
// - a line of the shown statement's own method (a condition, a preceding statement): the editor
//   returns to the statement's file if it had left it, centers the line and flashes it;
// - another method (a caller, the project method a call goes to): the editor opens that file at
//   that line as a place of its own (`?at=`), with "Back to log" in the code zone.

/** Jump to a line in the file of the shown statement. */
export function useJumpToLine() {
  const [location, setLocation] = useCodeLocation();
  const { focusLine } = useCodeFocus();
  return useCallback(
    (line: number) => {
      if (location) setLocation(null);
      focusLine(line);
    },
    [location, setLocation, focusLine],
  );
}

/** Open a line in another file (`?at=`). */
export function useOpenLocation() {
  const [, setLocation] = useCodeLocation();
  return useCallback((location: CodeLocationRef) => setLocation(location), [setLocation]);
}

/**
 * Open a project method by id at its first line; the method (file and lines) is fetched first,
 * through the shared cache. The error, if any, is kept for the tab to show.
 */
export function useOpenMethod() {
  const queryClient = useQueryClient();
  const open = useOpenLocation();
  const [error, setError] = useState<string | null>(null);
  const openMethod = useCallback(
    async (methodId: string) => {
      setError(null);
      try {
        const method = await queryClient.fetchQuery(methodQuery(methodId));
        open({ fileId: method.fileId, line: method.startLine });
      } catch (e) {
        setError(e instanceof Error ? e.message : String(e));
      }
    },
    [queryClient, open],
  );
  return { openMethod, error };
}
