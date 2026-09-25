import { act, renderHook } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { layoutKey, readLayout, usePersistentLayout, writeLayout } from './layoutStorage';

const IDS = ['left', 'right'] as const;

describe('readLayout / writeLayout', () => {
  it('round-trips a layout through localStorage', () => {
    writeLayout('g', { left: 30, right: 70 });
    expect(window.localStorage.getItem(layoutKey('g'))).toBe('{"left":30,"right":70}');
    expect(readLayout('g', IDS)).toEqual({ left: 30, right: 70 });
  });

  it('returns undefined when nothing is stored', () => {
    expect(readLayout('g', IDS)).toBeUndefined();
  });

  it.each([
    ['corrupt JSON', '{left:'],
    ['not an object', '[30,70]'],
    ['other panel ids', '{"left":30,"middle":70}'],
    ['an extra panel', '{"left":30,"right":60,"extra":10}'],
    ['a non-numeric size', '{"left":"30","right":70}'],
    ['a negative size', '{"left":-10,"right":110}'],
    ['sizes not adding up to 100', '{"left":10,"right":20}'],
  ])('ignores %s', (_, raw) => {
    window.localStorage.setItem(layoutKey('g'), raw);
    expect(readLayout('g', IDS)).toBeUndefined();
  });

  it('survives a storage that throws on read and on write', () => {
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new DOMException('denied', 'SecurityError');
    });
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new DOMException('full', 'QuotaExceededError');
    });

    expect(readLayout('g', IDS)).toBeUndefined();
    expect(() => writeLayout('g', { left: 50, right: 50 })).not.toThrow();
  });
});

describe('usePersistentLayout', () => {
  it('provides the saved layout as defaultLayout', () => {
    writeLayout('g', { left: 25, right: 75 });
    const { result } = renderHook(() => usePersistentLayout('g', IDS));
    expect(result.current.id).toBe('g');
    expect(result.current.defaultLayout).toEqual({ left: 25, right: 75 });
  });

  it('saves only layout changes made by the user', () => {
    const { result } = renderHook(() => usePersistentLayout('g', IDS));
    expect(result.current.defaultLayout).toBeUndefined();

    act(() =>
      result.current.onLayoutChanged({ left: 50, right: 50 }, { isUserInteraction: false }),
    );
    expect(window.localStorage.getItem(layoutKey('g'))).toBeNull();

    act(() => result.current.onLayoutChanged({ left: 40, right: 60 }, { isUserInteraction: true }));
    expect(readLayout('g', IDS)).toEqual({ left: 40, right: 60 });
  });
});
