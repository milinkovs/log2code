import { ScrollText, Zap } from 'lucide-react';
import type { ReactNode } from 'react';
import { isNotFound } from '../api/client';
import { useLog } from '../api/queries';
import type { ExceptionInfoDto, LogDetail, StackFrameDto } from '../api/types';
import {
  Button,
  Callout,
  ConfidenceBadge,
  EmptyState,
  LevelBadge,
  Spinner,
  matchConfidence,
} from '../components/ui';
import { filterMismatches } from '../filters/filters';
import { formatDateTime } from '../filters/time';
import { useLogFilters } from '../filters/useLogFilters';
import { useSearchParams } from 'react-router';
import { Zone } from '../layout/Zone';

/** Detail of the selected log (right, bottom): message, fields, exception summary and raw text. */
export function LogDetailZone({ logId }: { logId: string | undefined }) {
  const { data, error, isPending } = useLog(logId);

  let body;
  if (!logId) {
    body = (
      <EmptyState
        icon={ScrollText}
        title="No log selected"
        description="Pick a log in the list to see its details."
      />
    );
  } else if (error) {
    body = (
      <Callout tone="danger">
        {isNotFound(error) ? `Log not found: ${logId}` : `Could not load the log: ${error.message}`}
      </Callout>
    );
  } else if (isPending) {
    body = <Spinner label="Loading log…" />;
  } else {
    body = <LogDetailView log={data} />;
  }

  return (
    <Zone title="Log detail" icon={ScrollText}>
      {body}
    </Zone>
  );
}

const fileName = (path: string) => path.slice(path.lastIndexOf('/') + 1);

function LogDetailView({ log }: { log: LogDetail }) {
  const { update, clear } = useLogFilters();
  const [searchParams] = useSearchParams();
  const mismatches = filterMismatches(log, searchParams);
  // Clear keeps the dataset, so it only helps when something besides the dataset differs.
  const clearHelps = mismatches.some((name) => name !== 'dataset');
  const match = log.match;
  const confidence = matchConfidence(match?.status ?? null, match?.confidenceLevel ?? null);

  return (
    <article className="log-detail" aria-label="Selected log">
      {mismatches.length > 0 && (
        <Callout tone="warning">
          This log does not match the current filters ({mismatches.join(', ')}).
          {clearHelps && (
            <Button size="sm" className="callout__action" onClick={clear}>
              Clear filters
            </Button>
          )}
        </Callout>
      )}
      <div className="log-detail__head">
        <span className="mono log-detail__time">{formatDateTime(log.timestamp)} UTC</span>
        <LevelBadge level={log.level} />
        <ConfidenceBadge value={confidence} score={match?.confidence ?? undefined} />
      </div>

      <p className="log-detail__message" data-testid="log-message">
        {log.message}
      </p>

      <dl className="field-list">
        <Field label="Service">{log.service}</Field>
        <Field label="Thread" mono>
          {log.thread}
        </Field>
        <Field label="Logger" mono>
          {log.loggerRaw && (
            <>
              {log.loggerRaw}
              {!log.logger ? (
                <span className="text-muted"> (not resolved)</span>
              ) : (
                log.logger !== log.loggerRaw && (
                  <>
                    <span className="text-muted"> → </span>
                    {log.logger}
                  </>
                )
              )}
            </>
          )}
        </Field>
        <Field label="Trace id" mono>
          {log.traceId && (
            <button
              type="button"
              className="link-button"
              title="Show only logs with this trace id"
              onClick={() => update({ traceId: log.traceId ?? '' })}
            >
              {log.traceId}
            </button>
          )}
        </Field>
        <Field label="Dataset" mono>
          {log.datasetId}
        </Field>
        <Field label="Log source" mono>
          {log.sourceFile && `${log.sourceFile}:${log.lineNumber}`}
          {log.lineCount > 1 && <span className="text-muted"> ({log.lineCount} lines)</span>}
        </Field>
        {match?.filePath && (
          <Field label="Code" mono>
            <span title={match.filePath}>
              {fileName(match.filePath)}
              {match.line !== null && `:${match.line}`}
            </span>
          </Field>
        )}
      </dl>

      {log.exception && <ExceptionSummary exception={log.exception} />}

      <section className="log-detail__section" aria-label="Raw log">
        <h3 className="log-detail__label">Raw</h3>
        <pre className="code-block" data-testid="log-raw">
          {log.raw}
        </pre>
      </section>
    </article>
  );
}

function Field({ label, mono, children }: { label: string; mono?: boolean; children: ReactNode }) {
  const empty =
    children === null || children === undefined || children === false || children === '';
  return (
    <>
      <dt>{label}</dt>
      <dd className={mono ? 'mono' : undefined}>
        {empty ? <span className="text-muted">—</span> : children}
      </dd>
    </>
  );
}

const frameText = (f: StackFrameDto) =>
  `${f.className ?? '?'}.${f.method ?? '?'}(${f.file ?? 'Unknown Source'}${f.line !== null ? `:${f.line}` : ''})`;

/** Short exception summary; the full stack trace is a context tab (T30). */
function ExceptionSummary({ exception: e }: { exception: ExceptionInfoDto }) {
  const firstProjectFrame = [...e.frames, ...e.causedBy.flatMap((c) => c.frames)].find(
    (f) => f.inProject,
  );
  const frameCount = e.frames.length + e.causedBy.reduce((n, c) => n + c.frames.length, 0);
  const rootDiffers = e.rootClass && e.rootClass !== e.className;

  return (
    <section className="log-detail__section exception-summary" aria-label="Exception">
      <h3 className="log-detail__label">
        <Zap size={14} aria-hidden="true" className="exception-summary__icon" />
        Exception
      </h3>
      <p className="mono exception-summary__title">
        {e.className}
        {e.message && <span className="exception-summary__message">: {e.message}</span>}
      </p>
      {rootDiffers && (
        <p className="exception-summary__line">
          <span className="text-muted">Root cause </span>
          <span className="mono">{e.rootClass}</span>
        </p>
      )}
      {firstProjectFrame && (
        <p className="exception-summary__line">
          <span className="text-muted">First project frame </span>
          <span className="mono">{frameText(firstProjectFrame)}</span>
        </p>
      )}
      <p className="exception-summary__line text-muted">
        {frameCount.toLocaleString('en-US')} frames
        {e.causedBy.length > 0 && `, ${e.causedBy.length} caused by`}
      </p>
    </section>
  );
}
