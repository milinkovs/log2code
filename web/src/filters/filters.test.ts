import { describe, expect, it } from 'vitest';
import {
  EMPTY_FILTERS,
  applyFilters,
  filterMismatches,
  clearFilters,
  hasActiveFilters,
  parseFilters,
  toSearchParams,
} from './filters';

const p = (query: string) => new URLSearchParams(query);

describe('parseFilters', () => {
  it('reads every filter from the URL, repeated parameters as lists', () => {
    const f = parseFilters(
      p(
        'service=customers-service&service=vets-service&level=ERROR&level=WARN&status=ambiguous' +
          '&confidence=low&q=Saving%20owner&from=2026-09-23T20:00:00.000Z&to=2026-09-23T21:00:00.999Z' +
          '&traceId=abc123&hasException=true',
      ),
    );
    expect(f).toEqual({
      service: ['customers-service', 'vets-service'],
      level: ['ERROR', 'WARN'],
      status: ['ambiguous'],
      confidence: ['low'],
      q: 'Saving owner',
      from: '2026-09-23T20:00:00.000Z',
      to: '2026-09-23T21:00:00.999Z',
      traceId: 'abc123',
      hasException: true,
    });
  });

  it('ignores unknown enum values, duplicates, bad instants and non-true booleans', () => {
    const f = parseFilters(
      p(
        'level=ERROR&level=LOUD&level=ERROR&status=maybe&confidence=HIGH&from=yesterday&hasException=1',
      ),
    );
    expect(f).toEqual({ ...EMPTY_FILTERS, level: ['ERROR'] });
  });

  it('an empty URL has no active filters', () => {
    expect(parseFilters(p(''))).toEqual(EMPTY_FILTERS);
    expect(hasActiveFilters(EMPTY_FILTERS)).toBe(false);
    expect(hasActiveFilters({ ...EMPTY_FILTERS, hasException: true })).toBe(true);
    expect(hasActiveFilters({ ...EMPTY_FILTERS, level: ['INFO'] })).toBe(true);
  });
});

describe('applyFilters and clearFilters', () => {
  it('replaces only the patched filters and keeps dataset, order and the rest', () => {
    const next = applyFilters(p('datasetId=smoke-01&order=asc&level=INFO&service=a'), {
      level: ['ERROR', 'WARN'],
      q: 'boom',
      hasException: true,
    });
    expect(next.getAll('level')).toEqual(['ERROR', 'WARN']);
    expect(next.get('q')).toBe('boom');
    expect(next.get('hasException')).toBe('true');
    expect(next.get('service')).toBe('a');
    expect(next.get('datasetId')).toBe('smoke-01');
    expect(next.get('order')).toBe('asc');
  });

  it('drops empty values instead of writing them', () => {
    const next = applyFilters(p('level=INFO&q=x&hasException=true&traceId=t'), {
      level: [],
      q: '  ',
      hasException: false,
      traceId: '',
    });
    expect(next.toString()).toBe('');
  });

  it('Clear removes every filter but keeps the dataset and the sort order', () => {
    const next = clearFilters(
      p(
        'datasetId=smoke-01&order=asc&level=INFO&service=a&q=x&from=2026-01-01T00:00:00Z&hasException=true',
      ),
    );
    expect(next.toString()).toBe('datasetId=smoke-01&order=asc');
  });
});

describe('toSearchParams', () => {
  it('maps the URL onto GET /api/logs parameters with the same names', () => {
    expect(
      toSearchParams(
        p('datasetId=smoke-01&service=customers-service&level=INFO&level=WARN&q=%20owner%20'),
      ),
    ).toEqual({
      datasetId: 'smoke-01',
      service: ['customers-service'],
      level: ['INFO', 'WARN'],
      status: undefined,
      confidence: undefined,
      q: 'owner',
      from: undefined,
      to: undefined,
      traceId: undefined,
      hasException: undefined,
      order: 'desc',
    });
  });

  it('defaults to newest first and only accepts asc as the other order', () => {
    expect(toSearchParams(p('order=asc')).order).toBe('asc');
    expect(toSearchParams(p('order=sideways')).order).toBe('desc');
  });
});

describe('filterMismatches', () => {
  const log = {
    datasetId: 'smoke-01',
    service: 'customers-service',
    level: 'INFO',
    timestamp: '2026-09-23T20:05:44.739Z',
    traceId: 'abc',
    exception: null,
    match: { status: 'matched' as const, confidenceLevel: 'high' as const },
  };

  it('is empty when the log satisfies every active filter', () => {
    expect(
      filterMismatches(
        log,
        p(
          'datasetId=smoke-01&service=customers-service&service=vets-service&level=INFO&status=matched' +
            '&confidence=high&from=2026-09-23T20:05:44.739Z&to=2026-09-23T20:05:44.739Z&traceId=abc',
        ),
      ),
    ).toEqual([]);
    expect(filterMismatches(log, p(''))).toEqual([]);
  });

  it('names every filter the log does not satisfy', () => {
    expect(
      filterMismatches(
        log,
        p(
          'datasetId=demo-01&service=vets-service&level=ERROR&status=ambiguous&confidence=low' +
            '&from=2026-09-23T21:00:00Z&traceId=xyz&hasException=true',
        ),
      ),
    ).toEqual([
      'dataset',
      'service',
      'level',
      'status',
      'confidence',
      'time',
      'trace',
      'exceptions',
    ]);
    expect(filterMismatches(log, p('to=2026-09-23T20:05:44.738Z'))).toEqual(['time']);
  });

  it('never reports the text search, which only the API can evaluate', () => {
    expect(filterMismatches(log, p('q=something%20else%20entirely'))).toEqual([]);
  });
});
