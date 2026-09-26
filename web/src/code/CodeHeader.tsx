import { Check, ChevronDown, ExternalLink } from 'lucide-react';
import { DropdownMenu } from 'radix-ui';
import type {
  CandidateDetailDto,
  CatalogEntryDto,
  CodeUnitDto,
  MatchResultDto,
} from '../api/types';
import { Badge, Button, ConfidenceBadge, Tooltip, cx, matchConfidence } from '../components/ui';
import { memberLabel, shortVersion } from './labels';
import { breakdownRows, formatPoints } from './scoreBreakdown';

/** Code unit, file path, version and `Class#method` of the statement shown (design.md §5.2). */
export function CodeLocation({ entry }: { entry: CatalogEntryDto }) {
  return (
    <FileLocation
      codeUnit={entry.codeUnit}
      filePath={entry.filePath}
      member={memberLabel(entry.classFqn, entry.methodName, entry.packageName ?? undefined)}
      memberTitle={`${entry.classFqn}#${entry.methodSignature}`}
    />
  );
}

/** The same row for any stored file, e.g. one opened from the context tabs (T29). */
export function FileLocation({
  codeUnit,
  filePath,
  member,
  memberTitle,
}: {
  codeUnit: CodeUnitDto;
  filePath: string;
  member: string;
  memberTitle?: string;
}) {
  const slash = filePath.lastIndexOf('/');
  const dir = slash >= 0 ? filePath.slice(0, slash + 1) : '';
  const file = filePath.slice(slash + 1);
  const library = codeUnit.type !== 'project';
  return (
    <div className="code-location">
      <Badge>{library ? 'library' : 'project'}</Badge>
      <span className="code-location__unit mono" title={codeUnit.name}>
        {codeUnit.name}
      </span>
      <Badge>
        <span className="mono" title={codeUnit.version}>
          {shortVersion({ codeUnit })}
        </span>
      </Badge>
      <span className="code-location__dir mono" title={filePath}>
        <bdi>{dir}</bdi>
      </span>
      <span className="code-location__file mono" title={filePath}>
        {file}
      </span>
      <span className="code-location__member" title={memberTitle}>
        {member}
      </span>
    </div>
  );
}

/** Readable `score_breakdown` for the tooltip of the confidence badge. */
export function ScoreBreakdown({ match }: { match: MatchResultDto }) {
  const { rows, score } = breakdownRows(match.scoreBreakdown);
  return (
    <table className="breakdown">
      <caption className="breakdown__caption">Score breakdown</caption>
      <tbody>
        {rows.map((row) => (
          <tr key={row.key}>
            <td>{row.label}</td>
            <td
              className={cx(
                'breakdown__points mono',
                row.points < 0 && 'breakdown__points--negative',
              )}
            >
              {formatPoints(row.points)}
            </td>
          </tr>
        ))}
      </tbody>
      <tfoot>
        <tr>
          <td>Score</td>
          <td className="breakdown__points mono">{score.toFixed(2)}</td>
        </tr>
        {match.status === 'ambiguous' && match.confidence !== null && (
          <tr>
            <td>Confidence (ambiguous, × 0.6)</td>
            <td className="breakdown__points mono">{match.confidence.toFixed(2)}</td>
          </tr>
        )}
      </tfoot>
    </table>
  );
}

/** The match confidence; hovering or focusing it shows the score breakdown. */
export function MatchConfidence({ match }: { match: MatchResultDto }) {
  const value = matchConfidence(match.status, match.confidenceLevel);
  const badge = <ConfidenceBadge value={value} score={match.confidence ?? undefined} />;
  if (Object.keys(match.scoreBreakdown ?? {}).length === 0) return badge;
  return (
    <Tooltip content={<ScoreBreakdown match={match} />}>
      <button type="button" className="confidence-button">
        {badge}
      </button>
    </Tooltip>
  );
}

/** Score of a candidate shown as an alternative (the matcher keeps a breakdown only for the top). */
export function AlternativeScore({ score }: { score: number }) {
  return (
    <Tooltip content="Score of this candidate. The breakdown is kept only for the top match.">
      <button type="button" className="confidence-button">
        <span className="confidence confidence--unmatched">
          score <span className="confidence__score mono">{score.toFixed(2)}</span>
        </span>
      </button>
    </Tooltip>
  );
}

interface AlternativesMenuProps {
  candidates: CandidateDetailDto[];
  /** The top match (`match.statementId`); null when nothing matched. */
  primaryId: string | null;
  /** The statement shown now (top match or alternative). */
  shownId: string | null;
  onSelect: (statementId: string) => void;
  /** Ambiguous match: the menu is drawn with the warning tone. */
  attention?: boolean;
}

/** "Alternatives (n) ▾": the other candidates of the match; picking one shows its code. */
export function AlternativesMenu({
  candidates,
  primaryId,
  shownId,
  onSelect,
  attention,
}: AlternativesMenuProps) {
  const count = candidates.filter((c) => c.statementId !== primaryId).length;
  if (count === 0) return null;
  return (
    <DropdownMenu.Root modal={false}>
      <DropdownMenu.Trigger asChild>
        <Button
          size="sm"
          className={cx('alternatives-trigger', attention && 'alternatives-trigger--attention')}
        >
          Alternatives ({count})
          <ChevronDown size={14} aria-hidden="true" className="filter-trigger__chevron" />
        </Button>
      </DropdownMenu.Trigger>
      <DropdownMenu.Portal>
        <DropdownMenu.Content
          className="menu alternatives-menu"
          aria-label="Alternatives"
          align="end"
          sideOffset={4}
          collisionPadding={8}
        >
          <DropdownMenu.RadioGroup value={shownId ?? ''} onValueChange={(value) => onSelect(value)}>
            {candidates.map((candidate) => (
              <DropdownMenu.RadioItem
                key={candidate.statementId}
                value={candidate.statementId}
                className="menu__item alternatives-menu__item"
              >
                <span className="menu__check" aria-hidden="true">
                  <DropdownMenu.ItemIndicator>
                    <Check size={14} />
                  </DropdownMenu.ItemIndicator>
                </span>
                <span className="alternatives-menu__text">
                  <span className="alternatives-menu__member">
                    <span className="alternatives-menu__name">
                      {candidate.classFqn
                        ? memberLabel(candidate.classFqn, candidate.methodName)
                        : 'Statement not in the catalog'}
                    </span>
                    {candidate.line !== null && (
                      <span className="alternatives-menu__line mono">:{candidate.line}</span>
                    )}
                    {candidate.statementId === primaryId && <Badge tone="accent">top match</Badge>}
                  </span>
                  {candidate.template && (
                    <span className="alternatives-menu__template mono">{candidate.template}</span>
                  )}
                </span>
                <span className="alternatives-menu__score mono">{candidate.score.toFixed(2)}</span>
              </DropdownMenu.RadioItem>
            ))}
          </DropdownMenu.RadioGroup>
        </DropdownMenu.Content>
      </DropdownMenu.Portal>
    </DropdownMenu.Root>
  );
}

/** "GitHub ↗": the statement at its exact commit or version, in a new tab. */
export function GithubLink({ url }: { url: string }) {
  return (
    <a
      className="btn btn--ghost btn--sm"
      href={url}
      target="_blank"
      rel="noopener noreferrer"
      title="Open this line on GitHub in a new tab"
    >
      GitHub
      <ExternalLink size={14} aria-hidden="true" />
    </a>
  );
}
