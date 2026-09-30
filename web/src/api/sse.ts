/** One Server-Sent Event: its `event:` name (`message` when absent) and its `data:` lines joined. */
export interface SseMessage {
  event: string;
  data: string;
}

/**
 * Incremental Server-Sent Events parser (the parts of the WHATWG rules the explain stream needs).
 * Text may arrive split anywhere, even inside a line or between `\r` and `\n`. Lines end with LF,
 * CR or CRLF; a blank line ends an event; several `data:` lines are joined with `\n`; lines that
 * start with `:` are comments. `id:` and `retry:` are ignored. An event without data is dropped.
 */
export class SseParser {
  private buffer = '';
  private event = '';
  private data: string[] = [];

  /** Adds text and returns the events it completed, in order. */
  push(text: string): SseMessage[] {
    this.buffer += text;
    const out: SseMessage[] = [];
    let start = 0;
    for (let i = 0; i < this.buffer.length; i++) {
      const c = this.buffer[i];
      if (c !== '\n' && c !== '\r') continue;
      // A CR at the very end may be the first half of a CRLF that is still on its way.
      if (c === '\r' && i === this.buffer.length - 1) break;
      this.line(this.buffer.slice(start, i), out);
      if (c === '\r' && this.buffer[i + 1] === '\n') i++;
      start = i + 1;
    }
    this.buffer = this.buffer.slice(start);
    return out;
  }

  private line(line: string, out: SseMessage[]) {
    if (line === '') {
      if (this.data.length > 0)
        out.push({ event: this.event || 'message', data: this.data.join('\n') });
      this.event = '';
      this.data = [];
      return;
    }
    if (line.startsWith(':')) return;
    const colon = line.indexOf(':');
    const field = colon < 0 ? line : line.slice(0, colon);
    let value = colon < 0 ? '' : line.slice(colon + 1);
    if (value.startsWith(' ')) value = value.slice(1);
    if (field === 'event') this.event = value;
    else if (field === 'data') this.data.push(value);
  }
}

/**
 * Reads a byte stream as Server-Sent Events and calls `onMessage` for each. UTF-8 is decoded in
 * streaming mode, so a character split between two reads is not broken. Resolves when the stream
 * ends; an incomplete last event (no blank line after it) is dropped, as in a browser.
 */
export async function readSse(
  body: ReadableStream<Uint8Array>,
  onMessage: (message: SseMessage) => void,
): Promise<void> {
  const reader = body.getReader();
  const decoder = new TextDecoder();
  const parser = new SseParser();
  try {
    for (;;) {
      const { done, value } = await reader.read();
      if (done) break;
      parser.push(decoder.decode(value, { stream: true })).forEach(onMessage);
    }
    parser.push(decoder.decode()).forEach(onMessage);
  } finally {
    reader.releaseLock();
  }
}
