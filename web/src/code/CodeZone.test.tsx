import { act, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import type {
  CandidateDetailDto,
  CatalogEntryDto,
  LogDetail,
  MatchResultDto,
  SourceFileDto,
} from '../api/types';
import { json, mockFetch, problem } from '../test/fetchMock';
import { logDetail, logSummary, logsRoute, renderApp, searchOf, zone } from '../test/renderApp';
import type { CodeViewerProps } from './codeViewerProps';

// Monaco needs a real browser (layout, workers); the viewer is replaced by a stand-in that exposes
// what it was asked to show. The real editor is checked in the browser (progress.md, T28).
vi.mock('./CodeViewer', () => ({
  default: ({ value, path, firstLine = 1, statement, method }: CodeViewerProps) => (
    <pre
      data-testid="code-viewer"
      data-path={path}
      data-first-line={firstLine}
      data-statement={`${statement.start}-${statement.end}`}
      data-method={method ? `${method.start}-${method.end}` : ''}
    >
      {value}
    </pre>
  ),
}));

const SHA = '3858f9c630cf989bb6809a86edf47c2be78dc9f1';
const OWNER_PATH =
  'spring-petclinic-customers-service/src/main/java/org/springframework/samples/petclinic/customers/web/OwnerResource.java';
const OWNER_SOURCE = Array.from({ length: 120 }, (_, i) => `line ${i + 1}`).join('\n');

function entry(overrides: Partial<CatalogEntryDto> = {}): CatalogEntryDto {
  return {
    statementId: 'stmt-1',
    logicalId: 'logical-1',
    codeUnit: { type: 'project', name: 'spring-petclinic-microservices', version: SHA },
    module: 'spring-petclinic-customers-service',
    service: 'customers-service',
    filePath: OWNER_PATH,
    fileId: 'file-owner',
    packageName: 'org.springframework.samples.petclinic.customers.web',
    classFqn: 'org.springframework.samples.petclinic.customers.web.OwnerResource',
    classBinary: 'org.springframework.samples.petclinic.customers.web.OwnerResource',
    methodName: 'updateOwner',
    methodSignature: 'updateOwner(int,OwnerRequest)',
    methodId: 'method-1',
    inLambda: false,
    line: 89,
    endLine: 89,
    column: 9,
    methodStartLine: 84,
    methodEndLine: 92,
    loggingApi: 'slf4j',
    detection: 'typed',
    loggerExpr: 'log',
    loggerName: 'org.springframework.samples.petclinic.customers.web.OwnerResource',
    loggerNameKind: 'class_literal',
    level: 'INFO',
    levelDynamic: false,
    templateRaw: '"Saving owner {}"',
    template: 'Saving owner {}',
    templateKind: 'placeholders',
    unsupportedReason: null,
    constantTokens: ['saving', 'owner'],
    literalLength: 13,
    placeholderCount: 1,
    hasThrowableArg: false,
    enclosing: null,
    control: null,
    snippet: 'line 86\nline 87\nline 88\nline 89\nline 90',
    snippetStartLine: 86,
    githubUrl: `https://github.com/spring-petclinic/spring-petclinic-microservices/blob/${SHA}/${OWNER_PATH}#L89`,
    analyzerVersion: '0.1.0',
    analyzedAt: null,
    ...overrides,
  };
}

const LIBRARY = entry({
  statementId: 'stmt-lib',
  codeUnit: { type: 'dependency', name: 'com.netflix.eureka:eureka-client', version: '2.0.5' },
  module: 'com.netflix.eureka:eureka-client',
  service: null,
  filePath: 'com/netflix/discovery/DiscoveryClient.java',
  fileId: 'file-discovery',
  packageName: 'com.netflix.discovery',
  classFqn: 'com.netflix.discovery.DiscoveryClient',
  methodName: 'shutdown',
  methodSignature: 'shutdown()',
  line: 887,
  endLine: 887,
  methodStartLine: 883,
  methodEndLine: 909,
  githubUrl:
    'https://github.com/Netflix/eureka/blob/v2.0.5/eureka-client/src/main/java/com/netflix/discovery/DiscoveryClient.java#L887',
});

const ALT_A = entry({
  statementId: 'stmt-alt-a',
  methodName: 'createOwner',
  methodSignature: 'createOwner(OwnerRequest)',
  line: 60,
  endLine: 61,
  methodStartLine: 55,
  methodEndLine: 65,
  githubUrl: 'https://github.com/example/alt-a#L60',
});

const source = (fileId: string, content: string, filePath = OWNER_PATH): SourceFileDto => ({
  fileId,
  codeUnit: { type: 'project', name: 'spring-petclinic-microservices', version: SHA },
  module: null,
  filePath,
  content,
  lineCount: content.split('\n').length,
});

const candidate = (e: CatalogEntryDto, score: number): CandidateDetailDto => ({
  statementId: e.statementId,
  score,
  classFqn: e.classFqn,
  methodName: e.methodName,
  filePath: e.filePath,
  line: e.line,
  template: e.template,
});

function matchOf(overrides: Partial<MatchResultDto> = {}): MatchResultDto {
  return {
    ...(logDetail().match as MatchResultDto),
    scoreBreakdown: {
      regex_full: 0.45,
      specificity: 0.0928,
      logger_exact: 0.2,
      level_equal: 0.1,
      throwable_consistent: 0.05,
    },
    confidence: 0.8928,
    ...overrides,
  };
}

interface Setup {
  log: LogDetail;
  entries?: CatalogEntryDto[];
  sources?: SourceFileDto[];
  candidates?: CandidateDetailDto[];
  path?: string;
  list?: ReturnType<typeof logSummary>[];
}

function show({ log, entries = [], sources = [], candidates = [], path, list = [] }: Setup) {
  const fetchSpy = mockFetch(logsRoute(list), (url) => {
    const p = url.pathname;
    if (p === `/api/logs/${log.logId}`) return json(log);
    if (p === `/api/logs/${log.logId}/candidates`) return json(candidates);
    const other = list.find((s) => p === `/api/logs/${s.logId}`);
    if (other) return json(logDetail({ logId: other.logId, match: null }));
    if (p.startsWith('/api/catalog/')) {
      const found = entries.find((e) => p === `/api/catalog/${e.statementId}`);
      return found ? json(found) : problem(404, 'statement not found');
    }
    if (p.startsWith('/api/sources/')) {
      const found = sources.find((s) => p === `/api/sources/${s.fileId}`);
      return found ? json(found) : problem(404, 'source file not found');
    }
    if (p.startsWith('/api/meta')) return json([]);
    return undefined;
  });
  const router = renderApp(path ?? `/logs/${log.logId}?datasetId=smoke-01`);
  return { router, fetchSpy };
}

const code = () => zone('Code');
const viewer = () => within(code()).findByTestId('code-viewer');

describe('code zone', () => {
  it('without a selected log it asks to pick one', () => {
    mockFetch(logsRoute([]), (url) =>
      url.pathname.startsWith('/api/meta') ? json([]) : undefined,
    );
    renderApp('/');
    expect(within(code()).getByText('No log selected')).toBeInTheDocument();
  });

  it('shows the source file of a project statement at its line (AC1)', async () => {
    const log = logDetail({ match: matchOf() });
    show({ log, entries: [entry()], sources: [source('file-owner', OWNER_SOURCE)] });

    const view = await viewer();
    expect(view).toHaveAttribute('data-statement', '89-89');
    expect(view).toHaveAttribute('data-method', '84-92');
    expect(view).toHaveAttribute('data-first-line', '1');
    expect(view.textContent).toBe(OWNER_SOURCE);

    const location = within(code()).getByText('OwnerResource.java').closest('.code-location');
    expect(location).not.toBeNull();
    const row = within(location as HTMLElement);
    expect(row.getByText('project')).toBeInTheDocument();
    expect(row.getByText('spring-petclinic-microservices')).toBeInTheDocument();
    expect(row.getByText('3858f9c')).toHaveAttribute('title', SHA);
    expect(row.getByText('OwnerResource#updateOwner')).toHaveAttribute(
      'title',
      'org.springframework.samples.petclinic.customers.web.OwnerResource#updateOwner(int,OwnerRequest)',
    );
    expect(row.getAllByTitle(OWNER_PATH).map((e) => e.textContent)).toEqual([
      OWNER_PATH.slice(0, OWNER_PATH.lastIndexOf('/') + 1),
      'OwnerResource.java',
    ]);

    const github = within(code()).getByRole('link', { name: /GitHub/ });
    expect(github).toHaveAttribute('href', entry().githubUrl);
    expect(github).toHaveAttribute('target', '_blank');
    expect(github).toHaveAttribute('rel', 'noopener noreferrer');

    expect(within(code()).getByText('high')).toBeInTheDocument();
    expect(within(code()).queryByText(/Showing alternative/)).toBeNull();
    // Only one candidate (the match itself): no alternatives menu.
    expect(within(code()).queryByRole('button', { name: /Alternatives/ })).toBeNull();
  });

  it('shows a library statement from its sources jar with artifact and version (AC2)', async () => {
    const content = Array.from({ length: 950 }, (_, i) => `l${i + 1}`).join('\n');
    const log = logDetail({ match: matchOf({ statementId: 'stmt-lib' }) });
    show({
      log,
      entries: [LIBRARY],
      sources: [source('file-discovery', content, LIBRARY.filePath)],
    });

    const view = await viewer();
    expect(view).toHaveAttribute('data-statement', '887-887');
    expect(view).toHaveAttribute(
      'data-path',
      'file-discovery/com/netflix/discovery/DiscoveryClient.java',
    );
    const row = within(code().querySelector('.code-location') as HTMLElement);
    expect(row.getByText('library')).toBeInTheDocument();
    expect(row.getByText('com.netflix.eureka:eureka-client')).toBeInTheDocument();
    expect(row.getByText('2.0.5')).toBeInTheDocument();
    expect(row.getByText('DiscoveryClient.java')).toBeInTheDocument();
    expect(row.getByText('DiscoveryClient#shutdown')).toBeInTheDocument();
    expect(within(code()).getByRole('link', { name: /GitHub/ })).toHaveAttribute(
      'href',
      LIBRARY.githubUrl,
    );
  });

  it('the score breakdown is readable in the confidence tooltip', async () => {
    const log = logDetail({ match: matchOf() });
    show({ log, entries: [entry()], sources: [source('file-owner', OWNER_SOURCE)] });
    await viewer();

    await userEvent.hover(within(code()).getByRole('button', { name: /high/ }));
    const tooltip = await screen.findByRole('tooltip');
    expect(tooltip).toHaveTextContent('Message matches the template');
    expect(tooltip).toHaveTextContent('+0.45');
    expect(tooltip).toHaveTextContent('Logger is this class');
    expect(tooltip).toHaveTextContent('Score0.89');
  });

  describe('alternatives (AC3)', () => {
    const setup = (path?: string) => {
      const log = logDetail({
        match: matchOf({
          candidates: [
            { statementId: 'stmt-1', score: 0.89 },
            { statementId: 'stmt-alt-a', score: 0.61 },
            { statementId: 'stmt-missing', score: 0.4 },
          ],
        }),
      });
      const altSource = Array.from({ length: 70 }, (_, i) => `alt ${i + 1}`).join('\n');
      return show({
        log,
        path,
        entries: [entry(), { ...ALT_A, fileId: 'file-alt' }],
        sources: [source('file-owner', OWNER_SOURCE), source('file-alt', altSource)],
        candidates: [
          candidate(entry(), 0.89),
          candidate(ALT_A, 0.61),
          {
            statementId: 'stmt-missing',
            score: 0.4,
            classFqn: null,
            methodName: null,
            filePath: null,
            line: null,
            template: null,
          },
        ],
      });
    };

    it('picking an alternative shows its code, and going back shows the match again', async () => {
      const { router } = setup();
      await viewer();

      await userEvent.click(
        await within(code()).findByRole('button', { name: 'Alternatives (2)' }),
      );
      const menu = await screen.findByRole('menu');
      const items = within(menu).getAllByRole('menuitemradio');
      expect(items).toHaveLength(3);
      expect(items[0]).toHaveTextContent('OwnerResource#updateOwner:89');
      expect(items[0]).toHaveTextContent('top match');
      expect(items[0]).toHaveAttribute('aria-checked', 'true');
      expect(items[2]).toHaveTextContent('Statement not in the catalog');

      await userEvent.click(items[1]);
      expect(searchOf(router).get('alt')).toBe('stmt-alt-a');
      expect(searchOf(router).get('datasetId')).toBe('smoke-01');
      await vi.waitFor(async () =>
        expect(await viewer()).toHaveAttribute('data-statement', '60-61'),
      );
      expect((await viewer()).textContent).toContain('alt 60');
      expect(within(code()).getByText('Showing alternative')).toBeInTheDocument();
      expect(within(code()).getByText('OwnerResource#createOwner')).toBeInTheDocument();
      expect(within(code()).getByRole('button', { name: /score 0.61/ })).toBeInTheDocument();
      expect(within(code()).getByRole('link', { name: /GitHub/ })).toHaveAttribute(
        'href',
        ALT_A.githubUrl,
      );

      await userEvent.click(within(code()).getByRole('button', { name: 'Back to top match' }));
      expect(searchOf(router).has('alt')).toBe(false);
      await vi.waitFor(async () =>
        expect(await viewer()).toHaveAttribute('data-statement', '89-89'),
      );
      expect(within(code()).queryByText('Showing alternative')).toBeNull();
      expect(within(code()).getByText('high')).toBeInTheDocument();
    });

    it('the browser Back button returns from an alternative to the match', async () => {
      const { router } = setup();
      await viewer();
      await userEvent.click(
        await within(code()).findByRole('button', { name: 'Alternatives (2)' }),
      );
      await userEvent.click(
        within(await screen.findByRole('menu')).getAllByRole('menuitemradio')[1],
      );
      expect(searchOf(router).get('alt')).toBe('stmt-alt-a');

      await act(() => router.navigate(-1));
      expect(searchOf(router).has('alt')).toBe(false);
      await vi.waitFor(async () =>
        expect(await viewer()).toHaveAttribute('data-statement', '89-89'),
      );
    });

    it('a deep link with ?alt= opens the alternative', async () => {
      setup('/logs/dcf3be3719357b10ac07fe9b2aed1bd4?datasetId=smoke-01&alt=stmt-alt-a');
      await vi.waitFor(async () =>
        expect(await viewer()).toHaveAttribute('data-statement', '60-61'),
      );
      expect(within(code()).getByText('Showing alternative')).toBeInTheDocument();
    });

    it('an alternative that is gone from the catalog is reported', async () => {
      setup('/logs/dcf3be3719357b10ac07fe9b2aed1bd4?alt=stmt-missing');
      expect(
        await within(code()).findByText(/This statement is no longer in the catalog/),
      ).toBeInTheDocument();
      expect(within(code()).getByRole('button', { name: 'Back to top match' })).toBeInTheDocument();
    });
  });

  it('selecting another log drops the alternative of the previous one', async () => {
    const log = logDetail({ match: matchOf() });
    const list = [logSummary(1), logSummary(2)];
    const { router } = show({
      log,
      list,
      path: `/logs/${log.logId}?datasetId=smoke-01&alt=stmt-alt-a`,
      entries: [entry()],
    });
    const logs = zone('Logs');
    await userEvent.click(await within(logs).findByText('message 2'));
    expect(router.state.location.pathname).toBe('/logs/log-0002');
    expect(searchOf(router).has('alt')).toBe(false);
    expect(searchOf(router).get('datasetId')).toBe('smoke-01');
  });

  it('an ambiguous match warns and offers the close candidates', async () => {
    const log = logDetail({
      match: matchOf({
        status: 'ambiguous',
        confidence: 0.21,
        confidenceLevel: 'low',
        scoreBreakdown: { regex_full: 0.45, logger_hierarchy: 0.15, level_conflict: -0.3 },
        candidates: [
          { statementId: 'stmt-1', score: 0.35 },
          { statementId: 'stmt-alt-a', score: 0.35 },
        ],
      }),
    });
    const { router } = show({
      log,
      entries: [entry(), ALT_A],
      sources: [source('file-owner', OWNER_SOURCE)],
      candidates: [candidate(entry(), 0.35), candidate(ALT_A, 0.35)],
    });

    await viewer();
    const warning = await within(code()).findByText(/Ambiguous match/);
    expect(warning.closest('.callout')).toHaveClass('callout--warning');
    expect(within(code()).getByText('ambiguous')).toBeInTheDocument();
    expect(within(code()).getByRole('button', { name: 'Alternatives (1)' })).toHaveClass(
      'alternatives-trigger--attention',
    );

    await userEvent.hover(within(code()).getByRole('button', { name: /ambiguous/ }));
    const tooltip = await screen.findByRole('tooltip');
    expect(tooltip).toHaveTextContent('Level differs');
    expect(tooltip).toHaveTextContent('−0.30');
    expect(tooltip).toHaveTextContent('Confidence (ambiguous, × 0.6)0.21');

    await userEvent.click(
      within(warning.closest('.callout') as HTMLElement).getByRole('button', {
        name: /OwnerResource#createOwner:60/,
      }),
    );
    expect(searchOf(router).get('alt')).toBe('stmt-alt-a');
  });

  it('an unmatched log lists the candidates below the threshold; one can be opened', async () => {
    const low = entry({ ...ALT_A, fileId: 'file-alt' });
    const log = logDetail({
      match: matchOf({
        status: 'unmatched',
        statementId: null,
        confidence: null,
        confidenceLevel: null,
        scoreBreakdown: {},
        candidates: [{ statementId: 'stmt-alt-a', score: 0.02 }],
      }),
    });
    const { router } = show({
      log,
      entries: [low],
      sources: [source('file-alt', OWNER_SOURCE)],
      candidates: [candidate(low, 0.02)],
    });

    expect(await within(code()).findByText('No statement matched this log')).toBeInTheDocument();
    expect(within(code()).getByText('unmatched')).toBeInTheDocument();
    const list = await within(code()).findByRole('list', {
      name: 'Candidates below the threshold',
    });
    const option = within(list).getByRole('button', { name: /OwnerResource#createOwner:60/ });
    expect(option).toHaveTextContent('0.02');
    expect(within(code()).queryByTestId('code-viewer')).toBeNull();

    await userEvent.click(option);
    expect(searchOf(router).get('alt')).toBe('stmt-alt-a');
    expect(await viewer()).toHaveAttribute('data-statement', '60-61');
    expect(within(code()).getByText('Showing alternative')).toBeInTheDocument();

    await userEvent.click(within(code()).getByRole('button', { name: 'Back to message' }));
    expect(await within(code()).findByText('No statement matched this log')).toBeInTheDocument();
  });

  it('an unmatched log without candidates says that nothing was found', async () => {
    const log = logDetail({
      match: matchOf({
        status: 'unmatched',
        statementId: null,
        confidence: null,
        confidenceLevel: null,
        scoreBreakdown: {},
        candidates: [],
      }),
    });
    const { fetchSpy } = show({ log });
    expect(
      await within(code()).findByText(/The matcher found no candidate for this message/),
    ).toBeInTheDocument();
    expect(within(code()).queryByRole('button', { name: /Alternatives/ })).toBeNull();
    const asked = fetchSpy.mock.calls.map(([input]) => String(input));
    expect(asked.some((u) => u.includes('/candidates'))).toBe(false);
  });

  it('without the source file it shows the catalog snippet with the real line numbers', async () => {
    const log = logDetail({ match: matchOf() });
    show({ log, entries: [entry()], sources: [] });

    const view = await viewer();
    expect(view).toHaveAttribute('data-first-line', '86');
    expect(view).toHaveAttribute('data-statement', '89-89');
    expect(view.textContent).toBe(entry().snippet);
    expect(
      within(code()).getByText(/Showing the catalog snippet, lines 86–90/),
    ).toBeInTheDocument();
    expect(within(code()).getByRole('link', { name: /GitHub/ })).toBeInTheDocument();
  });

  it('without a source file and a snippet it says the source is not available', async () => {
    const log = logDetail({ match: matchOf() });
    show({ log, entries: [entry({ snippet: null })], sources: [] });
    expect(await within(code()).findByText('Source not available')).toBeInTheDocument();
    expect(within(code()).queryByTestId('code-viewer')).toBeNull();
  });

  it('reports a failing source request with a retry', async () => {
    const log = logDetail({ match: matchOf() });
    mockFetch(logsRoute([]), (url) => {
      if (url.pathname === `/api/logs/${log.logId}`) return json(log);
      if (url.pathname === '/api/catalog/stmt-1') return json(entry());
      if (url.pathname.startsWith('/api/sources/'))
        return problem(502, 'OpenSearch is not reachable');
      if (url.pathname.startsWith('/api/meta')) return json([]);
      return undefined;
    });
    renderApp(`/logs/${log.logId}`);
    const alert = await within(code()).findByRole('alert');
    expect(alert).toHaveTextContent('Could not load the source file: OpenSearch is not reachable');
    expect(within(alert).getByRole('button', { name: 'Retry' })).toBeInTheDocument();
  });
});
