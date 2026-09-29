import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { LIVE_POLL_MS } from '../api/queries';
import type { LogSummary } from '../api/types';
import { json, mockFetch } from '../test/fetchMock';
import { logRequests, logSummary, logsRoute, renderApp, searchOf, zone } from '../test/renderApp';
import { ROW_HEIGHT } from './LogList';
import { LIVE_KEY } from './useLiveLogs';

// Live list (variant A): the newest page is polled every LIVE_POLL_MS while "Live" is on.
// Real timers; each wait covers one poll.
const POLL_WAIT = { timeout: LIVE_POLL_MS + 2_000 };

const metaRoutes = (url: URL) => {
  if (url.pathname === '/api/meta/datasets') return json([{ datasetId: 'live', count: 3 }]);
  if (url.pathname === '/api/meta/services') return json(['customers-service']);
  return undefined;
};

const range = (n: number) => Array.from({ length: n }, (_, i) => logSummary(i));
const listbox = () => within(zone('Logs')).getByRole('listbox', { name: 'Logs' });
const options = () => within(listbox()).queryAllByRole('option');
const liveButton = () => within(zone('Logs')).getByRole('button', { name: 'Live' });

/** Newest first: new logs go to the front of what the mock API serves. */
function arrive(all: LogSummary[], ...messages: string[]) {
  all.unshift(...messages.map((message, i) => logSummary(1000 + all.length + i, { message })));
}

function scrollListTo(top: number) {
  const list = listbox();
  Object.defineProperty(list, 'scrollTop', { configurable: true, value: top, writable: true });
  fireEvent.scroll(list);
}

describe('live log list', () => {
  it('is on by default and shows new logs at the top while the list is at the top', async () => {
    const logs = range(3);
    mockFetch(logsRoute(logs), metaRoutes);
    renderApp('/');
    await within(zone('Logs')).findAllByRole('option');
    expect(liveButton()).toHaveAttribute('aria-pressed', 'true');

    arrive(logs, 'fresh log');
    await waitFor(() => expect(options()[0]).toHaveTextContent('fresh log'), POLL_WAIT);
    expect(within(zone('Logs')).getByText('4 logs')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /new log/ })).toBeNull();
  });

  it('scrolled down, keeps the rows still and offers "N new logs"', async () => {
    const logs = range(150);
    mockFetch(logsRoute(logs), metaRoutes);
    renderApp('/');
    await within(zone('Logs')).findAllByRole('option');
    scrollListTo(40 * ROW_HEIGHT);
    const shown = options().map((o) => o.id);

    arrive(logs, 'new one', 'new two');
    const newLogs = await screen.findByRole('button', { name: '2 new logs' }, POLL_WAIT);
    expect(options().map((o) => o.id)).toEqual(shown);

    await userEvent.click(newLogs);
    await waitFor(() => expect(options()[0]).toHaveTextContent('new one'));
    expect(screen.queryByRole('button', { name: /new log/ })).toBeNull();
  });

  it('can be paused, and the choice is remembered', async () => {
    const logs = range(3);
    const spy = mockFetch(logsRoute(logs), metaRoutes);
    renderApp('/');
    await within(zone('Logs')).findAllByRole('option');

    await userEvent.click(liveButton());
    expect(liveButton()).toHaveAttribute('aria-pressed', 'false');
    expect(window.localStorage.getItem(LIVE_KEY)).toBe('false');
    const requests = logRequests(spy).length;

    arrive(logs, 'not shown');
    await new Promise((resolve) => setTimeout(resolve, LIVE_POLL_MS + 500));
    expect(logRequests(spy)).toHaveLength(requests);
    expect(options()[0]).not.toHaveTextContent('not shown');
  });

  it('is off while oldest first; turning it on switches back to newest first', async () => {
    const logs = range(3);
    mockFetch(logsRoute(logs), metaRoutes);
    const router = renderApp('/?order=asc');
    await within(zone('Logs')).findAllByRole('option');
    expect(liveButton()).toHaveAttribute('aria-pressed', 'false');

    await userEvent.click(liveButton());
    await waitFor(() => expect(searchOf(router).get('order')).toBeNull());
    expect(liveButton()).toHaveAttribute('aria-pressed', 'true');
  });
});
