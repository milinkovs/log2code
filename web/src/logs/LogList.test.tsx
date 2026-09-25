import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { LOG_PAGE_SIZE } from '../api/queries';
import type { LogSummary } from '../api/types';
import { json, mockFetch, problem } from '../test/fetchMock';
import {
  logDetail,
  logRequests,
  logSummary,
  logsRoute,
  renderApp,
  searchOf,
  zone,
} from '../test/renderApp';
import { LIST_PADDING, ROW_HEIGHT } from './LogList';

const metaRoutes = (url: URL) => {
  if (url.pathname === '/api/meta/datasets') return json([{ datasetId: 'smoke-01', count: 250 }]);
  if (url.pathname === '/api/meta/services') return json(['customers-service']);
  return undefined;
};

const detailRoute = (logs: LogSummary[]) => (url: URL) => {
  const match = /^\/api\/logs\/([^/]+)$/.exec(url.pathname);
  const summary = match && logs.find((l) => l.logId === decodeURIComponent(match[1]));
  return summary ? json(logDetail({ logId: summary.logId, message: summary.message })) : undefined;
};

const range = (n: number, map?: (i: number) => Partial<LogSummary>) =>
  Array.from({ length: n }, (_, i) => logSummary(i, map?.(i)));

const listbox = () => within(zone('Logs')).getByRole('listbox', { name: 'Logs' });
const options = () => within(listbox()).queryAllByRole('option');

/** Expected height of the virtual canvas for `rows` rows. */
const canvasHeight = (rows: number) => `${rows * ROW_HEIGHT + 2 * LIST_PADDING}px`;

/** Scrolls the virtual list to `top` px (jsdom does not scroll by itself). */
function scrollListTo(top: number) {
  const list = listbox();
  Object.defineProperty(list, 'scrollTop', { configurable: true, value: top, writable: true });
  fireEvent.scroll(list);
}

