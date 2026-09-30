import { describe, expect, it } from 'vitest';
import { SseParser, readSse, type SseMessage } from './sse';

const encoder = new TextEncoder();

/** A byte stream that delivers the given chunks one read at a time. */
function streamOf(...chunks: (string | Uint8Array)[]): ReadableStream<Uint8Array> {
  return new ReadableStream({
    start(controller) {
      for (const chunk of chunks) {
        controller.enqueue(typeof chunk === 'string' ? encoder.encode(chunk) : chunk);
      }
      controller.close();
    },
  });
}

async function readAll(stream: ReadableStream<Uint8Array>): Promise<SseMessage[]> {
  const out: SseMessage[] = [];
  await readSse(stream, (message) => out.push(message));
  return out;
}

describe('SseParser', () => {
  it('reads several events from one chunk', () => {
    const parser = new SseParser();
    expect(parser.push('event:meta\ndata:{"a":1}\n\nevent:delta\ndata:{"text":"x"}\n\n')).toEqual([
      { event: 'meta', data: '{"a":1}' },
      { event: 'delta', data: '{"text":"x"}' },
    ]);
  });

  it('joins an event split anywhere, even inside a field name', () => {
    const parser = new SseParser();
    const text = 'event:delta\ndata:{"text":"Hello"}\n\n';
    const seen: SseMessage[] = [];
    for (const piece of [text.slice(0, 3), text.slice(3, 14), text.slice(14, 30), text.slice(30)]) {
      seen.push(...parser.push(piece));
    }
    expect(seen).toEqual([{ event: 'delta', data: '{"text":"Hello"}' }]);
  });

  it('accepts CRLF and CR line ends, also with CR and LF in different chunks', () => {
    const parser = new SseParser();
    expect(parser.push('event: done\r\ndata: {}\r')).toEqual([]);
    expect(parser.push('\n\r\n')).toEqual([{ event: 'done', data: '{}' }]);
    // A CR at the end of a chunk waits: it may be the first half of a CRLF.
    expect(parser.push('data: a\r\r')).toEqual([]);
    expect(parser.push('data: b\r\r')).toEqual([{ event: 'message', data: 'a' }]);
  });

  it('joins several data lines with a new line and strips one leading space', () => {
    const parser = new SseParser();
    expect(parser.push('data: one\ndata:  two\ndata\n\n')).toEqual([
      { event: 'message', data: 'one\n two\n' },
    ]);
  });

  it('ignores comments, other fields and events without data', () => {
    const parser = new SseParser();
    expect(parser.push(': keep-alive\n\nid: 7\nretry: 10\nevent: meta\n\ndata: x\n\n')).toEqual([
      { event: 'message', data: 'x' },
    ]);
  });
});

describe('readSse', () => {
  it('decodes UTF-8 split between two reads', async () => {
    const bytes = encoder.encode('data: {"text":"Šta"}\n\n');
    // "Š" is two bytes (0xC5 0xA0); cut between them.
    const cut = bytes.indexOf(0xc5) + 1;
    expect(await readAll(streamOf(bytes.slice(0, cut), bytes.slice(cut)))).toEqual([
      { event: 'message', data: '{"text":"Šta"}' },
    ]);
  });

  it('drops an incomplete last event, as a browser does', async () => {
    expect(await readAll(streamOf('event: delta\ndata: a\n\n', 'event: done\ndata: {}\n'))).toEqual(
      [{ event: 'delta', data: 'a' }],
    );
  });
});
