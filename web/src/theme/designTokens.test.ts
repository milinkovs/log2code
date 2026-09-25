/// <reference types="node" />
// Node APIs are only used by this test (the app tsconfig does not include node types).
import { readdirSync, readFileSync } from 'node:fs';
import { join, relative } from 'node:path';
import { describe, expect, it } from 'vitest';

// Vitest runs from web/ (jsdom gives import.meta.url a non-file scheme).
const SRC = join(process.cwd(), 'src');
/** The one file allowed to hold literal colors (docs/design.md, "Tokeni"). */
const TOKENS_FILE = 'theme/tokens.css';
const LITERAL_COLOR = /#[0-9a-f]{3}(?:[0-9a-f]{3})?(?:[0-9a-f]{2})?\b|\b(?:rgba?|hsla?|oklch)\(/gi;

function sourceFiles(): string[] {
  return readdirSync(SRC, { recursive: true, encoding: 'utf8' })
    .map((path) => path.replaceAll('\\', '/'))
    .filter((path) => /\.(css|tsx?)$/.test(path) && !/\.test\.tsx?$/.test(path))
    .filter((path) => path !== TOKENS_FILE);
}

describe('design tokens', () => {
  it('no source file outside tokens.css uses a literal color', () => {
    const offenders = sourceFiles().flatMap((path) => {
      const text = readFileSync(join(SRC, path), 'utf8');
      return [...text.matchAll(LITERAL_COLOR)].map(
        (m) => `${relative(SRC, join(SRC, path))}: ${m[0]}`,
      );
    });
    expect(offenders).toEqual([]);
  });

  it('the dark and light themes define the same color tokens', () => {
    const css = readFileSync(join(SRC, TOKENS_FILE), 'utf8');
    const light = css.slice(css.indexOf(":root[data-theme='light']"));
    const dark = css.slice(css.indexOf('dark theme (default)'), css.indexOf('light theme'));
    const names = (block: string) =>
      [...block.matchAll(/(--[a-z0-9-]+):/g)].map((m) => m[1]).sort();
    // Light may omit tokens that are theme-independent aliases (e.g. --code-bg: var(--bg-panel)).
    const aliases = ['--code-bg', '--code-statement-bg', '--focus-ring'];
    expect(names(light)).toEqual(names(dark).filter((n) => !aliases.includes(n)));
  });
});
