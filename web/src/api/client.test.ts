import { describe, expect, it } from 'vitest';
import { json, mockFetch, problem } from '../test/fetchMock';
import { ApiError, api, buildUrl, isNotFound } from './client';

describe('buildUrl', () => {
  it('prefixes /api and omits the query string when there is nothing to send', () => {
    expect(buildUrl('/logs')).toBe('/api/logs');
    expect(buildUrl('/logs', { q: '', datasetId: undefined, traceId: null })).toBe('/api/logs');
  });

  it('repeats multi-valued parameters and stringifies numbers and booleans', () => {
    const url = buildUrl('/logs', {
      service: ['customers-service', 'vets-service'],
      level: ['ERROR'],
      hasException: false,
      size: 50,
    });
    const params = new URL(url, 'http://localhost').searchParams;
    expect(params.getAll('service')).toEqual(['customers-service', 'vets-service']);
    expect(params.getAll('level')).toEqual(['ERROR']);
    expect(params.get('hasException')).toBe('false');
    expect(params.get('size')).toBe('50');
  });

  it('encodes the searchAfter cursor exactly once (ADR-024 point 5)', () => {
    const cursor = 'WyIxNzkw+MTkz/NjU2IiwiNSJd==';
    const url = buildUrl('/logs', { searchAfter: cursor });
    expect(url).not.toContain('%25'); // a double-encoded '%' would show up as %25
    expect(new URL(url, 'http://localhost').searchParams.get('searchAfter')).toBe(cursor);
  });
});

describe('request / api', () => {
  it('GETs JSON from the right URL', async () => {
    const fetchSpy = mockFetch((url) =>
      url.pathname === '/api/meta/datasets'
        ? json([{ datasetId: 'smoke-01', count: 3 }])
        : undefined,
    );

    await expect(api.getDatasets()).resolves.toEqual([{ datasetId: 'smoke-01', count: 3 }]);
    expect(fetchSpy).toHaveBeenCalledOnce();
    const [input, init] = fetchSpy.mock.calls[0];
    expect(input).toBe('/api/meta/datasets');
    expect(init?.method).toBe('GET');
  });

  it('passes search parameters through to GET /api/logs', async () => {
    const fetchSpy = mockFetch(() =>
      json({ items: [], nextSearchAfter: null, total: 0, tookMs: 1 }),
    );

    await api.searchLogs({ datasetId: 'smoke-01', level: ['WARN', 'ERROR'], size: 10 });
    const url = new URL(String(fetchSpy.mock.calls[0][0]), 'http://localhost');
    expect(url.pathname).toBe('/api/logs');
    expect(url.searchParams.get('datasetId')).toBe('smoke-01');
    expect(url.searchParams.getAll('level')).toEqual(['WARN', 'ERROR']);
    expect(url.searchParams.get('size')).toBe('10');
  });

  it('encodes path segments', async () => {
    const fetchSpy = mockFetch(() => json({}));
    await api.getLog('a/b c');
    expect(fetchSpy.mock.calls[0][0]).toBe('/api/logs/a%2Fb%20c');
  });

  it('turns an RFC 7807 error into an ApiError with the detail as message', async () => {
    mockFetch(() => problem(404, 'log not found: nope'));

    const error = await api.getLog('nope').catch((e: unknown) => e);
    expect(error).toBeInstanceOf(ApiError);
    expect(error).toMatchObject({ status: 404, message: 'log not found: nope' });
    expect((error as ApiError).problem?.detail).toBe('log not found: nope');
    expect(isNotFound(error)).toBe(true);
  });

  it('reports non-JSON errors with the HTTP status', async () => {
    mockFetch(() => new Response('Bad Gateway', { status: 502 }));

    const error = await api.getDatasets().catch((e: unknown) => e);
    expect(error).toMatchObject({ status: 502, problem: undefined });
    expect((error as Error).message).toContain('HTTP 502');
    expect(isNotFound(error)).toBe(false);
  });

  it('sends a JSON body on PUT and accepts 204 on DELETE', async () => {
    const fetchSpy = mockFetch((_url, init) => {
      if (init?.method === 'PUT') return json({ logId: 'x', verdict: 'correct' });
      if (init?.method === 'DELETE') return new Response(null, { status: 204 });
      return undefined;
    });

    await api.putLabel('x', { verdict: 'correct', note: 'ok' });
    const [, putInit] = fetchSpy.mock.calls[0];
    expect(putInit?.body).toBe(JSON.stringify({ verdict: 'correct', note: 'ok' }));
    expect((putInit?.headers as Record<string, string>)['Content-Type']).toBe('application/json');

    await expect(api.deleteLabel('x')).resolves.toBeUndefined();
  });
});
