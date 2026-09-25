import type { ConfidenceLevel, MatchStatus } from '../../api/types';

export type MatchConfidence = ConfidenceLevel | 'ambiguous' | 'unmatched';

/** Collapses match status and confidence into the one label the UI shows. */
export function matchConfidence(
  status: MatchStatus | null,
  confidenceLevel: ConfidenceLevel | null,
): MatchConfidence {
  if (status === 'ambiguous') return 'ambiguous';
  if (status !== 'matched' || !confidenceLevel) return 'unmatched';
  return confidenceLevel;
}
