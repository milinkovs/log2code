import { describe, expect, it } from 'vitest';
import { ALT_PARAM, withoutAlternative } from './alternative';
import { memberLabel, shortVersion } from './labels';
import { buildMonacoTheme, themeName, toHexColor } from './monacoTheme';
import { breakdownRows, formatPoints } from './scoreBreakdown';

describe('toHexColor', () => {
  it('keeps and expands hex colors', () => {
    expect(toHexColor('#111113')).toBe('#111113');
    expect(toHexColor('#ABC')).toBe('#aabbcc');
    expect(toHexColor('#abcd')).toBe('#aabbccdd');
    expect(toHexColor(' #8b7cf659 ')).toBe('#8b7cf659');
  });

  it('converts the functional notation, with and without alpha', () => {
    expect(toHexColor('rgb(17, 17, 19)')).toBe('#111113');
    expect(toHexColor('rgba(139, 124, 246, 0.35)')).toBe('#8b7cf659');
    expect(toHexColor('rgb(139 124 246 / 0.05)')).toBe('#8b7cf60d');
    expect(toHexColor('rgb(255 255 255 / 4%)')).toBe('#ffffff0a');
    expect(toHexColor('rgba(0, 0, 0, 1)')).toBe('#000000');
  });

  it('rejects what Monaco could not use', () => {
    expect(toHexColor('')).toBeUndefined();
    expect(toHexColor('var(--x)')).toBeUndefined();
    expect(toHexColor('#12345')).toBeUndefined();
    expect(toHexColor('rgb(1, 2)')).toBeUndefined();
    expect(toHexColor('hsl(0 0% 0%)')).toBeUndefined();
  });
});

describe('buildMonacoTheme', () => {
  const tokens: Record<string, string> = {
    '--code-bg': 'rgb(17, 17, 19)',
    '--code-text': '#c4c4cc',
    '--code-line-number': '#3f3f46',
    '--code-keyword': 'rgb(154, 166, 189)',
    '--code-comment': '#5c5c66',
    '--bg-hover': 'rgba(255, 255, 255, 0.04)',
    '--selection-bg': 'rgba(139, 124, 246, 0.35)',
    '--accent': '#8b7cf6',
  };
  const read = (token: string) => tokens[token] ?? '';

  it('takes the editor colors from the tokens (design.md §6)', () => {
    const theme = buildMonacoTheme('dark', read);
    expect(theme.base).toBe('vs-dark');
    expect(theme.inherit).toBe(true);
    expect(theme.colors['editor.background']).toBe('#111113');
    expect(theme.colors['editorLineNumber.foreground']).toBe('#3f3f46');
    expect(theme.colors['editor.lineHighlightBackground']).toBe('#ffffff0a');
    expect(theme.colors['editor.selectionBackground']).toBe('#8b7cf659');
    expect(theme.colors['editorCursor.foreground']).toBe('#8b7cf6');
    // Tokens that could not be read are left to the base theme.
    expect(theme.colors).not.toHaveProperty('editorWidget.background');
  });

  it('muted syntax rules without # and without alpha', () => {
    const theme = buildMonacoTheme('light', read);
    expect(theme.base).toBe('vs');
    expect(theme.rules).toContainEqual({ token: 'keyword', foreground: '9aa6bd' });
    expect(theme.rules).toContainEqual({
      token: 'comment',
      foreground: '5c5c66',
      fontStyle: 'italic',
    });
    expect(theme.rules.some((r) => r.token === 'string')).toBe(false);
  });

  it('names one theme per scheme', () => {
    expect(themeName('dark')).toBe('log2code-dark');
    expect(themeName('light')).toBe('log2code-light');
  });
});

describe('score breakdown', () => {
  it('orders the components as in the specification and sums them', () => {
    const { rows, score } = breakdownRows({
      throwable_consistent: 0.05,
      level_equal: 0.1,
      regex_full: 0.45,
      specificity: 0.1333,
      logger_exact: 0.2,
    });
    expect(rows.map((r) => r.key)).toEqual([
      'regex_full',
      'specificity',
      'logger_exact',
      'level_equal',
      'throwable_consistent',
    ]);
    expect(rows[0].label).toBe('Message matches the template');
    expect(score).toBeCloseTo(0.9333, 4);
  });

  it('clamps the score and keeps unknown components readable', () => {
    const { rows, score } = breakdownRows({ level_conflict: -0.3, some_new_part: 0.01 });
    expect(score).toBe(0);
    expect(rows.map((r) => r.label)).toEqual(['Level differs', 'some new part']);
    expect(breakdownRows(null)).toEqual({ rows: [], score: 0 });
  });

  it('formats signed points', () => {
    expect(formatPoints(0.45)).toBe('+0.45');
    expect(formatPoints(-0.3)).toBe('−0.30');
    expect(formatPoints(0.0001)).toBe('0.00');
  });
});

describe('labels', () => {
  it('Class#method, keeping outer classes when the package is known', () => {
    expect(memberLabel('a.b.OwnerResource', 'createOwner')).toBe('OwnerResource#createOwner');
    expect(memberLabel('a.b.Outer.Inner', 'run', 'a.b')).toBe('Outer.Inner#run');
    expect(memberLabel(null, null)).toBe('?#?');
  });

  it('short commit for the project, version for a library', () => {
    const sha = '3858f9c630cf989bb6809a86edf47c2be78dc9f1';
    expect(shortVersion({ codeUnit: { type: 'project', name: 'p', version: sha } })).toBe(
      '3858f9c',
    );
    expect(shortVersion({ codeUnit: { type: 'dependency', name: 'g:a', version: '2.0.5' } })).toBe(
      '2.0.5',
    );
  });
});

describe('alternative in the URL', () => {
  it('withoutAlternative drops only the alternative', () => {
    const params = new URLSearchParams(`datasetId=smoke-01&level=INFO&${ALT_PARAM}=stmt-9`);
    expect(withoutAlternative(params).toString()).toBe('datasetId=smoke-01&level=INFO');
    expect(params.get(ALT_PARAM)).toBe('stmt-9');
  });
});
