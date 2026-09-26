import { ArrowLeft, FileCode2, FileX2, Unlink } from 'lucide-react';
import { Suspense, lazy } from 'react';
import { isNotFound } from '../api/client';
import { useCatalogEntry, useLog, useLogCandidates, useSource } from '../api/queries';
import type { CandidateDetailDto, CatalogEntryDto, LogDetail } from '../api/types';
import { Badge, Button, Callout, EmptyState, Spinner } from '../components/ui';
import { Zone } from '../layout/Zone';
import { useAlternative } from './alternative';
import { useCodeFocus } from './codeFocus';
import { useCodeLocation, type CodeLocationRef } from './codeLocation';
import {
  AlternativeScore,
  AlternativesMenu,
  CodeLocation,
  FileLocation,
  GithubLink,
  MatchConfidence,
} from './CodeHeader';
import { memberLabel } from './labels';

// Monaco is several MB: it is loaded only once a log with code is shown (ADR-029, consequences).
const CodeViewer = lazy(() => import('./CodeViewer'));

const errorText = (error: unknown) => (error instanceof Error ? error.message : String(error));

/**
 * The "Code" zone (T28): the statement that produced the selected log, in its source file, with
 * the header (code unit, path, version, `Class#method`, confidence, alternatives, GitHub) and the
 * special states (unmatched, ambiguous, alternative shown, source not available).
 */
export function CodeZone({ logId }: { logId: string | undefined }) {
  if (!logId) {
    return (
      <Zone title="Code" icon={FileCode2}>
        <EmptyState
          icon={FileCode2}
          title="No log selected"
          description="Pick a log on the right to see the code that produced it."
        />
      </Zone>
    );
  }
  return <SelectedLogCode logId={logId} />;
}

function SelectedLogCode({ logId }: { logId: string }) {
  const log = useLog(logId);
  const [alternative, setAlternative] = useAlternative();
  const [location, setLocation] = useCodeLocation();

  const match = log.data?.match ?? null;
  const primaryId = match?.statementId ?? null;
  const shownId = alternative ?? primaryId;
  const showingAlternative = alternative !== null && alternative !== primaryId;
  const hasCandidates = (match?.candidates.length ?? 0) > 0;

  const candidates = useLogCandidates(logId, hasCandidates);
  const entry = useCatalogEntry(shownId);

  const candidateList = candidates.data ?? [];
  const shownCandidate = candidateList.find((c) => c.statementId === shownId);
  const select = (statementId: string | null) =>
    setAlternative(statementId === primaryId ? null : statementId);

  const actions = log.data && (
    <>
      {showingAlternative
        ? shownCandidate && <AlternativeScore score={shownCandidate.score} />
        : match && <MatchConfidence match={match} />}
      <AlternativesMenu
        candidates={candidateList}
        primaryId={primaryId}
        shownId={shownId}
        onSelect={select}
        attention={match?.status === 'ambiguous'}
      />
      {shownId && !location && entry.data?.githubUrl && <GithubLink url={entry.data.githubUrl} />}
    </>
  );

  return (
    <Zone title="Code" icon={FileCode2} actions={actions} flush>
      <div className="code-zone">
        {log.isPending ? (
          <div className="code-zone__message">
            <Spinner label="Loading log…" />
          </div>
        ) : log.isError ? (
          <div className="code-zone__message">
            <Callout tone="danger">
              {isNotFound(log.error)
                ? `Log not found: ${logId}`
                : `Could not load the log: ${errorText(log.error)}`}
            </Callout>
          </div>
        ) : location ? (
          <LocationCode location={location} onBack={() => setLocation(null)} />
        ) : !shownId ? (
          <UnmatchedView
            log={log.data}
            candidates={candidateList}
            loading={hasCandidates && candidates.isPending}
            onSelect={select}
          />
        ) : (
          <>
            {(entry.data || showingAlternative) && (
              <div className="code-zone__location">
                {entry.data ? (
                  <CodeLocation entry={entry.data} />
                ) : (
                  <span className="code-location" />
                )}
                {showingAlternative && (
                  <div className="code-zone__alternative">
                    <Badge tone="warning">Showing alternative</Badge>
                    <Button
                      variant="ghost"
                      size="sm"
                      icon={<ArrowLeft size={14} aria-hidden="true" />}
                      onClick={() => setAlternative(null)}
                    >
                      {primaryId ? 'Back to top match' : 'Back to message'}
                    </Button>
                  </div>
                )}
              </div>
            )}
            {match?.status === 'ambiguous' && !showingAlternative && (
              <AmbiguousNotice
                candidates={candidateList.filter((c) => c.statementId !== primaryId)}
                onSelect={select}
              />
            )}
            <StatementCode statementId={shownId} entry={entry} />
          </>
        )}
      </div>
    </Zone>
  );
}

