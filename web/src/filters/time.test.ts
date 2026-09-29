import { describe, expect, it } from 'vitest';
import { formatDateTime, formatTime, fromDateTimeLocal, toDateTimeLocal } from './time';

// The tests run in Europe/Belgrade (vite.config.ts): UTC+2 in September, UTC+1 in winter.
describe('time (browser local time)', () => {
  it('formats list times as HH:mm:ss.SSS in local time', () => {
    expect(formatTime('2026-09-23T20:05:44.739Z')).toBe('22:05:44.739');
    expect(formatTime('2026-09-23T20:05:44Z')).toBe('22:05:44.000');
    expect(formatTime('2026-09-23T22:05:44.5+02:00')).toBe('22:05:44.500');
    expect(formatTime('2026-01-10T20:05:44Z')).toBe('21:05:44.000');
    expect(formatTime(null)).toBe('');
    expect(formatTime('not a date')).toBe('');
  });

  it('formats the full date and time for the detail view, crossing midnight', () => {
    expect(formatDateTime('2026-09-03T07:05:04.009Z')).toBe('2026-09-03 09:05:04.009');
    expect(formatDateTime('2026-09-03T23:30:00.000Z')).toBe('2026-09-04 01:30:00.000');
  });

  it('converts between UTC instants and local datetime-local values', () => {
    expect(toDateTimeLocal('2026-09-23T20:05:44.739Z')).toBe('2026-09-23T22:05:44');
    expect(fromDateTimeLocal('2026-09-23T22:05:44', 'from')).toBe('2026-09-23T20:05:44.000Z');
    expect(fromDateTimeLocal('2026-09-23T22:05', 'from')).toBe('2026-09-23T20:05:00.000Z');
    expect(fromDateTimeLocal(toDateTimeLocal('2026-09-23T20:05:44Z'), 'from')).toBe(
      '2026-09-23T20:05:44.000Z',
    );
    expect(fromDateTimeLocal('', 'from')).toBe('');
  });

  it('an upper bound covers the whole second it names', () => {
    expect(fromDateTimeLocal('2026-09-23T22:05:44', 'to')).toBe('2026-09-23T20:05:44.999Z');
    expect(fromDateTimeLocal('2026-09-23T22:05:44.100', 'to')).toBe('2026-09-23T20:05:44.100Z');
  });
});
