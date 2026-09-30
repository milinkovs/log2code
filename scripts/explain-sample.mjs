#!/usr/bin/env node
// Calls POST /api/logs/{id}/explain on log2code-api (T42) and prints the answer as markdown.
// No dependencies (Node 18+). The Gemini key is never read here: only the API uses it.
//
// Usage: node scripts/explain-sample.mjs <logId> <L0..L4> [model] [--delay <s>] [--retries <n>] [--api <url>]
//   model      one of GET /api/llm/models; default is the API's defaultModel
//   --delay    seconds to wait before the first call (free-tier rate limits)
//   --retries  extra attempts after a temporary error (rate_limited, upstream, timeout); waits 20 s between them
//   --api      base URL of log2code-api (default http://localhost:8090)
//
// stdout: the answer, then one summary line. stderr: progress and errors.
// Exit codes: 0 = answer received, 1 = bad input or the API refused the request, 2 = failure after all attempts.

const RETRY_WAIT_S = 20;
const RETRYABLE = new Set(['rate_limited', 'upstream', 'timeout']);

function usage(message) {
  if (message) console.error(`explain-sample: ${message}`);
  console.error('Usage: node scripts/explain-sample.mjs <logId> <L0..L4> [model] [--delay <s>] [--retries <n>] [--api <url>]');
  process.exit(1);
}

const positional = [];
const opts = { delay: 0, retries: 0, api: 'http://localhost:8090' };
const argv = process.argv.slice(2);
for (let i = 0; i < argv.length; i++) {
  const a = argv[i];
  if (a === '--delay' || a === '--retries' || a === '--api') {
    const v = argv[++i];
    if (v === undefined) usage(`${a} needs a value`);
    if (a === '--api') opts.api = v.replace(/\/+$/, '');
    else {
      const n = Number(v);
      if (!Number.isFinite(n) || n < 0) usage(`${a} must be a non-negative number`);
      opts[a.slice(2)] = n;
    }
  } else if (a === '--help' || a === '-h') usage();
  else if (a.startsWith('--')) usage(`unknown option ${a}`);
  else positional.push(a);
}
const [logId, level, modelArg] = positional;
if (positional.length < 2 || positional.length > 3) usage();
if (!/^L[0-4]$/.test(level)) usage('level must be one of L0, L1, L2, L3, L4');

const sleep = (s) => new Promise((r) => setTimeout(r, s * 1000));

async function defaultModel() {
  const res = await fetch(`${opts.api}/api/llm/models`);
  if (!res.ok) throw new Error(`GET /api/llm/models returned HTTP ${res.status}`);
  return (await res.json()).defaultModel;
}

/** One request. Returns {answer, meta, done} or {error:{code,message}}; throws on refusal before the stream. */
async function explainOnce(model) {
  const res = await fetch(`${opts.api}/api/logs/${logId}/explain`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Accept: 'text/event-stream' },
    body: JSON.stringify({ level, model, turns: [] }),
  });
  if (!res.ok) {
    let detail = '';
    try {
      const p = await res.json();
      detail = p.detail || p.title || '';
    } catch {
      /* body is not JSON */
    }
    const err = new Error(`HTTP ${res.status}${detail ? `: ${detail}` : ''}`);
    err.refused = true;
    throw err;
  }
  const decoder = new TextDecoder();
  let buffer = '';
  let meta = null;
  let done = null;
  let error = null;
  let answer = '';
  const handle = (block) => {
    let event = 'message';
    const data = [];
    for (const line of block.split('\n')) {
      if (line.startsWith('event:')) event = line.slice(6).trim();
      else if (line.startsWith('data:')) data.push(line.slice(5).replace(/^ /, ''));
    }
    if (!data.length) return;
    const payload = JSON.parse(data.join('\n'));
    if (event === 'meta') meta = payload;
    else if (event === 'delta') answer += payload.text;
    else if (event === 'done') done = payload;
    else if (event === 'error') error = payload;
  };
  for await (const chunk of res.body) {
    buffer += decoder.decode(chunk, { stream: true }).replace(/\r\n/g, '\n');
    let cut;
    while ((cut = buffer.indexOf('\n\n')) >= 0) {
      handle(buffer.slice(0, cut));
      buffer = buffer.slice(cut + 2);
    }
  }
  if (buffer.trim()) handle(buffer);
  if (error) return { error };
  if (!done) return { error: { code: 'interrupted', message: 'The stream ended without done or error.' } };
  return { answer, meta, done };
}

try {
  const model = modelArg ?? (await defaultModel());
  if (opts.delay > 0) {
    console.error(`waiting ${opts.delay} s before the call`);
    await sleep(opts.delay);
  }
  let attempts = 0;
  let last = null;
  while (attempts <= opts.retries) {
    attempts++;
    last = await explainOnce(model);
    if (!last.error) break;
    console.error(`attempt ${attempts}: ${last.error.code}: ${last.error.message}`);
    if (!RETRYABLE.has(last.error.code) || attempts > opts.retries) break;
    console.error(`retrying in ${RETRY_WAIT_S} s`);
    await sleep(RETRY_WAIT_S);
  }
  if (last.error) {
    console.error(`explain-sample: failed after ${attempts} attempt(s): ${last.error.code}`);
    process.exit(2);
  }
  const { answer, meta, done } = last;
  process.stdout.write(`${answer.replace(/\s+$/, '')}\n\n`);
  process.stdout.write(
    `[model=${model} level=${meta.level} promptChars=${meta.promptChars} promptTokens=${done.promptTokens ?? 'n/a'} ` +
      `outputTokens=${done.outputTokens ?? 'n/a'} durationMs=${done.durationMs} finishReason=${done.finishReason} attempts=${attempts}]\n`,
  );
} catch (e) {
  console.error(`explain-sample: ${e.message}`);
  process.exit(e.refused ? 1 : 2);
}