type EntryQuery = ReturnType<typeof useCatalogEntry>;

/** The source file of the statement, or its catalog snippet when the file is not indexed. */
function StatementCode({ statementId, entry }: { statementId: string; entry: EntryQuery }) {
  const source = useSource(entry.data?.fileId);
  const focus = useCodeFocus();

  if (entry.isPending) {
    return (
      <div className="code-zone__message">
        <Spinner label="Loading statement…" />
      </div>
    );
  }
  if (entry.isError) {
    return (
      <div className="code-zone__message">
        {isNotFound(entry.error) ? (
          <Callout tone="warning">
            This statement is no longer in the catalog: <span className="mono">{statementId}</span>.
            Re-run the analyzer for this code version, or pick another candidate.
          </Callout>
        ) : (
          <Callout tone="danger">Could not load the statement: {errorText(entry.error)}</Callout>
        )}
      </div>
    );
  }

  const statement = entry.data;
  const range = { start: statement.line, end: statement.endLine };
  const method = { start: statement.methodStartLine, end: statement.methodEndLine };

  if (source.isPending) {
    return (
      <div className="code-zone__message">
        <Spinner label="Loading source…" />
      </div>
    );
  }
  if (source.isError && !isNotFound(source.error)) {
    return (
      <div className="code-zone__message">
        <Callout tone="danger">
          Could not load the source file: {errorText(source.error)}
          <Button size="sm" className="callout__action" onClick={() => void source.refetch()}>
            Retry
          </Button>
        </Callout>
      </div>
    );
  }
  if (source.isError) return <SnippetFallback statement={statement} />;

  return (
    <div className="code-zone__editor">
      <Suspense fallback={<EditorLoading />}>
        <CodeViewer
          value={source.data.content}
          path={`${source.data.fileId}/${source.data.filePath}`}
          statement={range}
          method={method}
          focus={focus.request}
          onFocusDone={focus.done}
        />
      </Suspense>
    </div>
  );
}

/** The file is not in `log2code-sources`: show the catalog snippet with the real line numbers. */
function SnippetFallback({ statement }: { statement: CatalogEntryDto }) {
  const focus = useCodeFocus();
  if (!statement.snippet) {
    return (
      <EmptyState
        icon={FileX2}
        title="Source not available"
        description={
          statement.githubUrl
            ? 'Neither the file nor a snippet is stored for this statement. Open it on GitHub.'
            : 'Neither the file nor a snippet is stored for this statement.'
        }
      />
    );
  }
  const lines = statement.snippet.split('\n').length;
  const last = statement.snippetStartLine + lines - 1;
  return (
    <>
      <div className="code-zone__notice">
        <Callout tone="info">
          The source file is not stored. Showing the catalog snippet, lines{' '}
          {statement.snippetStartLine}–{last}
          {statement.githubUrl ? '; the whole file is on GitHub.' : '.'}
        </Callout>
      </div>
      <div className="code-zone__editor">
        <Suspense fallback={<EditorLoading />}>
          <CodeViewer
            value={statement.snippet}
            path={`snippet/${statement.statementId}/${statement.filePath}`}
            firstLine={statement.snippetStartLine}
            statement={{ start: statement.line, end: statement.endLine }}
            focus={focus.request}
            onFocusDone={focus.done}
          />
        </Suspense>
      </div>
    </>
  );
}

/**
 * A line in another file, opened from the context tabs (`?at=`, T29): a caller at its call, or
 * the method a call goes to. The log's statement is one click away ("Back to log").
 */
