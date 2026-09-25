import { describe, expect, it } from 'vitest';
import { formatDateTime, formatTime, fromDateTimeLocal, toDateTimeLocal } from './time';

describe('time (always UTC)', () => {
  it('formats list times as HH:mm:ss.SSS in UTC', () => {
    expect(formatTime('2026-09-23T20:05:44.739Z')).toBe('20:05:44.739');
    expect(formatTime('2026-09-23T20:05:44Z')).toBe('20:05:44.000');
    expect(formatTime('2026-09-23T22:05:44.5+02:00')).toBe('20:05:44.500');
    expect(formatTime(null)).toBe('');
    expect(formatTime('not a date')).toBe('');
  });

  it('formats the full date and time for the detail view', () => {
    expect(formatDateTime('2026-09-03T07:05:04.009Z')).toBe('2026-09-03 07:05:04.009');
  });

  it('converts between ISO instants and datetime-local values without a time zone shift', () => {
    expect(toDateTimeLocal('2026-09-23T20:05:44.739Z')).toBe('2026-09-23T20:05:44');
    expect(fromDateTimeLocal('2026-09-23T20:05:44', 'from')).toBe('2026-09-23T20:05:44.000Z');
    expect(fromDateTimeLocal('2026-09-23T20:05', 'from')).toBe('2026-09-23T20:05:00.000Z');
    expect(fromDateTimeLocal('', 'from')).toBe('');
  });

  it('an upper bound covers the whole second it names', () => {
    expect(fromDateTimeLocal('2026-09-23T20:05:44', 'to')).toBe('2026-09-23T20:05:44.999Z');
    expect(fromDateTimeLocal('2026-09-23T20:05:44.100', 'to')).toBe('2026-09-23T20:05:44.100Z');
  });
});
