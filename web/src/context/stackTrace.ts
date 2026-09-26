import type { StackFrameDto } from '../api/types';

// Pure helpers of the "Stack trace" tab (T30).

/** A frame to show, or a run of consecutive library frames folded into one row. */
export type FrameItem =
  | { kind: 'frame'; frame: StackFrameDto; index: number }
  | { kind: 'folded'; start: number; frames: StackFrameDto[] };

/**
 * The rows of one frame list. With `hideLibrary`, every run of two or more consecutive library
 * frames becomes one folded row (a single library frame stays, a fold would take as much room);
 * runs whose start index is in `expanded` are shown frame by frame. Without it, every frame shows.
 */
export function frameItems(
  frames: StackFrameDto[],
  hideLibrary: boolean,
  expanded: ReadonlySet<number> = new Set(),
): FrameItem[] {
  const items: FrameItem[] = [];
  let i = 0;
  while (i < frames.length) {
    if (!hideLibrary || frames[i].inProject) {
      items.push({ kind: 'frame', frame: frames[i], index: i });
      i += 1;
      continue;
    }
    let end = i;
    while (end < frames.length && !frames[end].inProject) end += 1;
    if (end - i >= 2 && !expanded.has(i)) {
      items.push({ kind: 'folded', start: i, frames: frames.slice(i, end) });
    } else {
      for (let j = i; j < end; j += 1) items.push({ kind: 'frame', frame: frames[j], index: j });
    }
    i = end;
  }
  return items;
}

/** `Class.method(File.java:12)`, as Java prints a frame; the file part degrades like Java's. */
export function frameText(frame: StackFrameDto): string {
  const where = frame.file
    ? frame.line !== null && frame.line > 0
      ? `${frame.file}:${frame.line}`
      : frame.file
    : 'Unknown Source';
  return `${frame.className ?? '?'}.${frame.method ?? '?'}(${where})`;
}

/** `artifactId` of a `groupId:artifactId` code unit, for a short label next to a library frame. */
export function artifactOf(codeUnit: string | null): string | null {
  if (!codeUnit) return null;
  const colon = codeUnit.lastIndexOf(':');
  return colon >= 0 ? codeUnit.slice(colon + 1) : codeUnit;
}
