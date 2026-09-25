import { loader } from '@monaco-editor/react';
import * as monaco from 'monaco-editor/editor/editor.api';
// Editor features (find, folding, …) and the Java tokenizer only: the full `monaco-editor` entry
// would also bundle every language and the TypeScript, CSS, HTML and JSON workers (~9 MB).
import 'monaco-editor/features/register.all';
import 'monaco-editor/languages/definitions/java/register';
import EditorWorker from 'monaco-editor/editor/editor.worker?worker';
import type { ColorScheme } from '../theme/themeStore';
import { buildMonacoTheme, documentTokenReader, themeName } from './monacoTheme';

// Monaco is bundled and served with the app, never fetched from a CDN (T28 step 2, AC4):
// @monaco-editor/react gets this local instance instead of loading its default CDN copy, and the
// one web worker the viewer needs (the base editor worker; Java has no language worker) is built
// by Vite. This module is only reached through the lazily loaded CodeViewer, so the editor stays
// out of the main bundle.

self.MonacoEnvironment = {
  getWorker: () => new EditorWorker(),
};

loader.config({ monaco });

/** (Re)defines the editor theme for `scheme` from the tokens currently applied on the page. */
export function defineTheme(scheme: ColorScheme): string {
  const name = themeName(scheme);
  monaco.editor.defineTheme(name, buildMonacoTheme(scheme, documentTokenReader()));
  return name;
}

export { monaco };
