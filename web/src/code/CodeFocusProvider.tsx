import { useCallback, useMemo, useState, type ReactNode } from 'react';
import { CodeFocusContext, type FocusRequest } from './codeFocus';

/** Holds the pending focus request of the code panel; a new log drops the request of the old one. */
export function CodeFocusProvider({
  logId,
  children,
}: {
  logId: string | undefined;
  children: ReactNode;
}) {
  const [request, setRequest] = useState<FocusRequest | null>(null);
  const [requestLogId, setRequestLogId] = useState(logId);
  if (requestLogId !== logId) {
    setRequestLogId(logId);
    setRequest(null);
  }

  const focusLine = useCallback(
    (line: number) => setRequest((previous) => ({ line, nonce: (previous?.nonce ?? 0) + 1 })),
    [],
  );
  const done = useCallback(
    (nonce: number) => setRequest((previous) => (previous?.nonce === nonce ? null : previous)),
    [],
  );
  const value = useMemo(() => ({ request, focusLine, done }), [request, focusLine, done]);
  return <CodeFocusContext.Provider value={value}>{children}</CodeFocusContext.Provider>;
}
