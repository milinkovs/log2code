import { vi } from 'vitest';

type Route = (url: URL, init: RequestInit | undefined) => Response | undefined;

export const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': status >= 400 ? 'application/problem+json' : 'application/json' },
  });

export const problem = (status: number, detail: string) =>
  json({ title: 'Error', status, detail }, status);

/**
 * Replaces global `fetch` with a router over the given handlers (first non-undefined response
 * wins, unmatched requests get a 404 problem). Returns the spy to assert on the calls.
 */
export function mockFetch(...routes: Route[]) {
  const spy = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = new URL(String(input), 'http://localhost');
    for (const route of routes) {
      const response = route(url, init);
      if (response) return response;
    }
    return problem(404, `no mock for ${url.pathname}`);
  });
  vi.stubGlobal('fetch', spy);
  return spy;
}