function LocationCode({ location, onBack }: { location: CodeLocationRef; onBack: () => void }) {
  const source = useSource(location.fileId);
  const line = { start: location.line, end: location.line };
  return (
    <>
      <div className="code-zone__location">
        {source.data ? (
          <FileLocation
            codeUnit={source.data.codeUnit}
            filePath={source.data.filePath}
            member={`line ${location.line}`}
          />
        ) : (
          <span className="code-location" />
        )}
        <div className="code-zone__alternative">
          <Badge tone="accent">Opened from context</Badge>
          <Button
            variant="ghost"
            size="sm"
            icon={<ArrowLeft size={14} aria-hidden="true" />}
            onClick={onBack}
          >
            Back to log
          </Button>
        </div>
      </div>
      {source.isPending ? (
        <div className="code-zone__message">
          <Spinner label="Loading source…" />
        </div>
      ) : source.isError ? (
        <div className="code-zone__message">
          {isNotFound(source.error) ? (
            <Callout tone="warning">
              This source file is not stored: <span className="mono">{location.fileId}</span>.
            </Callout>
          ) : (
            <Callout tone="danger">
              Could not load the source file: {errorText(source.error)}
              <Button size="sm" className="callout__action" onClick={() => void source.refetch()}>
                Retry
              </Button>
            </Callout>
          )}
        </div>
      ) : (
        <div className="code-zone__editor">
          <Suspense fallback={<EditorLoading />}>
            <CodeViewer
              value={source.data.content}
              path={`${source.data.fileId}/${source.data.filePath}`}
              statement={line}
            />
          </Suspense>
        </div>
      )}
    </>
  );
}

function EditorLoading() {
  return (
    <div className="code-zone__message">
      <Spinner label="Loading editor…" />
    </div>
  );
}

/** Ambiguous match: warn, and offer the close candidates right away. */
function AmbiguousNotice({
  candidates,
  onSelect,
}: {
  candidates: CandidateDetailDto[];
  onSelect: (statementId: string) => void;
}) {
  return (
    <div className="code-zone__notice">
      <Callout tone="warning">
        Ambiguous match: other statements score almost the same. This is the best guess.
        {candidates.length > 0 && (
          <span className="code-zone__options">
            {candidates.slice(0, 3).map((c) => (
              <button
                key={c.statementId}
                type="button"
                className="link-button"
                onClick={() => onSelect(c.statementId)}
              >
                {memberLabel(c.classFqn, c.methodName)}
                {c.line !== null && `:${c.line}`}
                <span className="mono"> {c.score.toFixed(2)}</span>
              </button>
            ))}
          </span>
        )}
      </Callout>
    </div>
  );
}

/** No statement matched: say so and list the best candidates below the threshold, if any. */
function UnmatchedView({
  log,
  candidates,
  loading,
  onSelect,
}: {
  log: LogDetail;
  candidates: CandidateDetailDto[];
  loading: boolean;
  onSelect: (statementId: string) => void;
}) {
  const nothingFound = !loading && candidates.length === 0;
  return (
    <div className="unmatched">
      <EmptyState
        icon={Unlink}
        title="No statement matched this log"
        description={
          nothingFound
            ? 'The matcher found no candidate for this message. It may come from code that is not in the catalog.'
            : 'No candidate scored above the threshold. The best ones are below; pick one to see its code.'
        }
      />
      {loading && <Spinner label="Loading candidates…" />}
      {candidates.length > 0 && (
        <ul className="candidate-list" aria-label="Candidates below the threshold">
          {candidates.map((c) => (
            <li key={c.statementId}>
              <button type="button" className="candidate" onClick={() => onSelect(c.statementId)}>
                <span className="candidate__member">
                  {c.classFqn ? memberLabel(c.classFqn, c.methodName) : c.statementId}
                  {c.line !== null && <span className="candidate__line mono">:{c.line}</span>}
                </span>
                {c.template && <span className="candidate__template mono">{c.template}</span>}
                <span className="candidate__score mono">{c.score.toFixed(2)}</span>
              </button>
            </li>
          ))}
        </ul>
      )}
      {log.message && (
        <p className="unmatched__message">
          <span className="unmatched__label">Message</span>
          <span className="mono">{log.message}</span>
        </p>
      )}
    </div>
  );
}
