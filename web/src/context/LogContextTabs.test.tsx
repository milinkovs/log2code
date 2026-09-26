import { act, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type {
  ExceptionInfoDto,
  LogDetail,
  NeighborsResponse,
  SourceFileDto,
  StackFrameDto,
  TraceResponse,
} from '../api/types';
import type { CodeViewerProps } from '../code/codeViewerProps';
import { OWNER_SOURCE, entry, source } from '../test/catalogFixtures';
import { json, mockFetch, problem } from '../test/fetchMock';
import { logDetail, logSummary, logsRoute, renderApp, searchOf, zone } from '../test/renderApp';
import { CONTEXT_TAB_KEY } from './contextTab';
import { HIDE_LIBRARY_KEY, NEIGHBOR_COUNT_KEY, NEIGHBOR_SCOPE_KEY } from './contextPrefs';

// The T30 tabs: stack trace, neighbors and same request. Monaco needs a real browser, so the code
// zone renders a stand-in that exposes the file and the highlighted line.
vi.mock('../code/CodeViewer', () => ({
  default: function CodeViewerStandIn({ value, path, statement }: CodeViewerProps) {
    return (
      <pre
        data-testid="code-viewer"
        data-path={path}
        data-statement={`${statement.start}-${statement.end}`}
      >
        {value}
      </pre>
    );
  },
}));

const PKG = 'org.springframework.samples.petclinic.customers.web';
const CONTROLLER_PATH =
  'spring-petclinic-customers-service/src/main/java/org/springframework/samples/petclinic/customers/web/OwnerController.java';
const SPRING_PATH = 'org/springframework/web/servlet/FrameworkServlet.java';

function frame(overrides: Partial<StackFrameDto>): StackFrameDto {
  return {
    className: 'java.lang.Thread',
    method: 'run',
    file: 'Thread.java',
    line: 840,
    inProject: false,
    codeUnit: null,
    fileId: null,
    githubUrl: null,
    ...overrides,
  };
}

/** Library runs around two project frames; one library frame has its sources, one only GitHub. */
const FRAMES: StackFrameDto[] = [
  frame({
    className: 'jdk.internal.reflect.NativeConstructorAccessorImpl',
    method: 'newInstance0',
    file: null,
    line: null,
  }),
  frame({
    className: 'java.lang.reflect.Constructor',
    method: 'newInstance',
    file: 'Constructor.java',
    line: 481,
  }),
  frame({
    className: `${PKG}.OwnerController`,
    method: 'show',
    file: 'OwnerController.java',
    line: 40,
    inProject: true,
    codeUnit: 'spring-petclinic-microservices',
    fileId: 'file-controller',
  }),
  frame({
    className: 'org.springframework.web.servlet.FrameworkServlet',
    method: 'doGet',
    file: 'FrameworkServlet.java',
    line: 892,
    codeUnit: 'org.springframework:spring-webmvc',
    fileId: 'file-servlet',
    githubUrl:
      'https://github.com/spring-projects/spring-framework/blob/v7.0.2/FrameworkServlet.java#L892',
  }),
  frame({
    className: 'jakarta.servlet.http.HttpServlet',
    method: 'service',
    file: 'HttpServlet.java',
    line: 622,
    codeUnit: 'org.apache.tomcat.embed:tomcat-embed-core',
    githubUrl: 'https://github.com/apache/tomcat/blob/11.0.15/HttpServlet.java#L622',
  }),
  frame({}),
];

const EXCEPTION: ExceptionInfoDto = {
  className: 'java.lang.IllegalStateException',
  rootClass: 'java.sql.SQLException',
  message: 'Owner lookup failed',
  frames: FRAMES,
  causedBy: [
    {
      className: 'java.sql.SQLException',
      message: 'timeout',
      frames: [
        frame({
          className: 'com.zaxxer.hikari.pool.HikariPool',
          method: 'getConnection',
          line: 181,
        }),
      ],
    },
  ],
};

const SOURCES: SourceFileDto[] = [
  source('file-owner', OWNER_SOURCE),
  source('file-controller', OWNER_SOURCE, CONTROLLER_PATH),
  source('file-servlet', OWNER_SOURCE, SPRING_PATH),
];

/** A log with an exception (`log-0001` in the list); `log-0002` has none and no trace id. */
const ERROR_LOG = logDetail({
  logId: 'log-0001',
  level: 'ERROR',
  message: 'Request processing failed',
  exception: EXCEPTION,
});
const PLAIN_LOG = logDetail({ logId: 'log-0002', traceId: null, spanId: null });

const around = (id: number, count: number): NeighborsResponse => ({
  before: Array.from({ length: Math.min(count, id - 1) }, (_, i) =>
    logSummary(id - Math.min(count, id - 1) + i),
  ),
  current: logSummary(id),
  after: Array.from({ length: count }, (_, i) => logSummary(id + 1 + i)),
});

const TRACE: TraceResponse = {
  reason: null,
  items: [
    logSummary(1, {
      traceId: 't-1',
      timestamp: '2026-09-23T20:05:44.700Z',
      service: 'customers-service',
    }),
    logSummary(7, {
      traceId: 't-1',
      timestamp: '2026-09-23T20:05:44.712Z',
      service: 'customers-service',
      level: 'ERROR',
      hasException: true,
    }),
    logSummary(9, {
      traceId: 't-1',
      timestamp: '2026-09-23T20:05:46.100Z',
      service: 'visits-service',
    }),
    logSummary(12, {
      traceId: 't-1',
      timestamp: '2026-09-23T20:05:46.200Z',
      service: 'api-gateway',
    }),
  ],
};

function show(path: string, overrides: { neighbors?: () => Response } = {}) {
  const list = Array.from({ length: 30 }, (_, i) => logSummary(i + 1));
  const detail = (id: string): LogDetail =>
    id === 'log-0001'
      ? ERROR_LOG
      : id === 'log-0002'
        ? PLAIN_LOG
        : logDetail({ logId: id, match: null });
  const fetchSpy = mockFetch(logsRoute(list), (url) => {
    const p = url.pathname;
    const neighbors = p.match(/^\/api\/logs\/log-(\d+)\/neighbors$/);
    if (neighbors) {
      if (overrides.neighbors) return overrides.neighbors();
      return json(around(Number(neighbors[1]), Number(url.searchParams.get('before'))));
    }
    const trace = p.match(/^\/api\/logs\/([^/]+)\/trace$/);
    if (trace) {
      return json(trace[1] === 'log-0002' ? { items: [], reason: 'no-trace-id' } : TRACE);
    }
    if (p.endsWith('/candidates')) return json([]);
    const log = p.match(/^\/api\/logs\/([^/]+)$/);
    if (log) return json(detail(log[1]));
    if (p === '/api/catalog/stmt-1') return json(entry());
    const file = SOURCES.find((s) => p === `/api/sources/${s.fileId}`);
    if (file) return json(file);
    if (p.startsWith('/api/methods/')) return problem(404, 'method not found');
    if (p.startsWith('/api/meta')) return json([]);
    return undefined;
  });
  const router = renderApp(path);
  return { router, fetchSpy };
}

const context = () => within(zone('Context'));
const viewer = () => within(zone('Code')).findByTestId('code-viewer');
const requests = (spy: ReturnType<typeof mockFetch>, suffix: string) =>
  spy.mock.calls
    .map(([input]) => new URL(String(input), 'http://localhost'))
    .filter((url) => url.pathname.endsWith(suffix));

beforeEach(() => {
  window.localStorage.clear();
});

describe('stack trace tab', () => {
  it('exists only for a log with an exception; a remembered choice falls back to the first tab', async () => {
    window.localStorage.setItem(CONTEXT_TAB_KEY, 'stack');
    const { router } = show('/logs/log-0002?datasetId=smoke-01');
    // While the log loads the remembered tab is kept; once it has no exception, the first tab shows.
    await waitFor(() => expect(context().queryByRole('tab', { name: 'Stack trace' })).toBeNull());
    expect(context().getByRole('tab', { name: 'Conditions and flow' })).toHaveAttribute(
      'aria-selected',
      'true',
    );

    await act(() => router.navigate('/logs/log-0001?datasetId=smoke-01'));
    expect(await context().findByRole('tab', { name: 'Stack trace' })).toHaveAttribute(
      'aria-selected',
      'true',
    );
    expect(window.localStorage.getItem(CONTEXT_TAB_KEY)).toBe('stack');
  });

  it('shows the exception and its "Caused by" chain; library runs are folded by default', async () => {
    window.localStorage.setItem(CONTEXT_TAB_KEY, 'stack');
    show('/logs/log-0001?datasetId=smoke-01');
    const tab = context();
    expect(
      await tab.findByText('java.lang.IllegalStateException', { exact: false }),
    ).toHaveTextContent('java.lang.IllegalStateException: Owner lookup failed');
    expect(tab.getByText(/7 frames · 1 in the project/)).toBeInTheDocument();
    expect(tab.getByText('java.sql.SQLException', { selector: '.mono' })).toBeInTheDocument();
    const cause = tab.getByRole('region', { name: 'Caused by: java.sql.SQLException' });
    expect(within(cause).getByText(/: timeout/)).toBeInTheDocument();

    const toggle = tab.getByRole('button', { name: 'Hide library frames' });
    expect(toggle).toHaveAttribute('aria-pressed', 'true');
    const main = tab.getByRole('region', { name: 'java.lang.IllegalStateException' });
    // 2 library frames, the project frame, then 3 library frames.
    expect(within(main).getByRole('button', { name: '2 library frames' })).toBeInTheDocument();
    expect(within(main).getByRole('button', { name: '3 library frames' })).toBeInTheDocument();
    expect(within(main).getByText('project')).toBeInTheDocument();
    // A single library frame (the cause) stays as it is.
    expect(within(cause).getByText(/HikariPool\.getConnection/)).toBeInTheDocument();

    await userEvent.click(within(main).getByRole('button', { name: '2 library frames' }));
    expect(
      within(main).getByText(/Constructor\.newInstance\(Constructor\.java:481\)/),
    ).toBeInTheDocument();
    expect(
      within(main).getByText(/NativeConstructorAccessorImpl\.newInstance0\(Unknown Source\)/),
    ).toBeInTheDocument();
    expect(within(main).getByRole('button', { name: '3 library frames' })).toBeInTheDocument();

    await userEvent.click(toggle);
    expect(toggle).toHaveAttribute('aria-pressed', 'false');
    expect(within(main).queryByRole('button', { name: /library frames/ })).toBeNull();
    expect(within(main).getAllByRole('listitem')).toHaveLength(6);
    expect(window.localStorage.getItem(HIDE_LIBRARY_KEY)).toBe('false');
  });

  it('a project frame opens its file at its line (AC2); "Back to log" returns to the statement', async () => {
    window.localStorage.setItem(CONTEXT_TAB_KEY, 'stack');
    const { router } = show('/logs/log-0001?datasetId=smoke-01');
    expect(await viewer()).toHaveAttribute('data-statement', '89-89');

    const main = within(
      await context().findByRole('region', { name: 'java.lang.IllegalStateException' }),
    );
    await userEvent.click(
      main.getByRole('button', { name: /OwnerController\.show\(OwnerController\.java:40\)/ }),
    );
    expect(searchOf(router).get('at')).toBe('file-controller:40');
    await waitFor(async () =>
      expect(await viewer()).toHaveAttribute('data-path', `file-controller/${CONTROLLER_PATH}`),
    );
    expect(await viewer()).toHaveAttribute('data-statement', '40-40');
    expect(within(zone('Code')).getByText('Opened from context')).toBeInTheDocument();
    expect(main.getByRole('button', { name: /OwnerController\.show/ })).toHaveAttribute(
      'aria-current',
      'location',
    );

    await userEvent.click(within(zone('Code')).getByRole('button', { name: 'Back to log' }));
    expect(searchOf(router).get('at')).toBeNull();
    await waitFor(async () => expect(await viewer()).toHaveAttribute('data-statement', '89-89'));
  });

  it('a library frame opens its sources when stored, else links to GitHub, else is plain text', async () => {
    window.localStorage.setItem(CONTEXT_TAB_KEY, 'stack');
    window.localStorage.setItem(HIDE_LIBRARY_KEY, 'false');
    const { router } = show('/logs/log-0001?datasetId=smoke-01');
    const main = within(
      await context().findByRole('region', { name: 'java.lang.IllegalStateException' }),
    );

    const github = main.getByRole('link', {
      name: /HttpServlet\.service\(HttpServlet\.java:622\)/,
    });
    expect(github).toHaveAttribute('href', FRAMES[4].githubUrl);
    expect(github).toHaveAttribute('target', '_blank');
    expect(github).toHaveAttribute('rel', 'noopener noreferrer');
    expect(main.getByText('tomcat-embed-core')).toBeInTheDocument();

    const plain = main.getByText('java.lang.Thread.run(Thread.java:840)');
    expect(plain.tagName).toBe('SPAN');

    await userEvent.click(main.getByRole('button', { name: /FrameworkServlet\.doGet/ }));
    expect(searchOf(router).get('at')).toBe('file-servlet:892');
    await waitFor(async () =>
      expect(await viewer()).toHaveAttribute('data-path', `file-servlet/${SPRING_PATH}`),
    );
  });
});

describe('neighbors tab', () => {
  it('shows 10 logs on each side in the service scope by default, the current one marked', async () => {
    window.localStorage.setItem(CONTEXT_TAB_KEY, 'neighbors');
    const { fetchSpy } = show('/logs/log-0015?datasetId=smoke-01');
    const list = within(await context().findByRole('list', { name: 'Neighbor logs' }));
    expect(list.getAllByRole('button')).toHaveLength(21);
    const current = list.getByRole('button', { current: true });
    expect(current).toHaveTextContent('message 15');
    const [request] = requests(fetchSpy, '/neighbors');
    expect(request.pathname).toBe('/api/logs/log-0015/neighbors');
    expect(Object.fromEntries(request.searchParams)).toEqual({
      before: '10',
      after: '10',
      scope: 'service',
    });
  });

  it('scope and count change the request and are remembered', async () => {
    window.localStorage.setItem(CONTEXT_TAB_KEY, 'neighbors');
    const { fetchSpy } = show('/logs/log-0003?datasetId=smoke-01');
    const tab = context();
    await tab.findByRole('list', { name: 'Neighbor logs' });
    // Log 3 has only two logs before it.
    expect(tab.queryByText('Start of the log file.')).toBeNull();

    await userEvent.click(tab.getByRole('radio', { name: 'Thread' }));
    await userEvent.click(tab.getByRole('radio', { name: '20' }));
    await waitFor(() => {
      const last = requests(fetchSpy, '/neighbors').at(-1) as URL;
      expect(last.searchParams.get('scope')).toBe('thread');
      expect(last.searchParams.get('before')).toBe('20');
    });
    expect(tab.getByRole('radio', { name: 'Thread' })).toHaveAttribute('aria-checked', 'true');
    expect(window.localStorage.getItem(NEIGHBOR_SCOPE_KEY)).toBe('thread');
    expect(window.localStorage.getItem(NEIGHBOR_COUNT_KEY)).toBe('20');
  });

  it('marks the edge of the file when there is nothing before', async () => {
    window.localStorage.setItem(CONTEXT_TAB_KEY, 'neighbors');
    show('/logs/log-0001?datasetId=smoke-01');
    expect(await context().findByText('Start of the log file.')).toBeInTheDocument();
  });

  it('clicking a neighbor selects it in the whole app, keeping the filters (AC3)', async () => {
    window.localStorage.setItem(CONTEXT_TAB_KEY, 'neighbors');
    const { router } = show(
      '/logs/log-0015?datasetId=smoke-01&level=INFO&alt=stmt-9&at=file-owner:10',
    );
    const list = within(await context().findByRole('list', { name: 'Neighbor logs' }));
    await userEvent.click(list.getByRole('button', { name: /message 17/ }));

    expect(router.state.location.pathname).toBe('/logs/log-0017');
    const search = searchOf(router);
    expect(search.get('datasetId')).toBe('smoke-01');
    expect(search.get('level')).toBe('INFO');
    expect(search.get('alt')).toBeNull();
    expect(search.get('at')).toBeNull();
    // The log list, the detail and the neighbors follow the new selection.
    await waitFor(() =>
      expect(within(zone('Logs')).getByRole('option', { selected: true })).toHaveTextContent(
        'message 17',
      ),
    );
    await waitFor(() =>
      expect(
        within(context().getByRole('list', { name: 'Neighbor logs' })).getByRole('button', {
          current: true,
        }),
      ).toHaveTextContent('message 17'),
    );
    // A click adds a history entry: Back returns to the previous log.
    await act(() => router.navigate(-1));
    expect(router.state.location.pathname).toBe('/logs/log-0015');
  });

  it('reports an error with Retry', async () => {
    window.localStorage.setItem(CONTEXT_TAB_KEY, 'neighbors');
    show('/logs/log-0015?datasetId=smoke-01', {
      neighbors: () => problem(502, 'OpenSearch is not reachable'),
    });
    expect(
      await context().findByText(/Could not load the neighbors: OpenSearch is not reachable/),
    ).toBeInTheDocument();
    expect(context().getByRole('button', { name: 'Retry' })).toBeInTheDocument();
  });
});

describe('same request tab', () => {
  it('lists the logs of the trace by time, with the offset and a color per service', async () => {
    window.localStorage.setItem(CONTEXT_TAB_KEY, 'request');
    const { router } = show('/logs/log-0001?datasetId=smoke-01');
    const tab = context();
    const list = within(await tab.findByRole('list', { name: 'Logs of the same request' }));
    expect(tab.getByText(/4 logs · 3 services/)).toBeInTheDocument();

    const rows = list.getAllByRole('button');
    expect(rows.map((row) => row.querySelector('.context-log__offset')?.textContent)).toEqual([
      '+0 ms',
      '+12 ms',
      '+1.400 s',
      '+1.500 s',
    ]);
    expect(rows[0]).toHaveAttribute('aria-current', 'true');
    const color = (row: HTMLElement) =>
      (row.querySelector('.service-dot') as HTMLElement).style.getPropertyValue('--service-color');
    expect(color(rows[0])).toBe(color(rows[1]));
    expect(new Set(rows.map(color)).size).toBe(3);
    expect(color(rows[0])).toMatch(/^var\(--service-[1-8]\)$/);

    await userEvent.click(rows[2]);
    expect(router.state.location.pathname).toBe('/logs/log-0009');
  });

  it('"Show in log list" filters the list by the trace id', async () => {
    window.localStorage.setItem(CONTEXT_TAB_KEY, 'request');
    const { router } = show('/logs/log-0001?datasetId=smoke-01');
    await userEvent.click(await context().findByRole('button', { name: 'Show in log list' }));
    expect(searchOf(router).get('traceId')).toBe('t-1');
    expect(router.state.location.pathname).toBe('/logs/log-0001');
  });

  it('without a trace id it explains why and opens the thread neighbors', async () => {
    window.localStorage.setItem(CONTEXT_TAB_KEY, 'request');
    const { fetchSpy } = show('/logs/log-0002?datasetId=smoke-01');
    expect(await context().findByText('No trace id on this log')).toBeInTheDocument();
    expect(requests(fetchSpy, '/neighbors')).toHaveLength(0);

    await userEvent.click(context().getByRole('button', { name: 'Show neighbors in this thread' }));
    expect(context().getByRole('tab', { name: 'Neighbors' })).toHaveAttribute(
      'aria-selected',
      'true',
    );
    expect(await context().findByRole('radio', { name: 'Thread' })).toHaveAttribute(
      'aria-checked',
      'true',
    );
    await waitFor(() =>
      expect(requests(fetchSpy, '/neighbors').at(-1)?.searchParams.get('scope')).toBe('thread'),
    );
  });
});

describe('log tabs without a log', () => {
  it('ask to pick a log, and work for an unmatched log', async () => {
    window.localStorage.setItem(CONTEXT_TAB_KEY, 'neighbors');
    show('/?datasetId=smoke-01');
    expect(await context().findByText('No log selected')).toBeInTheDocument();
    expect(screen.queryByRole('list', { name: 'Neighbor logs' })).toBeNull();
  });

  it('neighbors work for an unmatched log (no statement needed)', async () => {
    window.localStorage.setItem(CONTEXT_TAB_KEY, 'neighbors');
    show('/logs/log-0020?datasetId=smoke-01');
    const list = within(await context().findByRole('list', { name: 'Neighbor logs' }));
    expect(list.getByRole('button', { current: true })).toHaveTextContent('message 20');
  });
});
