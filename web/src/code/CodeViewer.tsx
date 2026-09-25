import Editor, { type OnMount } from '@monaco-editor/react';
import { useEffect, useMemo, useRef, useState } from 'react';
import type { editor } from 'monaco-editor';
import { Spinner } from '../components/ui';
import { useColorScheme } from '../theme/themeStore';
import { defineTheme, monaco } from './monacoSetup';
import { readToken } from './monacoTheme';
import type { CodeViewerProps } from './codeViewerProps';

type Editor = editor.IStandaloneCodeEditor;

const FONT_SIZE = 12;
const LINE_HEIGHT = Math.round(FONT_SIZE * 1.65);
/** Below this the editor is still being laid out and cannot center a line. */
const MIN_REVEAL_HEIGHT = 3 * LINE_HEIGHT;

/**
 * Read-only Java viewer (Monaco) for one file or snippet. Highlights the log statement
 * (`statement`, background plus a marker in the margin) and, faintly, the enclosing method, and
 * scrolls the statement to the middle. Loaded lazily: this module pulls in the whole editor.
 */
export default function CodeViewer({
  value,
  path,
  firstLine = 1,
  statement,
  method,
}: CodeViewerProps) {
  const scheme = useColorScheme();
  const [editorInstance, setEditorInstance] = useState<Editor | null>(null);
  const decorations = useRef<editor.IEditorDecorationsCollection | null>(null);
  // A reveal asked for before the editor had its size; it runs on the first layout that has it.
  const pendingReveal = useRef<(() => void) | null>(null);

  // The tokens differ per theme, so the Monaco theme is rebuilt from them whenever the scheme
  // changes (themeStore updates <html data-theme> before it notifies, so the new values are live).
  const theme = useMemo(() => defineTheme(scheme), [scheme]);

  const onMount: OnMount = (instance) => {
    decorations.current = instance.createDecorationsCollection();
    // @monaco-editor/react keeps the container hidden until the editor is ready, so the first
    // layout is only a few pixels high and centering then would leave the statement at the top.
    instance.onDidLayoutChange((layout) => {
      if (layout.height >= MIN_REVEAL_HEIGHT && pendingReveal.current) {
        pendingReveal.current();
        pendingReveal.current = null;
      }
    });
    setEditorInstance(instance);
  };

  // Statement and method ranges in model lines (a snippet starts at `firstLine` in the file).
  const toModel = (fileLine: number) => fileLine - firstLine + 1;
  const statementStart = toModel(statement.start);
  const statementEnd = toModel(Math.max(statement.end, statement.start));
  const methodStart = method ? toModel(method.start) : undefined;
  const methodEnd = method ? toModel(method.end) : undefined;

  useEffect(() => {
    if (!editorInstance || !decorations.current) return;
    const model = editorInstance.getModel();
    if (!model) return;
    const lines = model.getLineCount();
    const clamp = (line: number) => Math.min(Math.max(line, 1), lines);
    const all: editor.IModelDeltaDecoration[] = [];
    if (methodStart !== undefined && methodEnd !== undefined) {
      all.push({
        range: new monaco.Range(clamp(methodStart), 1, clamp(methodEnd), 1),
        options: { isWholeLine: true, className: 'code-method-range' },
      });
    }
    all.push({
      range: new monaco.Range(clamp(statementStart), 1, clamp(statementEnd), 1),
      options: {
        isWholeLine: true,
        className: 'code-statement-line',
        linesDecorationsClassName: 'code-statement-marker',
        overviewRuler: {
          color: { id: 'editorCursor.foreground' },
          position: monaco.editor.OverviewRulerLane.Full,
        },
        minimap: {
          color: { id: 'editorCursor.foreground' },
          position: monaco.editor.MinimapPosition.Inline,
        },
      },
    });
    decorations.current.set(all);
    editorInstance.setSelection(
      new monaco.Range(clamp(statementStart), 1, clamp(statementStart), 1),
    );
    const reveal = () =>
      editorInstance.revealLinesInCenter(
        clamp(statementStart),
        clamp(statementEnd),
        monaco.editor.ScrollType.Immediate,
      );
    if (editorInstance.getLayoutInfo().height >= MIN_REVEAL_HEIGHT) {
      pendingReveal.current = null;
      reveal();
    } else {
      pendingReveal.current = reveal;
    }
  }, [editorInstance, value, path, statementStart, statementEnd, methodStart, methodEnd]);

  const lineNumbers = firstLine === 1 ? ('on' as const) : (n: number) => String(n + firstLine - 1);

  return (
    <div className="code-viewer" data-testid="code-viewer">
      <Editor
        path={path}
        value={value}
        language="java"
        theme={theme}
        onMount={onMount}
        loading={<Spinner label="Loading editor…" />}
        options={{
          readOnly: true,
          domReadOnly: true,
          minimap: { enabled: true, renderCharacters: false, scale: 1 },
          fontFamily: readToken('--font-mono'),
          fontSize: FONT_SIZE,
          lineHeight: LINE_HEIGHT,
          lineNumbers,
          lineNumbersMinChars: 4,
          glyphMargin: false,
          folding: true,
          scrollBeyondLastLine: false,
          renderLineHighlight: 'line',
          contextmenu: false,
          automaticLayout: true,
          padding: { top: 8, bottom: 8 },
          scrollbar: { verticalScrollbarSize: 10, horizontalScrollbarSize: 10 },
          overviewRulerLanes: 1,
          stickyScroll: { enabled: false },
          guides: { indentation: true },
          occurrencesHighlight: 'off',
          links: false,
          unicodeHighlight: { ambiguousCharacters: false, invisibleCharacters: false },
        }}
      />
    </div>
  );
}
