import { useCallback, useEffect, useRef, useState } from 'react';
import { ApiError, streamExplain } from '../../api/client';
import type {
  ExplainDoneEvent,
  ExplainErrorCode,
  ExplainLevel,
  ExplainMetaEvent,
  ExplainTurn,
} from '../../api/types';

/** Follow-up questions allowed in one conversation; then a new explanation is needed. */
export const MAX_FOLLOW_UPS = 10;

/**
 * Why an answer failed: an `error` event code (T42), or what the browser saw itself. `request` is
 * a request the API rejected before streaming (400, 404); `network` is no answer at all;
 * `interrupted` is a stream that ended without `done` or `error` (docs/llm-explain.md).
 */
export type ExplainFailureKind =
  ExplainErrorCode | 'not_configured' | 'network' | 'interrupted' | 'request';

export interface ExplainFailure {
  kind: ExplainFailureKind | string;
  message: string;
}

/** Failures that the same request may get past a moment later (ADR-041). */
const RETRYABLE: ReadonlySet<string> = new Set([
  'upstream',
  'rate_limited',
  'timeout',
  'interrupted',
]);

export const canRetry = (failure: ExplainFailure) => RETRYABLE.has(failure.kind);

/** `waiting`: sent, no text yet ("Thinking…"); `streaming`: text arriving. */
export type AnswerState = 'waiting' | 'streaming' | 'done' | 'stopped' | 'error';

export interface ExplainAnswer {
  role: 'model';
  text: string;
  state: AnswerState;
  done?: ExplainDoneEvent;
  failure?: ExplainFailure;
}

export interface ExplainQuestion {
  role: 'user';
  text: string;
}

export type ExplainMessage = ExplainAnswer | ExplainQuestion;

export type SessionStatus = 'idle' | 'streaming' | 'done' | 'stopped' | 'error';

export interface ExplainSession {
  status: SessionStatus;
  /** The first answer, then question–answer pairs. */
  messages: ExplainMessage[];
  /** The last `meta` event: the level, model and sections of the prompt. */
  meta: ExplainMetaEvent | null;
  /** Level and model of this conversation (every request of it uses them). */
  level: ExplainLevel | null;
  model: string | null;
  followUps: number;
  explain: (level: ExplainLevel, model: string) => void;
  ask: (question: string) => void;
  retry: () => void;
  stop: () => void;
}

interface State {
  messages: ExplainMessage[];
  meta: ExplainMetaEvent | null;
  level: ExplainLevel | null;
  model: string | null;
}

const EMPTY: State = { messages: [], meta: null, level: null, model: null };

const toTurns = (messages: ExplainMessage[]): ExplainTurn[] =>
  messages.map(({ role, text }) => ({ role, text }));

const lastAnswer = (messages: ExplainMessage[]): ExplainAnswer | undefined => {
  const last = messages[messages.length - 1];
  return last?.role === 'model' ? last : undefined;
};

function failureOf(error: unknown, gotEvents: boolean): ExplainFailure {
  if (error instanceof ApiError) {
    if (error.status === 503 && error.code === 'llm_not_configured') {
      return { kind: 'not_configured', message: error.message };
    }
    return { kind: 'request', message: error.message };
  }
  const message = error instanceof Error ? error.message : String(error);
  // A failure after the stream opened is a cut connection, not an unreachable API.
  return { kind: gotEvents ? 'interrupted' : 'network', message };
}

/**
 * One explain conversation for one log (T43): `idle → streaming → done | stopped | error`. The
 * browser keeps the conversation and sends it in `turns` (the server keeps nothing, D9). Nothing is
 * sent until `explain` is called. "Stop", a new request and unmount abort the request in flight;
 * mount the owner with `key={logId}` so that another log starts from an empty conversation.
 */
export function useExplainSession(logId: string): ExplainSession {
  const [state, setState] = useState<State>(EMPTY);
  const controller = useRef<AbortController | null>(null);
  const stopped = useRef(false);

  useEffect(
    () => () => {
      controller.current?.abort();
      controller.current = null;
    },
    [],
  );

  /** Replaces the last message (the answer being written) with `update(answer)`. */
  const updateAnswer = useCallback((update: (answer: ExplainAnswer) => ExplainAnswer) => {
    setState((s) => {
      const answer = lastAnswer(s.messages);
      if (!answer) return s;
      return { ...s, messages: [...s.messages.slice(0, -1), update(answer)] };
    });
  }, []);

  /** Sends `before` as the conversation and writes the answer into a new last message. */
  const run = useCallback(
    (level: ExplainLevel, model: string, before: ExplainMessage[]) => {
      controller.current?.abort();
      const own = new AbortController();
      controller.current = own;
      stopped.current = false;
      setState((s) => ({
        ...s,
        level,
        model,
        messages: [...before, { role: 'model', text: '', state: 'waiting' }],
      }));

      let gotEvents = false;
      let finished = false;
      const isCurrent = () => controller.current === own;
      streamExplain(
        logId,
        { level, model, turns: toTurns(before) },
        {
          signal: own.signal,
          onEvent: (event) => {
            if (!isCurrent()) return;
            gotEvents = true;
            switch (event.type) {
              case 'meta':
                setState((s) => ({ ...s, meta: event }));
                break;
              case 'delta':
                updateAnswer((a) => ({ ...a, text: a.text + event.text, state: 'streaming' }));
                break;
              case 'done':
                finished = true;
                updateAnswer((a) => ({ ...a, state: 'done', done: event }));
                break;
              case 'error':
                finished = true;
                updateAnswer((a) => ({
                  ...a,
                  state: 'error',
                  failure: { kind: event.code, message: event.message },
                }));
                break;
            }
          },
        },
      )
        .then(() => {
          if (!isCurrent() || finished) return;
          updateAnswer((a) => ({
            ...a,
            state: 'error',
            failure: { kind: 'interrupted', message: 'The stream ended without an answer.' },
          }));
        })
        .catch((error: unknown) => {
          if (!isCurrent()) return;
          if (own.signal.aborted) {
            if (stopped.current) updateAnswer((a) => ({ ...a, state: 'stopped' }));
            return;
          }
          updateAnswer((a) => ({ ...a, state: 'error', failure: failureOf(error, gotEvents) }));
        })
        .finally(() => {
          if (isCurrent()) controller.current = null;
        });
    },
    [logId, updateAnswer],
  );

  const explain = useCallback(
    (level: ExplainLevel, model: string) => {
      setState(EMPTY);
      run(level, model, []);
    },
    [run],
  );

  const ask = useCallback(
    (question: string) => {
      const text = question.trim();
      if (!text || !state.level || !state.model) return;
      run(state.level, state.model, [...state.messages, { role: 'user', text }]);
    },
    [run, state.level, state.model, state.messages],
  );

  const retry = useCallback(() => {
    if (!state.level || !state.model || !lastAnswer(state.messages)) return;
    // The same request again: the failed attempt's text is replaced by the new one.
    run(state.level, state.model, state.messages.slice(0, -1));
  }, [run, state.level, state.model, state.messages]);

  const stop = useCallback(() => {
    if (!controller.current) return;
    stopped.current = true;
    controller.current.abort();
  }, []);

  const answer = lastAnswer(state.messages);
  const status: SessionStatus = !answer
    ? 'idle'
    : answer.state === 'waiting' || answer.state === 'streaming'
      ? 'streaming'
      : answer.state;
  const followUps = state.messages.filter((m) => m.role === 'user').length;

  return { ...state, status, followUps, explain, ask, retry, stop };
}
