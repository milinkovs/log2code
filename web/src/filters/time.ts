// All times in the UI are in the browser's local time zone, like OpenSearch Dashboards shows them,
// so a log opened from Discover shows the same time in both (decided with the user after T32; it
// replaces T27's UTC). The zone is not labeled. `@timestamp` and the API stay UTC instants; the raw
// log line keeps whatever the service wrote.

const pad = (n: number, width = 2) => String(n).padStart(width, '0');

function parse(iso: string | null | undefined): Date | undefined {
  if (!iso) return undefined;
  const date = new Date(iso);
  return Number.isNaN(date.getTime()) ? undefined : date;
}

/** `HH:mm:ss.SSS` (local time) for list rows; empty for a missing or invalid timestamp. */
export function formatTime(iso: string | null | undefined): string {
  const d = parse(iso);
  if (!d) return '';
  return `${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}.${pad(d.getMilliseconds(), 3)}`;
}

/** `YYYY-MM-DD HH:mm:ss.SSS` (local time) for the log detail and filter chips. */
export function formatDateTime(iso: string | null | undefined): string {
  const d = parse(iso);
  if (!d) return '';
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${formatTime(iso)}`;
}

/** ISO instant → value of an `<input type="datetime-local" step="1">` (local wall time). */
export function toDateTimeLocal(iso: string): string {
  const d = parse(iso);
  if (!d) return '';
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`;
}

/**
 * `<input type="datetime-local">` value (local wall time) → ISO instant (UTC). An upper bound
 * entered to the second covers that whole second (`.999`), so "to 20:05:44" includes 20:05:44.739.
 */
export function fromDateTimeLocal(value: string, bound: 'from' | 'to'): string {
  if (!value) return '';
  const withSeconds = value.length === 16 ? `${value}:00` : value;
  // An ISO date-time without an offset is parsed as local time.
  const d = parse(withSeconds);
  if (!d) return '';
  if (bound === 'to' && !withSeconds.includes('.')) d.setMilliseconds(999);
  return d.toISOString();
}
