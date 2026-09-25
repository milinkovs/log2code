import { act, renderHook } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  DEFAULT_PREFERENCE,
  readPreference,
  resetThemeStoreForTests,
  THEME_STORAGE_KEY,
  useColorScheme,
  useThemePreference,
} from './themeStore';

/** A controllable `prefers-color-scheme: dark` media query. */
function stubDarkMedia(initiallyDark: boolean) {
  const listeners = new Set<() => void>();
  const media = {
    matches: initiallyDark,
    addEventListener: (_: string, l: () => void) => listeners.add(l),
    removeEventListener: (_: string, l: () => void) => listeners.delete(l),
  };
  vi.spyOn(window, 'matchMedia').mockReturnValue(media as unknown as MediaQueryList);
  return {
    setDark(dark: boolean) {
      media.matches = dark;
      listeners.forEach((l) => l());
    },
    listenerCount: () => listeners.size,
  };
}

afterEach(() => {
  resetThemeStoreForTests();
  delete document.documentElement.dataset.theme;
});

describe('theme preference', () => {
  it('defaults to dark when nothing is stored, regardless of the system', () => {
    stubDarkMedia(false);
    expect(DEFAULT_PREFERENCE).toBe('dark');
    const { result } = renderHook(() => useColorScheme());
    expect(result.current).toBe('dark');
  });

  it('ignores unknown stored values', () => {
    window.localStorage.setItem(THEME_STORAGE_KEY, 'purple');
    expect(readPreference()).toBe('dark');
  });

  it('is stored, applied to <html data-theme> and shown by both hooks', () => {
    stubDarkMedia(true);
    const pref = renderHook(() => useThemePreference());
    const scheme = renderHook(() => useColorScheme());

    act(() => pref.result.current[1]('light'));
    expect(pref.result.current[0]).toBe('light');
    expect(scheme.result.current).toBe('light');
    expect(window.localStorage.getItem(THEME_STORAGE_KEY)).toBe('light');
    expect(document.documentElement.dataset.theme).toBe('light');
  });

  it('"system" follows prefers-color-scheme live and unsubscribes on unmount', () => {
    const media = stubDarkMedia(false);
    window.localStorage.setItem(THEME_STORAGE_KEY, 'system');
    const { result, unmount } = renderHook(() => useColorScheme());
    expect(result.current).toBe('light');

    act(() => media.setDark(true));
    expect(result.current).toBe('dark');
    expect(document.documentElement.dataset.theme).toBe('dark');

    unmount();
    expect(media.listenerCount()).toBe(0);
  });

  it('still switches when storage throws', () => {
    stubDarkMedia(false);
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new Error('quota');
    });
    const pref = renderHook(() => useThemePreference());
    act(() => pref.result.current[1]('light'));
    expect(pref.result.current[0]).toBe('light');
    expect(document.documentElement.dataset.theme).toBe('light');
  });
});
