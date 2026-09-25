// TypeScript mirror of the log2code-api DTOs (org.log2code.api.dto), as documented in
// docs/api.md. JSON field names are camelCase (Jackson default); `Instant` fields are ISO-8601
// strings. Keep this file in sync with the Java records whenever the REST contract changes.

export type Level = 'TRACE' | 'DEBUG' | 'INFO' | 'WARN' | 'ERROR' | 'FATAL' | 'UNKNOWN';
export type MatchStatus = 'matched' | 'ambiguous' | 'unmatched';
export type ConfidenceLevel = 'high' | 'medium' | 'low';
export type NeighborScope = 'service' | 'thread' | 'dataset';
export type SortOrder = 'asc' | 'desc';
export type Verdict = 'correct' | 'incorrect' | 'not_in_catalog';

/** RFC 7807 body returned by the API for every error. */
export interface ProblemDetail {
  type?: string;
  title?: string;
  status?: number;
  detail?: string;
  instance?: string;
}

// ---- logs (T23) ----

export interface LogSummary {
  logId: string;
  timestamp: string | null;
  service: string | null;
  level: string | null;
  thread: string | null;
  loggerRaw: string | null;
  message: string | null;
  status: MatchStatus | null;
  confidence: number | null;
  confidenceLevel: ConfidenceLevel | null;
  hasException: boolean;
  traceId: string | null;
  classFqn: string | null;
  methodName: string | null;
  line: number | null;
}

export interface LogSearchResponse {
  items: LogSummary[];
  nextSearchAfter: string | null;
  total: number;
  tookMs: number;
}

export interface CandidateDto {
  statementId: string;
  score: number;
}

export interface MatchResultDto {
  status: MatchStatus | null;
  statementId: string | null;
  confidence: number | null;
  confidenceLevel: ConfidenceLevel | null;
  candidates: CandidateDto[];
  scoreBreakdown: Record<string, number>;
  args: string[];
  codeUnit: string | null;
  module: string | null;
  classFqn: string | null;
  methodName: string | null;
  filePath: string | null;
  loggingApi: string | null;
  templateKind: string | null;
  line: number | null;
  template: string | null;
  githubUrl: string | null;
}

export interface StackFrameDto {
  className: string | null;
  method: string | null;
  file: string | null;
  line: number | null;
  inProject: boolean;
  codeUnit: string | null;
  fileId: string | null;
  githubUrl: string | null;
}

export interface CausedByDto {
  className: string | null;
  message: string | null;
  frames: StackFrameDto[];
}

export interface ExceptionInfoDto {
  className: string | null;
  rootClass: string | null;
  message: string | null;
  frames: StackFrameDto[];
  causedBy: CausedByDto[];
}

export interface CodeVersionDto {
  name: string | null;
  version: string | null;
}

export interface GroundTruthDto {
  className: string | null;
  method: string | null;
  line: number | null;
  reliable: boolean | null;
}

export interface LogDetail {
  logId: string;
  timestamp: string | null;
  timestampRaw: string | null;
  datasetId: string | null;
  sourceFile: string | null;
  lineNumber: number;
  lineCount: number;
  sequence: number;
  service: string | null;
  module: string | null;
  appName: string | null;
  pid: string | null;
  thread: string | null;
  level: string | null;
  loggerRaw: string | null;
  logger: string | null;
  message: string | null;
  raw: string | null;
  traceId: string | null;
  spanId: string | null;
  exception: ExceptionInfoDto | null;
  code: CodeVersionDto | null;
  match: MatchResultDto | null;
  groundTruth: GroundTruthDto | null;
  parserFormat: string | null;
  ingesterVersion: string | null;
  ingestedAt: string | null;
}

/** Query parameters of `GET /api/logs`; multi-valued ones are sent as repeated parameters. */
export interface LogSearchParams {
  q?: string;
  service?: string[];
  level?: string[];
  status?: MatchStatus[];
  confidence?: ConfidenceLevel[];
  datasetId?: string;
  from?: string;
  to?: string;
  traceId?: string;
  hasException?: boolean;
  statementId?: string;
  size?: number;
  order?: SortOrder;
  /** Opaque cursor from `nextSearchAfter`; passed through as-is (never pre-encoded). */
  searchAfter?: string;
}

// ---- meta (T23) ----

export interface DatasetSummary {
  datasetId: string;
  count: number;
}

export interface CodeUnitSummary {
  kind: string;
  name: string;
  version: string;
  finishedAt: string | null;
}

// ---- catalog, sources, candidates, neighbors, trace, methods (T24) ----

export interface CodeUnitDto {
  type: string;
  name: string;
  version: string;
}

export interface EnclosingBlockDto {
  blockKind: string | null;
  condition: string | null;
  branch: string | null;
  startLine: number;
  endLine: number;
}

export interface ConditionDto {
  kind: string;
  text: string;
  line: number;
  negated: boolean;
}

export interface EarlyExitDto {
  text: string;
  line: number;
  exitKind: string;
}

export interface PrecedingStatementDto {
  kind: string;
  text: string;
  line: number;
}

export interface CallSiteDto {
  line: number;
  text: string;
  target: string | null;
  targetMethodId: string | null;
  resolved: boolean;
}

