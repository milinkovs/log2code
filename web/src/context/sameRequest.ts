// Pure helpers of the "Same request" tab (T30).

/** Number of `--service-N` tokens in tokens.css. */
export const SERVICE_COLORS = 8;

/** 1-based index of a service's preferred color token (FNV-1a hash of the name, design.md §5.2). */
export function serviceColorIndex(service: string | null): number {
  let hash = 0x811c9dc5;
  for (const char of service ?? '') {
    hash ^= char.codePointAt(0) ?? 0;
    hash = Math.imul(hash, 0x01000193);
  }
  return ((hash >>> 0) % SERVICE_COLORS) + 1;
}

/**
 * Color tokens (`var(--service-N)`) for the services of one list. Each service starts from its hash
 * color; when two collide (6 PetClinic services in 8 colors do), the later one in name order takes
 * the next free color. The result depends only on the set of services, so the same set always gets
 * the same colors, and no two services of a list share one while colors last.
 */
export function serviceColors(services: Iterable<string | null>): Map<string, string> {
  const names = [...new Set([...services].map((s) => s ?? ''))].sort();
  const taken = new Set<number>();
  const colors = new Map<string, string>();
  for (const name of names) {
    let index = serviceColorIndex(name);
    for (let tries = 0; tries < SERVICE_COLORS && taken.has(index); tries += 1) {
      index = (index % SERVICE_COLORS) + 1;
    }
    taken.add(index);
    colors.set(name, `var(--service-${index})`);
  }
  return colors;
}

/**
 * Time since the first log of the request: `+0 ms`, `+12 ms`, `+1.234 s`, `+2 min 3.4 s`. Empty
 * when either time is missing.
 */
export function formatOffset(fromIso: string | null, toIso: string | null): string {
  if (!fromIso || !toIso) return '';
  const ms = Date.parse(toIso) - Date.parse(fromIso);
  if (Number.isNaN(ms)) return '';
  const sign = ms < 0 ? '−' : '+';
  const abs = Math.abs(ms);
  if (abs < 1000) return `${sign}${abs} ms`;
  if (abs < 60_000) return `${sign}${(abs / 1000).toFixed(3)} s`;
  const minutes = Math.floor(abs / 60_000);
  return `${sign}${minutes} min ${((abs % 60_000) / 1000).toFixed(1)} s`;
}
