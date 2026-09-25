import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { createMemoryRouter, RouterProvider } from 'react-router';
import { describe, expect, it } from 'vitest';
import type { LogDetail } from './api/types';
import { layoutKey } from './layout/layoutStorage';
import { WORKSPACE_GROUP } from './layout/panelIds';
import { routes } from './routes';
import { json, mockFetch, problem } from './test/fetchMock';

const DATASETS = [
  { datasetId: 'demo-01', count: 877 },
  { datasetId: 'smoke-01', count: 1045 },
];

const LOG = {
  logId: 'dcf3be3719357b10ac07fe9b2aed1bd4',
  datasetId: 'smoke-01',
  service: 'customers-service',
  level: 'INFO',
  message: 'Saving owner Owner[id=11]',
} as Partial<LogDetail>;

function apiMock() {
  return mockFetch((url) => {
    if (url.pathname === '/api/meta/datasets') return json(DATASETS);
    if (url.pathname === `/api/logs/${LOG.logId}`) return json(LOG);
    if (url.pathname.startsWith('/api/logs/')) return problem(404, 'log not found');
    return undefined;
  });
}

function renderApp(path: string) {
  const router = createMemoryRouter(routes, { initialEntries: [path] });
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <QueryClientProvider client={queryClient}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  );
  return router;
}

const zone = (name: string) => screen.getByRole('region', { name });

describe('layout', () => {
  it('shows the top bar and the four zones: code and context left, logs and detail right', async () => {
    apiMock();
    renderApp('/');

    expect(screen.getByRole('link', { name: 'log2code' })).toBeInTheDocument();
    expect(screen.getByRole('search', { name: 'Filters' })).toBeInTheDocument();
    for (const name of ['Code', 'Context', 'Logs', 'Log detail']) {
      expect(zone(name)).toBeInTheDocument();
    }
    expect(within(zone('Log detail')).getByText('No log selected')).toBeInTheDocument();

    // Left panel holds code + context, right panel holds logs + detail.
    const left = screen.getByTestId('code');
    const right = screen.getByTestId('logs');
    expect(left).toContainElement(zone('Code'));
    expect(left).toContainElement(zone('Context'));
    expect(right).toContainElement(zone('Logs'));
    expect(right).toContainElement(zone('Log detail'));

    expect(await screen.findByRole('option', { name: 'smoke-01 (1,045)' })).toBeInTheDocument();
  });

  it('restores panel sizes saved in localStorage', () => {
    apiMock();
    window.localStorage.setItem(layoutKey(WORKSPACE_GROUP), JSON.stringify({ code: 30, logs: 70 }));
    renderApp('/');

    const code = screen.getByTestId('code');
    const logs = screen.getByTestId('logs');
    expect(Number(code.style.flexGrow)).toBe(30);
    expect(Number(logs.style.flexGrow)).toBe(70);
  });

  it('falls back to default sizes when the saved layout is unusable', () => {
    // jsdom cannot measure, so the defaults themselves are checked in a real browser; here the
    // corrupt value must simply behave exactly like "nothing saved".
    apiMock();
    renderApp('/');
    const defaultGrow = screen.getByTestId('code').style.flexGrow;
    cleanup();

    window.localStorage.setItem(layoutKey(WORKSPACE_GROUP), 'not json');
    renderApp('/');
    expect(screen.getByTestId('code').style.flexGrow).toBe(defaultGrow);
    expect(screen.getByRole('region', { name: 'Code' })).toBeInTheDocument();
  });
});

describe('routes', () => {
  it('/logs/:logId loads the log and shows its JSON in the detail zone', async () => {
    const fetchSpy = apiMock();
    renderApp(`/logs/${LOG.logId}`);

    const detail = await within(zone('Log detail')).findByTestId('log-detail-json');
    expect(JSON.parse(detail.textContent ?? '')).toEqual(LOG);
    expect(fetchSpy.mock.calls.map(([input]) => String(input))).toContain(`/api/logs/${LOG.logId}`);
  });

  it('reports an unknown log id', async () => {
    apiMock();
    renderApp('/logs/does-not-exist');

    expect(await within(zone('Log detail')).findByRole('alert')).toHaveTextContent(
      'Log not found: does-not-exist',
    );
  });

  it('shows an error page for unknown paths', () => {
    apiMock();
    renderApp('/nowhere');

    expect(screen.getByRole('alert')).toHaveTextContent('404');
    expect(screen.getByRole('link', { name: 'Back to logs' })).toHaveAttribute('href', '/');
  });
});

describe('dataset selection', () => {
  it('lives in the query string and keeps the selected log', async () => {
    apiMock();
    const router = renderApp(`/logs/${LOG.logId}`);
    const select = screen.getByRole('combobox', { name: 'Dataset' });
    await screen.findByRole('option', { name: 'demo-01 (877)' });

    await userEvent.selectOptions(select, 'demo-01');
    expect(router.state.location.pathname).toBe(`/logs/${LOG.logId}`);
    expect(new URLSearchParams(router.state.location.search).get('datasetId')).toBe('demo-01');
    expect(select).toHaveValue('demo-01');

    await userEvent.selectOptions(select, 'All datasets');
    expect(router.state.location.search).toBe('');
  });

  it('is read from the URL, even for a dataset the API does not list', async () => {
    apiMock();
    renderApp('/?datasetId=old-01');

    const select = screen.getByRole('combobox', { name: 'Dataset' });
    expect(select).toHaveValue('old-01');
    await screen.findByRole('option', { name: 'smoke-01 (1,045)' });
    expect(select).toHaveValue('old-01');
  });

  it('keeps the query string when going home via the title', async () => {
    apiMock();
    const router = renderApp(`/logs/${LOG.logId}?datasetId=smoke-01`);

    await userEvent.click(screen.getByRole('link', { name: 'log2code' }));
    expect(router.state.location.pathname).toBe('/');
    expect(router.state.location.search).toBe('?datasetId=smoke-01');
    expect(within(zone('Log detail')).getByText('No log selected')).toBeInTheDocument();
  });
});

describe('theme toggle', () => {
  it('starts dark, switches the theme and remembers the choice', async () => {
    apiMock();
    renderApp('/');
    const toggle = screen.getByRole('radiogroup', { name: 'Theme' });
    const dark = within(toggle).getByRole('radio', { name: 'Dark theme' });
    const light = within(toggle).getByRole('radio', { name: 'Light theme' });
    expect(dark).toBeChecked();

    await userEvent.click(light);
    expect(light).toBeChecked();
    expect(document.documentElement.dataset.theme).toBe('light');
    expect(window.localStorage.getItem('log2code:theme')).toBe('light');

    // Clicking the active option keeps it (a theme is always selected).
    await userEvent.click(light);
    expect(light).toBeChecked();
  });
});
