import { useSyncExternalStore } from 'react';

/** What the user picked in the theme toggle. */
export type ThemePreference = 'system' | 'light' | 'dark';
/** What is actually shown. */
export type ColorScheme = 'light' | 'dark';

export const THEME_STORAGE_KEY = 'log2code:theme';
/** Used when nothing (valid) is stored. Keep in sync with the inline script in index.html. */
export const DEFAULT_PREFERENCE: ThemePreference = 'dark';

const DARK_QUERY = '(prefers-color-scheme: dark)';
const listeners = new Set<() => void>();
// Fallback when storage throws on write, so the toggle still works for this page load.
let memoryPreference: ThemePreference | undefined;

function darkMedia(): MediaQueryList | undefined {
  return typeof window !== 'undefined' && typeof window.matchMedia === 'function'
    ? window.matchMedia(DARK_QUERY)
    : undefined;
}

function isPreference(value: unknown): value is ThemePreference {
  return value === 'system' || value === 'light' || value === 'dark';
}

/** Stored preference; any storage failure or unknown value means the default. */
export function readPreference(): ThemePreference {
  try {
    const stored = window.localStorage.getItem(THEME_STORAGE_KEY);
    return isPreference(stored) ? stored : DEFAULT_PREFERENCE;
  } catch {
    return DEFAULT_PREFERENCE;
  }
}

const currentPreference = (): ThemePreference => memoryPreference ?? readPreference();

export function resolveScheme(preference: ThemePreference): ColorScheme {
  if (preference === 'system') return darkMedia()?.matches ? 'dark' : 'light';
  return preference;
}

/** Puts the resolved scheme on `<html data-theme>`, which switches the token set in tokens.css. */
function applyScheme(): void {
  if (typeof document === 'undefined') return;
  document.documentElement.dataset.theme = resolveScheme(currentPreference());
}

function notify(): void {
  applyScheme();
  listeners.forEach((listener) => listener());
}

export function setPreference(preference: ThemePreference): void {
  try {
    window.localStorage.setItem(THEME_STORAGE_KEY, preference);
    memoryPreference = undefined;
  } catch {
    // storage unavailable: the choice still applies until the page is reloaded
    memoryPreference = preference;
  }
  notify();
}

function subscribe(onChange: () => void): () => void {
  listeners.add(onChange);
  const media = darkMedia();
  // The OS scheme matters only for "system", but listening always is cheap and keeps it simple.
  const onMedia = () => {
    applyScheme();
    onChange();
  };
  media?.addEventListener('change', onMedia);
  // Another tab changed the preference.
  const onStorage = (event: StorageEvent) => {
    if (event.key === THEME_STORAGE_KEY) onMedia();
  };
  window.addEventListener('storage', onStorage);
  return () => {
    listeners.delete(onChange);
    media?.removeEventListener('change', onMedia);
    window.removeEventListener('storage', onStorage);
  };
}

/** The theme preference and a setter, for the toggle in the top bar. */
export function useThemePreference(): [ThemePreference, (preference: ThemePreference) => void] {
  const preference = useSyncExternalStore(subscribe, currentPreference, () => DEFAULT_PREFERENCE);
  return [preference, setPreference];
}

/**
 * The scheme actually shown, live. CSS switches on its own through `data-theme`; this hook is for
 * components that need the value in JS, such as the Monaco editor theme (T28).
 */
export function useColorScheme(): ColorScheme {
  return useSyncExternalStore(
    subscribe,
    () => resolveScheme(currentPreference()),
    () => resolveScheme(DEFAULT_PREFERENCE),
  );
}

/** Test helper: forget the in-memory fallback. */
export function resetThemeStoreForTests(): void {
  memoryPreference = undefined;
}
