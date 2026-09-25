import { useCallback, useState } from 'react';
import type { Layout, LayoutChangedMeta } from 'react-resizable-panels';

const KEY_PREFIX = 'log2code:layout:';

export const layoutKey = (groupId: string) => `${KEY_PREFIX}${groupId}`;

/**
 * Reads a saved panel layout. Any failure (storage disabled or throwing, corrupt JSON, a layout
 * for a different set of panels) yields `undefined`, which makes the group use its default sizes.
 */
export function readLayout(groupId: string, panelIds: readonly string[]): Layout | undefined {
  let raw: string | null;
  try {
    raw = window.localStorage.getItem(layoutKey(groupId));
  } catch {
    return undefined;
  }
  if (!raw) return undefined;

  let parsed: unknown;
  try {
    parsed = JSON.parse(raw);
  } catch {
    return undefined;
  }
  if (typeof parsed !== 'object' || parsed === null || Array.isArray(parsed)) return undefined;

  const record = parsed as Record<string, unknown>;
  if (Object.keys(record).length !== panelIds.length) return undefined;
  const layout: Layout = {};
  let total = 0;
  for (const id of panelIds) {
    const size = record[id];
    if (typeof size !== 'number' || !Number.isFinite(size) || size < 0) return undefined;
    layout[id] = size;
    total += size;
  }
  // Sizes are percentages of the group; anything far from 100 is not a layout we wrote.
  return Math.abs(total - 100) <= 1 ? layout : undefined;
}

/** Saves a panel layout; failures are ignored (the layout simply is not remembered). */
export function writeLayout(groupId: string, layout: Layout): void {
  try {
    window.localStorage.setItem(layoutKey(groupId), JSON.stringify(layout));
  } catch {
    // storage full, disabled or unavailable: keep working with in-memory sizes
  }
}

/**
 * Wires a `Group` to localStorage: pass the result straight to `<Group {...}>`. The saved layout
 * is read once on mount; only user-driven changes (drag, keyboard) are written back.
 */
export function usePersistentLayout(groupId: string, panelIds: readonly string[]) {
  const [defaultLayout] = useState(() => readLayout(groupId, panelIds));
  const onLayoutChanged = useCallback(
    (layout: Layout, meta: LayoutChangedMeta) => {
      if (meta.isUserInteraction) writeLayout(groupId, layout);
    },
    [groupId],
  );
  return { id: groupId, defaultLayout, onLayoutChanged };
}
