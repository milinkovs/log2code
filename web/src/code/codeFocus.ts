import { createContext, useContext } from 'react';

// A short "jump to this line and flash it" request from the context tabs to the editor (T29).
// Unlike `?at=` it is not a place of its own: it scrolls within the file of the log statement and
// is gone once the editor has shown it, so it does not live in the URL or in the history.

export interface FocusRequest {
  /** File line (1-based) in the file of the shown statement. */
  line: number;
  /** Distinguishes two clicks on the same line, so the second one flashes again. */
  nonce: number;
}

export interface CodeFocus {
  /** The request the editor has not shown yet, if any. */
  request: FocusRequest | null;
  focusLine: (line: number) => void;
  /** Called by the editor once it has shown `nonce`; clears it so a remount does not repeat it. */
  done: (nonce: number) => void;
}

export const CodeFocusContext = createContext<CodeFocus>({
  request: null,
  focusLine: () => {},
  done: () => {},
});

export const useCodeFocus = () => useContext(CodeFocusContext);
