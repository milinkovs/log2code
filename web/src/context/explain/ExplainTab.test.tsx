import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { useState } from 'react';
import { beforeEach, describe, expect, it } from 'vitest';
import type { ExplainPrompt, ExplainRequest, ExplainSection, LlmModels } from '../../api/types';
import { json, mockFetch } from '../../test/fetchMock';
import { EXPLAIN_LEVEL_KEY, EXPLAIN_MODEL_KEY } from './explainPrefs';
import { ExplainTab } from './ExplainTab';

// The explain tab (T43) against a fake `fetch`: every POST gets a stream the test writes to.

const MODELS: LlmModels = {
  configured: true,
  defaultModel: 'gemini-3.5-flash-lite',
  models: [
    { id: 'gemini-3.8-flash', label: 'Gemini 3.8 Flash' },
    { id: 'gemini-3.5-flash-lite', label: 'Gemini 3.5 Flash-Lite' },
  ],
};

const SECTIONS: ExplainSection[] = [
  { id: 'log', included: true, reason: null },
  { id: 'exception', included: true, reason: null },
  { id: 'statement', included: true, reason: null },
  { id: 'method', included: true, reason: null },
  { id: 'flow', included: true, reason: null },
  { id: 'stackCode', included: true, reason: null },
  { id: 'callers', included: false, reason: 'level' },
  { id: 'neighbors', included: false, reason: 'level' },
];

const META = {
  level: 'L2',
  model: 'gemini-3.5-flash-lite',
  promptVersion: 1,
  promptChars: 10677,
  sections: SECTIONS,
};
const DONE = { finishReason: 'STOP', promptTokens: 3786, outputTokens: 450, durationMs: 2594 };

const PROMPT: ExplainPrompt = {
  promptVersion: 1,
  level: 'L2',
  systemPrompt: 'Ti si iskusan Java inženjer.',
  userPrompt: '# Log zapis\n- Servis: customers-service',
  promptChars: 41,
  sections: SECTIONS,
};

const encoder = new TextEncoder();

/** One explain request: its body, whether it was aborted, and a stream the test writes. */
class FakeStream {
  readonly body: ExplainRequest;
  readonly logId: string;
  aborted = false;
  private controller!: ReadableStreamDefaultController<Uint8Array>;
  readonly stream: ReadableStream<Uint8Array>;

  constructor(logId: string, body: ExplainRequest, signal: AbortSignal | null | undefined) {
    this.logId = logId;
    this.body = body;
    this.stream = new ReadableStream({ start: (c) => void (this.controller = c) });
    signal?.addEventListener('abort', () => {
      this.aborted = true;
      this.controller.error(new DOMException('The operation was aborted.', 'AbortError'));
    });
  }

  send(event: string, data: unknown) {
    act(() =>
      this.controller.enqueue(encoder.encode(`event:${event}\ndata:${JSON.stringify(data)}\n\n`)),
    );
  }

  close() {
    act(() => this.controller.close());
  }
}

interface Setup {
  models?: LlmModels;
  /** Answer for a POST instead of a stream (e.g. a 503 before streaming, or a network error). */
  post?: () => Response | Promise<Response>;
}

function setup({ models = MODELS, post }: Setup = {}) {
  const streams: FakeStream[] = [];
  const spy = mockFetch((url, init) => {
    const p = url.pathname;
    if (p === '/api/llm/models') return json(models);
    if (p.endsWith('/explain/prompt'))
      return json({ ...PROMPT, level: url.searchParams.get('level') });
    const explain = p.match(/^\/api\/logs\/([^/]+)\/explain$/);
    if (explain && init?.method === 'POST') {
      if (post) return post() as Response;
      const fake = new FakeStream(explain[1], JSON.parse(String(init.body)), init.signal);
      streams.push(fake);
      return new Response(fake.stream, { headers: { 'Content-Type': 'text/event-stream' } });
    }
    return undefined;
  });
  const posts = () => spy.mock.calls.filter(([, init]) => init?.method === 'POST');
  return { streams, spy, posts };
}

