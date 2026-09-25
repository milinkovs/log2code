import type { editor } from 'monaco-editor';
import type { ColorScheme } from '../theme/themeStore';

// Monaco cannot read CSS variables: its themes take hex colors only. The editor themes are built
// from the design tokens in tokens.css at runtime (docs/design.md §6), so tokens.css stays the only
// file with literal colors and the editor follows every token change.

export const themeName = (scheme: ColorScheme) => `log2code-${scheme}`;

/** Reads the computed value of a token (e.g. `--code-bg`) as a CSS color string. */
export type TokenReader = (token: string) => string;

const hex2 = (n: number) =>
  Math.round(Math.min(255, Math.max(0, n)))
    .toString(16)
    .padStart(2, '0');

/**
 * Converts a computed CSS color to the `#rrggbb` / `#rrggbbaa` form Monaco accepts. Handles hex
 * (3, 4, 6, 8 digits) and the functional `rgb`/`rgba` notation in both the comma and the space
 * syntax, which is what `getComputedStyle` returns. Anything else yields `undefined`.
 */
export function toHexColor(value: string): string | undefined {
  const color = value.trim().toLowerCase();
  const hex = /^#([0-9a-f]{3,8})$/.exec(color)?.[1];
  if (hex) {
    if (hex.length === 3 || hex.length === 4) return `#${[...hex].map((c) => c + c).join('')}`;
    if (hex.length === 6 || hex.length === 8) return `#${hex}`;
    return undefined;
  }
  const fn = /^rgba?\s*\(([^)]*)\)$/.exec(color)?.[1];
  if (!fn) return undefined;
  const parts = fn.split(/[\s,/]+/).filter(Boolean);
  if (parts.length < 3 || parts.length > 4) return undefined;
  const channel = (part: string) =>
    part.endsWith('%') ? (Number.parseFloat(part) / 100) * 255 : Number.parseFloat(part);
  const rgb = parts.slice(0, 3).map(channel);
  if (rgb.some(Number.isNaN)) return undefined;
  const base = `#${rgb.map(hex2).join('')}`;
  if (parts.length === 3) return base;
  const alphaPart = parts[3];
  const alpha = alphaPart.endsWith('%')
    ? Number.parseFloat(alphaPart) / 100
    : Number.parseFloat(alphaPart);
  if (Number.isNaN(alpha)) return undefined;
  return alpha >= 1 ? base : `${base}${hex2(alpha * 255)}`;
}

/** Monaco wants token rule colors without `#` and without alpha. */
const ruleColor = (hex: string | undefined) => hex?.slice(1, 7);

/**
 * The Monaco theme for one scheme, from the tokens as they are currently applied on the page.
 * Colors a token cannot provide are left out, so Monaco falls back to its base theme.
 */
export function buildMonacoTheme(
  scheme: ColorScheme,
  read: TokenReader,
): editor.IStandaloneThemeData {
  const color = (token: string) => toHexColor(read(token));

  const colors: Record<string, string | undefined> = {
    'editor.background': color('--code-bg'),
    'editor.foreground': color('--code-text'),
    'editorGutter.background': color('--code-bg'),
    'editorLineNumber.foreground': color('--code-line-number'),
    'editorLineNumber.activeForeground': color('--text-secondary'),
    'editor.lineHighlightBackground': color('--bg-hover'),
    'editor.lineHighlightBorder': color('--bg-hover'),
    'editor.selectionBackground': color('--selection-bg'),
    'editor.inactiveSelectionBackground': color('--selection-bg'),
    'editorCursor.foreground': color('--accent'),
    'editorIndentGuide.background1': color('--border'),
    'editorIndentGuide.activeBackground1': color('--border-strong'),
    'editorWidget.background': color('--bg-elevated'),
    'editorWidget.border': color('--border-strong'),
    'minimap.background': color('--code-bg'),
    'scrollbarSlider.background': color('--scrollbar-thumb'),
    'scrollbarSlider.hoverBackground': color('--scrollbar-thumb-hover'),
    'scrollbarSlider.activeBackground': color('--scrollbar-thumb-hover'),
    'editorOverviewRuler.border': color('--code-bg'),
    focusBorder: color('--accent'),
  };

  const rule = (token: string, cssToken: string, fontStyle?: string) => ({
    token,
    foreground: ruleColor(color(cssToken)),
    ...(fontStyle ? { fontStyle } : {}),
  });

  return {
    base: scheme === 'dark' ? 'vs-dark' : 'vs',
    inherit: true,
    rules: [
      rule('', '--code-text'),
      rule('comment', '--code-comment', 'italic'),
      rule('keyword', '--code-keyword'),
      rule('string', '--code-string'),
      rule('string.escape', '--code-string'),
      rule('number', '--code-number'),
      rule('annotation', '--code-annotation'),
      rule('type', '--code-type'),
      rule('type.identifier', '--code-type'),
      rule('identifier', '--code-text'),
      rule('delimiter', '--code-text'),
      rule('operator', '--code-text'),
    ].filter((r) => r.foreground !== undefined),
    colors: Object.fromEntries(
      Object.entries(colors).filter((entry): entry is [string, string] => entry[1] !== undefined),
    ),
  };
}

/**
 * Reads tokens through a probe element: custom properties that alias other tokens or use
 * an alpha channel come back from `getComputedStyle(probe).color` in one normalized notation.
 */
export function documentTokenReader(root: HTMLElement = document.body): TokenReader {
  const probe = document.createElement('span');
  probe.style.display = 'none';
  root.appendChild(probe);
  const cache = new Map<string, string>();
  const read: TokenReader = (token) => {
    let value = cache.get(token);
    if (value === undefined) {
      probe.style.color = `var(${token})`;
      value = getComputedStyle(probe).color;
      cache.set(token, value);
    }
    return value;
  };
  // The probe is only needed while one theme is being built.
  queueMicrotask(() => probe.remove());
  return read;
}

/** The computed value of a non-color token, e.g. `--font-mono`. */
export const readToken = (token: string) =>
  getComputedStyle(document.documentElement).getPropertyValue(token).trim();
