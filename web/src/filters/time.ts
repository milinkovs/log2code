// All times in the UI are UTC, like `@timestamp` and the raw log lines (decided with the user in
// T27), so a dataset looks the same on every machine and list times match the `raw` text.

const pad = (n: number, width = 2) => String(n).padStart(width, '0');

function parse(iso: string | null | undefined): Date | undefined {
  if (!iso) return undefined;
  const date = new Date(iso);
  return Number.isNaN(date.getTime()) ? undefined : date;
}

/** `HH:mm:ss.SSS` (UTC) for list rows; empty for a missing or invalid timestamp. */
export function formatTime(iso: string | null | undefined): string {
  const d = parse(iso);
  if (!d) return '';
  return `${pad(d.getUTCHours())}:${pad(d.getUTCMinutes())}:${pad(d.getUTCSeconds())}.${pad(d.getUTCMilliseconds(), 3)}`;
}

/** `YYYY-MM-DD HH:mm:ss.SSS` (UTC) for the log detail and filter chips. */
export function formatDateTime(iso: string | null | undefined): string {
  const d = parse(iso);
  if (!d) return '';
  return `${d.getUTCFullYear()}-${pad(d.getUTCMonth() + 1)}-${pad(d.getUTCDate())} ${formatTime(iso)}`;
}

/** ISO instant → value of an `<input type="datetime-local" step="1">`, read as UTC. */
export function toDateTimeLocal(iso: string): string {
  const d = parse(iso);
  return d ? d.toISOString().slice(0, 19) : '';
}

/**
 * `<input type="datetime-local">` value (UTC wall time) → ISO instant. An upper bound entered to
 * the second covers that whole second (`.999`), so "to 20:05:44" includes 20:05:44.739.
 */
export function fromDateTimeLocal(value: string, bound: 'from' | 'to'): string {
  if (!value) return '';
  const withSeconds = value.length === 16 ? `${value}:00` : value;
  const d = parse(`${withSeconds}Z`);
  if (!d) return '';
  if (bound === 'to' && !withSeconds.includes('.')) d.setUTCMilliseconds(999);
  return d.toISOString();
}