describe('log list', () => {
  it('shows one row per log: time (UTC), level, service, message, confidence, exception', async () => {
    const logs = [
      logSummary(1, {
        timestamp: '2026-09-23T20:05:44.739Z',
        level: 'ERROR',
        service: 'vets-service',
        message: 'Failed to process request',
        status: 'ambiguous',
        confidenceLevel: 'medium',
        hasException: true,
      }),
      logSummary(2, { status: 'unmatched', confidenceLevel: null, confidence: null }),
    ];
    mockFetch(logsRoute(logs), metaRoutes);
    renderApp('/');

    const [first, second] = await within(zone('Logs')).findAllByRole('option');
    expect(first).toHaveTextContent('20:05:44.739');
    expect(within(first).getByText('ERROR')).toHaveClass('level--error');
    expect(first).toHaveTextContent('vets-service');
    expect(first).toHaveTextContent('Failed to process request');
    expect(first).toHaveTextContent('ambiguous');
    expect(within(first).getByRole('img', { name: 'Has exception' })).toBeInTheDocument();
    expect(second).toHaveTextContent('unmatched');
    expect(within(second).queryByRole('img', { name: 'Has exception' })).toBeNull();
    expect(within(zone('Logs')).getByText('2 logs · UTC')).toBeInTheDocument();
    // The row shows only the time; the full UTC date and time is in the tooltip.
    expect(within(first).getByText('20:05:44.739')).toHaveAttribute(
      'title',
      '2026-09-23 20:05:44.739 UTC',
    );
  });

  it('counts a single result as "1 log" and labels the times as UTC', async () => {
    mockFetch(logsRoute(range(1)), metaRoutes);
    renderApp('/');
    expect(await within(zone('Logs')).findByText('1 log · UTC')).toBeInTheDocument();
  });

  it('only renders the visible rows (virtualized)', async () => {
    mockFetch(logsRoute(range(100)), metaRoutes);
    renderApp('/');
    await within(zone('Logs')).findAllByRole('option');
    expect(options().length).toBeLessThan(60);
    expect(options().length).toBeGreaterThan(15);
  });

  it('scrolling loads the next pages through searchAfter, without duplicates', async () => {
    const logs = range(250);
    const spy = mockFetch(logsRoute(logs), metaRoutes);
    renderApp('/?datasetId=smoke-01&level=INFO');
    await within(zone('Logs')).findAllByRole('option');
    expect(logRequests(spy)).toHaveLength(1);

    scrollListTo(90 * ROW_HEIGHT);
    await waitFor(() => expect(logRequests(spy)).toHaveLength(2));
    scrollListTo(190 * ROW_HEIGHT);
    await waitFor(() => expect(logRequests(spy)).toHaveLength(3));

    const requests = logRequests(spy);
    expect(requests.map((r) => r.searchParams.get('searchAfter'))).toEqual([
      null,
      btoa(String(LOG_PAGE_SIZE)),
      btoa(String(2 * LOG_PAGE_SIZE)),
    ]);
    // Every page keeps the same filters and page size.
    for (const r of requests) {
      expect(r.searchParams.get('datasetId')).toBe('smoke-01');
      expect(r.searchParams.getAll('level')).toEqual(['INFO']);
      expect(r.searchParams.get('size')).toBe(String(LOG_PAGE_SIZE));
    }

    // 250 unique rows in total (no loader row once the last, short page arrived)…
    const canvas = listbox().firstElementChild as HTMLElement;
    await waitFor(() => expect(canvas.style.height).toBe(canvasHeight(250)));
    // …and the rendered window has no repeated log.
    scrollListTo(240 * ROW_HEIGHT);
    await waitFor(() => expect(options().at(-1)).toHaveTextContent('message 249'));
    const ids = options().map((o) => o.id);
    expect(new Set(ids).size).toBe(ids.length);
    // A fourth request is never made: the last page was short.
    expect(logRequests(spy)).toHaveLength(3);
  });

  it('drops a log that a later page repeats', async () => {
    const page1 = range(LOG_PAGE_SIZE);
    // The mock "API" repeats the last log of page 1 at the start of page 2.
    const all = [...page1, page1[LOG_PAGE_SIZE - 1], ...range(20, (i) => ({ logId: `x-${i}` }))];
    mockFetch(logsRoute(all), metaRoutes);
    renderApp('/');
    await within(zone('Logs')).findAllByRole('option');

    scrollListTo(90 * ROW_HEIGHT);
    const canvas = listbox().firstElementChild as HTMLElement;
    await waitFor(() => expect(canvas.style.height).toBe(canvasHeight(LOG_PAGE_SIZE + 20)));
  });

  it('clicking a row selects it: /logs/:id keeps the filters and pushes a history entry', async () => {
    const logs = range(5);
    mockFetch(logsRoute(logs), detailRoute(logs), metaRoutes);
    const router = renderApp('/?datasetId=smoke-01&level=INFO');

    await userEvent.click(await within(zone('Logs')).findByText('message 2'));
    expect(router.state.location.pathname).toBe('/logs/log-0002');
    expect(router.state.location.search).toBe('?datasetId=smoke-01&level=INFO');
    expect(router.state.historyAction).toBe('PUSH');

    const selected = within(listbox()).getByRole('option', { selected: true });
    expect(selected).toHaveTextContent('message 2');
    expect(listbox()).toHaveAttribute('aria-activedescendant', selected.id);
    expect(await within(zone('Log detail')).findByTestId('log-message')).toHaveTextContent(
      'message 2',
    );
  });

  it('↑ and ↓ move the selection and replace the history entry', async () => {
    const logs = range(5);
    mockFetch(logsRoute(logs), detailRoute(logs), metaRoutes);
    const router = renderApp('/?level=INFO');
    await within(zone('Logs')).findAllByRole('option');

    listbox().focus();
    await userEvent.keyboard('{ArrowDown}');
    expect(router.state.location.pathname).toBe('/logs/log-0000');
    await userEvent.keyboard('{ArrowDown}{ArrowDown}');
    expect(router.state.location.pathname).toBe('/logs/log-0002');
    await userEvent.keyboard('{ArrowUp}');
    expect(router.state.location.pathname).toBe('/logs/log-0001');
    expect(router.state.historyAction).toBe('REPLACE');
    expect(searchOf(router).getAll('level')).toEqual(['INFO']);
    expect(within(listbox()).getByRole('option', { selected: true })).toHaveTextContent(
      'message 1',
    );

    // The ends of the list stop the selection.
    await userEvent.keyboard('{ArrowUp}{ArrowUp}{ArrowUp}');
    expect(router.state.location.pathname).toBe('/logs/log-0000');
  });

  it('a deep link with filters and a selected log restores all of it', async () => {
    const logs = range(60, (i) => ({
      level: 'ERROR',
      service: 'customers-service',
      logId: `e-${i}`,
    }));
    const spy = mockFetch(logsRoute(logs), detailRoute(logs), metaRoutes);
    renderApp('/logs/e-45?datasetId=smoke-01&service=customers-service&level=ERROR&order=asc');

    // Filter bar and chips reflect the URL…
    const filterBar = screen.getByRole('search', { name: 'Filters' });
    expect(
      within(filterBar).getByRole('button', { name: 'Level, 1 selected' }),
    ).toBeInTheDocument();
    expect(
      within(filterBar).getByRole('button', { name: 'Service, 1 selected' }),
    ).toBeInTheDocument();
    expect(screen.getByRole('group', { name: 'Active filters' })).toHaveTextContent('ERROR');
    expect(screen.getByRole('combobox', { name: 'Dataset' })).toHaveValue('smoke-01');
    expect(within(zone('Logs')).getByRole('button', { name: /Oldest first/ })).toBeInTheDocument();

    // …the list is asked with the same filters and scrolls to the selected row…
    await waitFor(() => {
      const request = logRequests(spy)[0];
      expect(request.searchParams.getAll('service')).toEqual(['customers-service']);
      expect(request.searchParams.getAll('level')).toEqual(['ERROR']);
      expect(request.searchParams.get('order')).toBe('asc');
    });
    const selected = await within(zone('Logs')).findByRole('option', { selected: true });
    expect(selected.id).toBe('log-row-e-45');

    // …and the detail shows the log.
    expect(await within(zone('Log detail')).findByTestId('log-message')).toHaveTextContent(
      'message 45',
    );
  });

  it('the sort switch toggles order=asc in the URL', async () => {
    const spy = mockFetch(logsRoute(range(3)), metaRoutes);
    const router = renderApp('/');
    await within(zone('Logs')).findAllByRole('option');

    await userEvent.click(within(zone('Logs')).getByRole('button', { name: /Newest first/ }));
    expect(searchOf(router).get('order')).toBe('asc');
    await waitFor(() => expect(logRequests(spy).at(-1)?.searchParams.get('order')).toBe('asc'));

    await userEvent.click(within(zone('Logs')).getByRole('button', { name: /Oldest first/ }));
    expect(searchOf(router).get('order')).toBeNull();
  });

  it('shows skeleton rows while loading', async () => {
    mockFetch(metaRoutes, (url) =>
      url.pathname === '/api/logs' ? (new Promise(() => {}) as never) : undefined,
    );
    renderApp('/');
    expect(within(zone('Logs')).getByRole('status')).toHaveTextContent('Loading logs…');
  });

  it('an empty filtered result offers to clear the filters', async () => {
    mockFetch(logsRoute([]), metaRoutes);
    const router = renderApp('/?datasetId=smoke-01&level=FATAL');

    expect(
      await within(zone('Logs')).findByText('No logs match these filters'),
    ).toBeInTheDocument();
    await userEvent.click(within(zone('Logs')).getByRole('button', { name: 'Clear filters' }));
    expect(router.state.location.search).toBe('?datasetId=smoke-01');
  });

  it('an empty dataset without filters says nothing is ingested', async () => {
    mockFetch(logsRoute([]), metaRoutes);
    renderApp('/');
    expect(await within(zone('Logs')).findByText('No logs')).toBeInTheDocument();
  });

  it('reports an API error with a retry', async () => {
    let fail = true;
    mockFetch(metaRoutes, (url) => {
      if (url.pathname !== '/api/logs') return undefined;
      return fail ? problem(502, 'OpenSearch is not reachable') : logsRoute(range(2))(url);
    });
    renderApp('/');

    const alert = await within(zone('Logs')).findByRole('alert');
    expect(alert).toHaveTextContent('Could not load logs: OpenSearch is not reachable');
    fail = false;
    await userEvent.click(within(alert).getByRole('button', { name: 'Retry' }));
    expect(await within(zone('Logs')).findAllByRole('option')).toHaveLength(2);
  });
});