/** Mounts the tab as the context zone does: keyed by log id. */
function Harness({ initial }: { initial: string }) {
  const [logId, setLogId] = useState(initial);
  return (
    <>
      <button type="button" onClick={() => setLogId('log-other')}>
        Select another log
      </button>
      <ExplainTab key={logId} logId={logId} />
    </>
  );
}

function renderTab(logId = 'log-1') {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <QueryClientProvider client={client}>
      <Harness initial={logId} />
    </QueryClientProvider>,
  );
}

const explainButton = () => screen.findByRole('button', { name: 'Explain' });

async function startExplain(env: ReturnType<typeof setup>) {
  await userEvent.click(await explainButton());
  await waitFor(() => expect(env.streams.length).toBeGreaterThan(0));
  return env.streams.at(-1) as FakeStream;
}

/** Streams a whole answer: meta, the deltas, done. */
function answer(stream: FakeStream, ...deltas: string[]) {
  stream.send('meta', META);
  deltas.forEach((text) => stream.send('delta', { text }));
  stream.send('done', DONE);
  stream.close();
}

beforeEach(() => {
  window.localStorage.clear();
});

describe('explain tab', () => {
  it('shows the controls with the defaults and sends nothing before a click', async () => {
    const env = setup();
    renderTab();
    expect(await explainButton()).toBeEnabled();
    expect(
      screen.getByRole('button', { name: 'Context level: L2 + conditions and stack trace code' }),
    ).toBeInTheDocument();
    expect(
      screen.getByRole('button', { name: 'Model: Gemini 3.5 Flash-Lite' }),
    ).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Show prompt' })).toBeInTheDocument();
    expect(screen.getByText('Explain this log')).toBeInTheDocument();
    expect(env.posts()).toHaveLength(0);
  });

  it('remembers the chosen level and model', async () => {
    const env = setup();
    renderTab();
    await userEvent.click(await screen.findByRole('button', { name: /^Context level: L2/ }));
    await userEvent.click(await screen.findByRole('menuitemradio', { name: /L4/ }));
    await userEvent.click(screen.getByRole('button', { name: /^Model:/ }));
    await userEvent.click(await screen.findByRole('menuitemradio', { name: /Gemini 3.8 Flash/ }));
    expect(window.localStorage.getItem(EXPLAIN_LEVEL_KEY)).toBe('L4');
    expect(window.localStorage.getItem(EXPLAIN_MODEL_KEY)).toBe('gemini-3.8-flash');

    const stream = await startExplain(env);
    expect(stream.body).toEqual({ level: 'L4', model: 'gemini-3.8-flash', turns: [] });
  });

  it('falls back to the default model when the remembered one is not offered any more', async () => {
    window.localStorage.setItem(EXPLAIN_MODEL_KEY, 'gemini-1.0-gone');
    window.localStorage.setItem(EXPLAIN_LEVEL_KEY, 'L9');
    const env = setup();
    renderTab();
    const stream = await startExplain(env);
    expect(stream.body).toEqual({ level: 'L2', model: 'gemini-3.5-flash-lite', turns: [] });
  });

  it('shows a callout and turns "Explain" off when no API key is set', async () => {
    const env = setup({ models: { ...MODELS, configured: false } });
    renderTab();
    const button = await explainButton();
    expect(button).toBeDisabled();
    const note = screen.getByText(
      'Explain needs a Gemini API key. Add LOG2CODE_GEMINI_API_KEY to infra/.env.local and restart log2code-api.',
    );
    expect(button).toHaveAccessibleDescription(note.textContent as string);
    expect(env.posts()).toHaveLength(0);
  });

  it('adds the deltas in order, then shows the numbers of the answer', async () => {
    const env = setup();
    renderTab();
    const stream = await startExplain(env);
    expect(screen.getByRole('button', { name: 'Stop' })).toBeInTheDocument();
    stream.send('meta', META);
    expect(await screen.findByText('Thinking…')).toBeInTheDocument();
    const included = screen.getByText('Included:').parentElement as HTMLElement;
    expect(
      within(included)
        .getAllByText(/./, { selector: '.badge' })
        .map((b) => b.textContent),
    ).toEqual([
      'log',
      'exception',
      'statement',
      'method source',
      'conditions and flow',
      'stack trace code',
    ]);

    stream.send('delta', { text: '### Šta se desilo\n\nPri čuvanju ' });
    stream.send('delta', { text: 'ljubimca.' });
    const conversation = screen.getByLabelText('Explanation');
    expect(
      await within(conversation).findByRole('heading', { name: 'Šta se desilo' }),
    ).toBeInTheDocument();
    expect(within(conversation).getByText('Pri čuvanju ljubimca.')).toBeInTheDocument();
    expect(conversation.querySelector('[aria-busy="true"]')).not.toBeNull();
    expect(screen.queryByText('Thinking…')).toBeNull();

    stream.send('done', DONE);
    stream.close();
    expect(
      await screen.findByText('gemini-3.5-flash-lite · L2 · 3,786 in / 450 out · 2.6 s'),
    ).toBeInTheDocument();
    expect(conversation.querySelector('[aria-busy="true"]')).toBeNull();
    expect(screen.getByRole('button', { name: 'Explain' })).toBeInTheDocument();
    expect(screen.queryByText('The answer was cut off.')).toBeNull();
  });

  it('warns when the answer was cut off (MAX_TOKENS)', async () => {
    const env = setup();
    renderTab();
    const stream = await startExplain(env);
    stream.send('meta', META);
    stream.send('delta', { text: 'Počinje' });
    stream.send('done', { ...DONE, finishReason: 'MAX_TOKENS', outputTokens: null });
    stream.close();
    expect(await screen.findByText('The answer was cut off.')).toBeInTheDocument();
    expect(
      screen.getByText('gemini-3.5-flash-lite · L2 · 3,786 in / ? out · 2.6 s'),
    ).toBeInTheDocument();
  });

  it('"Stop" aborts the request and keeps the text so far', async () => {
    const env = setup();
    renderTab();
    const stream = await startExplain(env);
    stream.send('meta', META);
    stream.send('delta', { text: 'Delimičan odgovor' });
    await userEvent.click(await screen.findByRole('button', { name: 'Stop' }));
    expect(stream.aborted).toBe(true);
    expect(await screen.findByText('Stopped.')).toBeInTheDocument();
    expect(screen.getByText('Delimičan odgovor')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Retry' })).toBeNull();
  });

  it.each([
    ['rate_limited', 'Gemini rate limit reached. Try again in a minute.'],
    ['invalid_key', 'The Gemini API key was rejected. Check infra/.env.local.'],
    ['blocked', 'Gemini blocked this answer.'],
    ['timeout', "Gemini didn't answer in time."],
    ['upstream', 'Gemini returned an error: Gemini returned HTTP 503: high demand'],
  ])('an error event %s shows its message', async (code, text) => {
    const env = setup();
    renderTab();
    const stream = await startExplain(env);
    stream.send('meta', META);
    stream.send('error', { code, message: 'Gemini returned HTTP 503: high demand' });
    stream.close();
    expect(await screen.findByRole('alert')).toHaveTextContent(text);
  });

  it('a network failure says the API cannot be reached', async () => {
    setup({ post: () => Promise.reject(new TypeError('Failed to fetch')) });
    renderTab();
    await userEvent.click(await explainButton());
    expect(await screen.findByRole('alert')).toHaveTextContent("Couldn't reach log2code-api.");
    expect(screen.queryByRole('button', { name: 'Retry' })).toBeNull();
  });

  it('a 503 before streaming (no key) shows the key message', async () => {
    setup({
      post: () =>
        json(
          {
            title: 'Service Unavailable',
            status: 503,
            detail: 'not configured',
            code: 'llm_not_configured',
          },
          503,
        ),
    });
    renderTab();
    await userEvent.click(await explainButton());
    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Explain needs a Gemini API key. Add LOG2CODE_GEMINI_API_KEY to infra/.env.local and restart log2code-api.',
    );
    expect(screen.queryByRole('button', { name: 'Retry' })).toBeNull();
  });

  it('a stream that ends without "done" or "error" is an interrupted connection; the text stays', async () => {
    const env = setup();
    renderTab();
    const stream = await startExplain(env);
    stream.send('meta', META);
    stream.send('delta', { text: 'Prvi deo odgovora' });
    stream.close();
    expect(await screen.findByText('The connection was interrupted.')).toBeInTheDocument();
    expect(screen.getByText('Prvi deo odgovora')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Retry' })).toBeInTheDocument();
  });

  it.each([
    ['upstream', true],
    ['rate_limited', true],
    ['timeout', true],
    ['invalid_key', false],
    ['blocked', false],
  ])('"Retry" after %s: offered = %s', async (code, offered) => {
    const env = setup();
    renderTab();
    const stream = await startExplain(env);
    stream.send('meta', META);
    stream.send('error', { code, message: 'x' });
    stream.close();
    await screen.findByRole('alert');
    expect(!!screen.queryByRole('button', { name: 'Retry' })).toBe(offered);
  });

  it('no "Retry" when no API key is set', async () => {
    setup({ models: { ...MODELS, configured: false } });
    renderTab();
    await explainButton();
    expect(screen.queryByRole('button', { name: 'Retry' })).toBeNull();
  });

  it('"Retry" sends the same request and replaces the failed attempt', async () => {
    const env = setup();
    renderTab();
    await userEvent.click(await explainButton());
    await waitFor(() => expect(env.streams).toHaveLength(1));
    answer(env.streams[0], 'Prvo objašnjenje.');
    const box = await screen.findByRole('textbox', { name: 'Follow-up question' });
    await userEvent.type(box, 'Zašto?{Enter}');
    await waitFor(() => expect(env.streams).toHaveLength(2));
    const failed = env.streams[1];
    failed.send('meta', META);
    failed.send('delta', { text: 'Nedovršen tekst' });
    failed.send('error', { code: 'upstream', message: 'Gemini returned HTTP 503: high demand' });
    failed.close();

    await userEvent.click(await screen.findByRole('button', { name: 'Retry' }));
    await waitFor(() => expect(env.streams).toHaveLength(3));
    expect(env.streams[2].body).toEqual(failed.body);
    answer(env.streams[2], 'Novi odgovor.');
    expect(await screen.findByText('Novi odgovor.')).toBeInTheDocument();
    expect(screen.queryByText('Nedovršen tekst')).toBeNull();
    expect(screen.queryByRole('alert')).toBeNull();
    expect(screen.getAllByText('Zašto?')).toHaveLength(1);
  });

  it('"Retry" of an interrupted first answer sends the first request again', async () => {
    const env = setup();
    renderTab();
    const first = await startExplain(env);
    first.send('meta', META);
    first.close();
    await userEvent.click(await screen.findByRole('button', { name: 'Retry' }));
    await waitFor(() => expect(env.streams).toHaveLength(2));
    expect(env.streams[1].body).toEqual({ level: 'L2', model: 'gemini-3.5-flash-lite', turns: [] });
  });

  it('a follow-up question sends the whole conversation as turns', async () => {
    const env = setup();
    renderTab();
    answer(await startExplain(env), 'Prvi ', 'odgovor.');
    const box = await screen.findByRole('textbox', { name: 'Follow-up question' });
    await userEvent.type(box, 'Kako da popravim?{Shift>}{Enter}{/Shift}Tačno.');
    expect(env.posts()).toHaveLength(1);
    await userEvent.click(screen.getByRole('button', { name: 'Ask' }));
    await waitFor(() => expect(env.streams).toHaveLength(2));
    expect(env.streams[1].body).toEqual({
      level: 'L2',
      model: 'gemini-3.5-flash-lite',
      turns: [
        { role: 'model', text: 'Prvi odgovor.' },
        { role: 'user', text: 'Kako da popravim?\nTačno.' },
      ],
    });
    expect(box).toHaveValue('');
    expect(box).toBeDisabled();
    answer(env.streams[1], 'Drugi odgovor.');
    await waitFor(() => expect(box).toBeEnabled());

    await userEvent.type(box, 'I još?{Enter}');
    await waitFor(() => expect(env.streams).toHaveLength(3));
    expect(env.streams[2].body.turns).toEqual([
      { role: 'model', text: 'Prvi odgovor.' },
      { role: 'user', text: 'Kako da popravim?\nTačno.' },
      { role: 'model', text: 'Drugi odgovor.' },
      { role: 'user', text: 'I još?' },
    ]);
  });

  it('a follow-up after an empty answer sends a model turn with empty text', async () => {
    const env = setup();
    renderTab();
    const stream = await startExplain(env);
    stream.send('meta', META);
    stream.send('done', { ...DONE, finishReason: 'MAX_TOKENS' });
    stream.close();
    const box = await screen.findByRole('textbox', { name: 'Follow-up question' });
    await waitFor(() => expect(box).toBeEnabled());
    await userEvent.type(box, 'Ponovi?{Enter}');
    await waitFor(() => expect(env.streams).toHaveLength(2));
    expect(env.streams[1].body.turns).toEqual([
      { role: 'model', text: '' },
      { role: 'user', text: 'Ponovi?' },
    ]);
  });

  it('after ten follow-up questions asks for a new explanation', async () => {
    const env = setup();
    renderTab();
    answer(await startExplain(env), 'Odgovor 0.');
    for (let i = 1; i <= 10; i++) {
      const box = await screen.findByRole('textbox', { name: 'Follow-up question' });
      await waitFor(() => expect(box).toBeEnabled());
      await userEvent.type(box, `Pitanje ${i}{Enter}`);
      await waitFor(() => expect(env.streams).toHaveLength(i + 1));
      answer(env.streams[i], `Odgovor ${i}.`);
    }
    expect(await screen.findByText('Start a new explanation to continue.')).toBeInTheDocument();
    expect(screen.queryByRole('textbox', { name: 'Follow-up question' })).toBeNull();
    expect(env.streams[10].body.turns).toHaveLength(20);
  });

  it('"Explain" after a change of level starts a new conversation', async () => {
    const env = setup();
    renderTab();
    answer(await startExplain(env), 'Prvi odgovor.');
    await screen.findByText('Prvi odgovor.');
    await userEvent.click(screen.getByRole('button', { name: /^Context level:/ }));
    await userEvent.click(await screen.findByRole('menuitemradio', { name: /L0/ }));
    await userEvent.click(screen.getByRole('button', { name: 'Explain' }));
    await waitFor(() => expect(env.streams).toHaveLength(2));
    expect(env.streams[1].body).toEqual({ level: 'L0', model: 'gemini-3.5-flash-lite', turns: [] });
    expect(screen.queryByText('Prvi odgovor.')).toBeNull();
  });

  it('another log clears the conversation and aborts the stream', async () => {
    const env = setup();
    renderTab('log-1');
    const stream = await startExplain(env);
    stream.send('meta', META);
    stream.send('delta', { text: 'Odgovor za prvi log' });
    await screen.findByText('Odgovor za prvi log');

    await userEvent.click(screen.getByRole('button', { name: 'Select another log' }));
    expect(stream.aborted).toBe(true);
    expect(screen.queryByText('Odgovor za prvi log')).toBeNull();
    expect(await screen.findByText('Explain this log')).toBeInTheDocument();
    expect(env.posts()).toHaveLength(1);

    const next = await startExplain(env);
    expect(next.logId).toBe('log-other');
  });

  it('"Show prompt" opens a dialog with the prompt of the chosen level', async () => {
    const env = setup();
    renderTab();
    await userEvent.click(await screen.findByRole('button', { name: 'Show prompt' }));
    const dialog = await screen.findByRole('dialog', { name: 'Prompt · L2' });
    expect(await within(dialog).findByText('Ti si iskusan Java inženjer.')).toBeInTheDocument();
    expect(within(dialog).getByText(/# Log zapis/)).toBeInTheDocument();
    expect(within(dialog).getByText('41 characters')).toBeInTheDocument();
    expect(within(dialog).getAllByText('left out: not in this level')).toHaveLength(2);
    expect(within(dialog).getByRole('button', { name: 'Copy' })).toBeInTheDocument();
    const prompts = env.spy.mock.calls
      .map(([input]) => new URL(String(input), 'http://localhost'))
      .filter((url) => url.pathname.endsWith('/explain/prompt'));
    expect(prompts.map((url) => url.searchParams.get('level'))).toEqual(['L2']);
    expect(env.posts()).toHaveLength(0);

    await userEvent.click(within(dialog).getByRole('button', { name: 'Close' }));
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
    expect(screen.getByRole('button', { name: 'Show prompt' })).toHaveFocus();
  });
});
