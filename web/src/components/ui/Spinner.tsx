import { LoaderCircle } from 'lucide-react';

/** Loading indicator with a visible label, e.g. "Loading log…". */
export function Spinner({ label }: { label: string }) {
  return (
    <span className="spinner" role="status">
      <LoaderCircle size={14} aria-hidden="true" className="spinner__icon" />
      {label}
    </span>
  );
}
