import type { FocusRequest } from './codeFocus';

/** An inclusive range of file lines (1-based). */
export interface LineRange {
  start: number;
  end: number;
}

/** Props of the lazily loaded CodeViewer; kept apart so importing them does not load Monaco. */
export interface CodeViewerProps {
  /** The file content, or a snippet of it. */
  value: string;
  /** Model URI path; one model per file, so switching between files keeps each one's state. */
  path: string;
  /** File line of the first line of `value` (1 for a whole file, `snippetStartLine` otherwise). */
  firstLine?: number;
  /** The log statement (`line`–`end_line`), highlighted and scrolled to the middle. */
  statement: LineRange;
  /** The enclosing method, highlighted faintly. */
  method?: LineRange;
  /** A line to center and flash briefly (a click in the context tabs, T29). */
  focus?: FocusRequest | null;
  /** Called once `focus` has been shown. */
  onFocusDone?: (nonce: number) => void;
}