export interface ControlContextDto {
  conditions: ConditionDto[];
  earlyExits: EarlyExitDto[];
  preceding: PrecedingStatementDto[];
  callsBefore: CallSiteDto[];
}

export interface CatalogEntryDto {
  statementId: string;
  logicalId: string;
  codeUnit: CodeUnitDto;
  module: string | null;
  service: string | null;
  filePath: string;
  fileId: string;
  packageName: string | null;
  classFqn: string;
  classBinary: string | null;
  methodName: string;
  methodSignature: string;
  methodId: string | null;
  inLambda: boolean;
  line: number;
  endLine: number;
  column: number;
  methodStartLine: number;
  methodEndLine: number;
  loggingApi: string;
  detection: string;
  loggerExpr: string | null;
  loggerName: string | null;
  loggerNameKind: string | null;
  level: string;
  levelDynamic: boolean;
  templateRaw: string | null;
  template: string | null;
  templateKind: string;
  unsupportedReason: string | null;
  constantTokens: string[];
  literalLength: number;
  placeholderCount: number;
  hasThrowableArg: boolean;
  enclosing: EnclosingBlockDto | null;
  control: ControlContextDto | null;
  snippet: string | null;
  snippetStartLine: number;
  githubUrl: string | null;
  analyzerVersion: string | null;
  analyzedAt: string | null;
}

export interface CandidateDetailDto {
  statementId: string;
  score: number;
  classFqn: string | null;
  methodName: string | null;
  filePath: string | null;
  line: number | null;
  template: string | null;
}

export interface SourceFileDto {
  fileId: string;
  codeUnit: CodeUnitDto;
  module: string | null;
  filePath: string;
  content: string;
  lineCount: number;
}

export interface SourceLookupResponse {
  fileId: string;
}

export interface NeighborsResponse {
  before: LogSummary[];
  current: LogSummary;
  after: LogSummary[];
}

export interface TraceResponse {
  items: LogSummary[];
  /** `null` when the log has a trace id, `"no-trace-id"` otherwise. */
  reason: string | null;
}

export interface CallEdgeDto {
  line: number;
  text: string;
  targetMethodId: string | null;
  targetFqn: string | null;
  resolved: boolean;
  viaInterface: boolean;
}

export interface CallerRefDto {
  methodId: string;
  classFqn: string;
  methodName: string;
  fileId: string;
  line: number;
}

/** Row of `/methods/{id}/callers`; `callerCount`/`annotations` belong to the caller itself. */
export interface CallerDto extends CallerRefDto {
  callerCount: number;
  annotations: string[];
}

export interface MethodDetailDto {
  methodId: string;
  codeUnit: CodeUnitDto;
  module: string | null;
  service: string | null;
  fileId: string;
  filePath: string;
  classFqn: string;
  classBinary: string | null;
  methodName: string;
  methodSignature: string;
  startLine: number;
  endLine: number;
  annotations: string[];
  hasLogStatements: boolean;
  calls: CallEdgeDto[];
  calledBy: CallerRefDto[];
  callerCount: number;
}

// ---- context bundle and labels (T25) ----

export interface ContextMatchDto {
  status: MatchStatus | null;
  confidence: number | null;
  confidenceLevel: ConfidenceLevel | null;
  candidates: CandidateDto[];
}

export interface ContextCodeDto {
  filePath: string;
  githubUrl: string | null;
  methodSource: string | null;
  methodStartLine: number;
  snippet: string | null;
  snippetStartLine: number;
}

export interface ContextCallerDto {
  classFqn: string;
  methodName: string;
  filePath: string | null;
  line: number;
  snippet: string | null;
}

export interface ContextStackFrameDto extends StackFrameDto {
  snippet: string | null;
}

export interface ContextCausedByDto {
  className: string | null;
  message: string | null;
  frames: ContextStackFrameDto[];
}

export interface ContextExceptionDto {
  className: string | null;
  rootClass: string | null;
  message: string | null;
  frames: ContextStackFrameDto[];
  causedBy: ContextCausedByDto[];
}

export interface ContextNeighborsDto {
  before: LogSummary[];
  after: LogSummary[];
}

export interface ContextBundleDto {
  schemaVersion: number;
  log: LogDetail;
  match: ContextMatchDto;
  statement: CatalogEntryDto | null;
  code: ContextCodeDto | null;
  control: ControlContextDto | null;
  callers: ContextCallerDto[];
  exception: ContextExceptionDto | null;
  neighbors: ContextNeighborsDto;
  trace: TraceResponse;
}

export interface LabelDto {
  logId: string;
  datasetId: string | null;
  verdict: Verdict;
  correctStatementId: string | null;
  predictedStatementId: string | null;
  note: string | null;
  labeledAt: string | null;
}

export interface LabelRequest {
  verdict: Verdict;
  correctStatementId?: string | null;
  note?: string | null;
}

export interface LabelSearchResponse {
  items: LabelDto[];
  nextSearchAfter: string | null;
  total: number;
}

export interface LabelListParams {
  datasetId?: string;
  verdict?: Verdict;
  size?: number;
  searchAfter?: string;
}

export interface ReviewQueueParams {
  datasetId?: string;
  limit?: number;
  seed?: number;
}
