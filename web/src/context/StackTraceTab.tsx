import { ChevronRight, ExternalLink, Library } from 'lucide-react';
import { useState } from 'react';
import type { ExceptionInfoDto, StackFrameDto } from '../api/types';
import { useCodeLocation } from '../code/codeLocation';
import { Badge, Button, cx } from '../components/ui';
import { useHideLibraryFrames } from './contextPrefs';
import { useOpenLocation } from './navigation';
import { artifactOf, frameItems, frameText } from './stackTrace';

/**
 * "Stack trace" (T30): the exception and its `Caused by` chain. Project frames stand out; a frame
 * whose file is stored (`fileId`: project, or a library from its sources jar) opens that file at
 * the line (`?at=`, like a caller in T29); otherwise its GitHub link, if any; otherwise plain
 * text. "Hide library frames" folds runs of library frames into one row (ADR-032).
 */
export function StackTraceTab({ exception }: { exception: ExceptionInfoDto }) {
  const [hideLibrary, setHideLibrary] = useHideLibraryFrames();
  const all = [exception.frames, ...exception.causedBy.map((c) => c.frames)].flat();
  const projectCount = all.filter((frame) => frame.inProject).length;
  const libraryCount = all.length - projectCount;

  return (
    <div className="stack">
      <div className="stack__toolbar">
        <p className="stack__summary">
          {all.length.toLocaleString('en-US')} {all.length === 1 ? 'frame' : 'frames'} ·{' '}
          {projectCount} in the project
          {exception.rootClass && exception.rootClass !== exception.className && (
            <>
              {' · root cause '}
              <span className="mono">{exception.rootClass}</span>
            </>
          )}
        </p>
        <Button
          size="sm"
          variant="ghost"
          className={cx('filter-trigger', hideLibrary && 'filter-trigger--active')}
          aria-pressed={hideLibrary}
          icon={<Library size={14} aria-hidden="true" />}
          onClick={() => setHideLibrary(!hideLibrary)}
          disabled={libraryCount === 0}
        >
          Hide library frames
        </Button>
      </div>
      <ExceptionBlock
        heading={exception.className}
        message={exception.message}
        frames={exception.frames}
        hideLibrary={hideLibrary}
      />
      {exception.causedBy.map((cause, i) => (
        <ExceptionBlock
          // The chain is fixed for a log; its position is its identity.
          key={i}
          causedBy
          heading={cause.className}
          message={cause.message}
          frames={cause.frames}
          hideLibrary={hideLibrary}
        />
      ))}
    </div>
  );
}

function ExceptionBlock({
  heading,
  message,
  frames,
  hideLibrary,
  causedBy = false,
}: {
  heading: string | null;
  message: string | null;
  frames: StackFrameDto[];
  hideLibrary: boolean;
  causedBy?: boolean;
}) {
  // Start indexes of the folded runs the user opened; kept while the toggle changes.
  const [expanded, setExpanded] = useState<ReadonlySet<number>>(() => new Set());
  const items = frameItems(frames, hideLibrary, expanded);
  const title = `${causedBy ? 'Caused by: ' : ''}${heading ?? 'Unknown exception'}`;

  return (
    <section className="stack__block" aria-label={title}>
      <p className={cx('stack__heading mono', causedBy && 'stack__heading--cause')}>
        {title}
        {message && <span className="stack__message">: {message}</span>}
      </p>
      {frames.length === 0 ? (
        <p className="stack__empty">No frames were recorded.</p>
      ) : (
        <ol className="stack__frames">
          {items.map((item) =>
            item.kind === 'frame' ? (
              <FrameRow key={item.index} frame={item.frame} />
            ) : (
              <li key={`fold-${item.start}`}>
                <button
                  type="button"
                  className="stack-fold"
                  aria-expanded={false}
                  onClick={() => setExpanded(new Set(expanded).add(item.start))}
                >
                  <ChevronRight size={14} aria-hidden="true" />
                  {item.frames.length} library frames
                </button>
              </li>
            ),
          )}
        </ol>
      )}
    </section>
  );
}

function FrameRow({ frame }: { frame: StackFrameDto }) {
  const [location] = useCodeLocation();
  const openLocation = useOpenLocation();
  const text = frameText(frame);
  const line = frame.line !== null && frame.line > 0 ? frame.line : null;
  const openable = frame.fileId !== null && line !== null;
  const current = openable && location?.fileId === frame.fileId && location.line === line;
  const artifact = frame.inProject ? null : artifactOf(frame.codeUnit);

  let body;
  if (openable) {
    body = (
      <button
        type="button"
        className="stack-frame__open"
        title={text}
        aria-current={current ? 'location' : undefined}
        onClick={() => openLocation({ fileId: frame.fileId as string, line: line as number })}
      >
        {text}
      </button>
    );
  } else if (frame.githubUrl) {
    body = (
      <a
        className="stack-frame__github"
        href={frame.githubUrl}
        target="_blank"
        rel="noopener noreferrer"
        title={`${text} on GitHub`}
      >
        {text}
        <ExternalLink size={12} aria-hidden="true" />
      </a>
    );
  } else {
    body = (
      <span className="stack-frame__text" title={text}>
        {text}
      </span>
    );
  }

  return (
    <li
      className={cx(
        'stack-frame mono',
        frame.inProject ? 'stack-frame--project' : 'stack-frame--library',
        current && 'stack-frame--current',
      )}
    >
      <span className="stack-frame__at" aria-hidden="true">
        at
      </span>
      {body}
      {frame.inProject ? (
        <Badge tone="accent">project</Badge>
      ) : (
        artifact && <span className="stack-frame__unit">{artifact}</span>
      )}
    </li>
  );
}
