import type { ReactNode } from 'react';

/** A keyboard key, e.g. <Kbd>↑</Kbd> or <Kbd>1</Kbd> in hints and tooltips. */
export function Kbd({ children }: { children: ReactNode }) {
  return <kbd className="kbd">{children}</kbd>;
}
