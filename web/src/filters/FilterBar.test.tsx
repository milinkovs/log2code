import { act, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { json, mockFetch } from '../test/fetchMock';
import { logRequests, logSummary, logsRoute, renderApp, searchOf } from '../test/renderApp';
import { SEARCH_DEBOUNCE_MS } from './FilterBar';

const SERVICES = ['api-gateway', 'customers-service', 'vets-service'];

function apiMock() {
  return mockFetch(logsRoute([logSummary(1), logSummary(2)]), (url) => {
    if (url.pathname === '/api/meta/datasets') return json([{ datasetId: 'smoke-01', count: 2 }]);
    if (url.pathname === '/api/meta/services') return json(SERVICES);
    return undefined;
  });
}

const filters = () => screen.getByRole('search', { name: 'Filters' });
const chips = () => screen.queryByRole('group', { name: 'Active filters' });
const chipRow = () => screen.getByRole('group', { name: 'Active filters' });

async function pick(menu: string, ...options: string[]) {
  await userEvent.click(within(filters()).getByRole('button', { name: new RegExp(`^${menu}`) }));
  const content = await screen.findByRole('menu', { name: menu });
  for (const option of options) {
    await userEvent.click(within(content).getByRole('menuitemcheckbox', { name: option }));
  }
  await userEvent.keyboard('{Escape}');
}

describe('filter bar', () => {
  it('service and level (multi-select) go to the URL and to the API as repeated parameters', async () => {
    const spy = apiMock();
    const router = renderApp('/?datasetId=smoke-01');

    await pick('Service', 'customers-service', 'vets-service');
    await pick('Level', 'WARN', 'ERROR');

    const params = searchOf(router);
    expect(params.getAll('service')).toEqual(['customers-service', 'vets-service']);
    // Option order, not click order.
    expect(params.getAll('level')).toEqual(['WARN', 'ERROR']);
    expect(params.get('datasetId')).toBe('smoke-01');

    await waitFor(() => {
      const last = logRequests(spy).at(-1);
      expect(last?.searchParams.getAll('service')).toEqual(['customers-service', 'vets-service']);
      expect(last?.searchParams.getAll('level')).toEqual(['WARN', 'ERROR']);
      expect(last?.searchParams.get('datasetId')).toBe('smoke-01');
    });
    expect(
      within(filters()).getByRole('button', { name: 'Level, 2 selected' }),
    ).toBeInTheDocument();
  });

  it('asks for the services of the selected dataset', async () => {
    const spy = apiMock();
    renderApp('/?datasetId=smoke-01');
    await pick('Service');
    const calls = spy.mock.calls.map(([input]) => String(input));
    expect(calls).toContain('/api/meta/services?datasetId=smoke-01');
  });

  it('status, confidence, exceptions only and trace id update the URL', async () => {
    apiMock();
    const router = renderApp('/');

    await pick('Status', 'ambiguous', 'unmatched');
    await pick('Confidence', 'low');
    await userEvent.click(within(filters()).getByRole('button', { name: 'Exceptions only' }));
    await userEvent.click(within(filters()).getByRole('button', { name: 'Trace id' }));
    const traceInput = within(await screen.findByRole('dialog', { name: 'Trace id' })).getByRole(
      'textbox',
    );
    await userEvent.type(traceInput, ' 6a3f0c9e {Enter}');

    const params = searchOf(router);
    expect(params.getAll('status')).toEqual(['ambiguous', 'unmatched']);
    expect(params.getAll('confidence')).toEqual(['low']);
    expect(params.get('hasException')).toBe('true');
    expect(params.get('traceId')).toBe('6a3f0c9e');
    expect(within(filters()).getByRole('button', { name: 'Exceptions only' })).toHaveAttribute(
      'aria-pressed',
      'true',
    );
  });

  it('the time range is entered and sent as UTC', async () => {
    apiMock();
    const router = renderApp('/');

    await userEvent.click(within(filters()).getByRole('button', { name: 'Time' }));
    const dialog = await screen.findByRole('dialog', { name: 'Time range' });
    const [from, to] = [
      within(dialog).getByLabelText('From (UTC)'),
      within(dialog).getByLabelText('To (UTC)'),
    ];
    await userEvent.clear(from);
    await userEvent.type(from, '2026-09-23T20:05:00');
    await userEvent.clear(to);
    await userEvent.type(to, '2026-09-23T20:06:00');
    await userEvent.click(within(dialog).getByRole('button', { name: 'Apply' }));

    expect(searchOf(router).get('from')).toBe('2026-09-23T20:05:00.000Z');
    expect(searchOf(router).get('to')).toBe('2026-09-23T20:06:00.999Z');
    expect(chips()).toHaveTextContent('from:2026-09-23 20:05:00.000 UTC');
  });

  it('text search is debounced by 300 ms and replaces the history entry', async () => {
    const spy = apiMock();
    const router = renderApp('/');
    await waitFor(() => expect(logRequests(spy)).toHaveLength(1));

    const input = within(filters()).getByRole('searchbox', { name: 'Search messages' });
    await userEvent.type(input, 'Saving owner');
    // Nothing is applied while typing…
    expect(searchOf(router).get('q')).toBeNull();

    // …only after the pause.
    await act(() => new Promise((r) => setTimeout(r, SEARCH_DEBOUNCE_MS + 50)));
    expect(searchOf(router).get('q')).toBe('Saving owner');
    expect(router.state.historyAction).toBe('REPLACE');
    await waitFor(() =>
      expect(logRequests(spy).at(-1)?.searchParams.get('q')).toBe('Saving owner'),
    );
    // One request for the initial list and one for the finished text, none per keystroke.
    expect(logRequests(spy)).toHaveLength(2);
  });

  it('active filters are chips; removing one or Clear keeps dataset and sort order', async () => {
    apiMock();
    const router = renderApp(
      '/?datasetId=smoke-01&order=asc&level=ERROR&level=WARN&service=customers-service&q=boom',
    );

    const row = chipRow();
    expect(within(row).getByText('ERROR, WARN')).toBeInTheDocument();
    expect(within(row).getByText('customers-service')).toBeInTheDocument();
    expect(within(row).getByText('“boom”')).toBeInTheDocument();
    expect(within(filters()).getByRole('searchbox')).toHaveValue('boom');

    await userEvent.click(within(row).getByRole('button', { name: 'Remove level filter' }));
    expect(searchOf(router).getAll('level')).toEqual([]);
    expect(searchOf(router).get('service')).toBe('customers-service');

    await userEvent.click(within(chipRow()).getByRole('button', { name: 'Clear' }));
    expect(router.state.location.search).toBe('?datasetId=smoke-01&order=asc');
    expect(chips()).toBeNull();
    // The search field follows the URL.
    expect(within(filters()).getByRole('searchbox')).toHaveValue('');
  });

  it('changing a filter keeps the selected log', async () => {
    apiMock();
    const router = renderApp('/logs/log-0001?datasetId=smoke-01');
    await pick('Level', 'ERROR');
    expect(router.state.location.pathname).toBe('/logs/log-0001');
    expect(searchOf(router).getAll('level')).toEqual(['ERROR']);
  });
});
