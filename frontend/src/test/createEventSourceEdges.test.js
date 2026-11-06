import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import createEventSourceUntracked from '../utils/createEventSource.js';

/**
 * Edge behaviour of the fetch-based event-stream reader: the line parser's corners (a lone CR at the
 * end of a chunk, a field with no colon, unknown fields between `event:` and `data:`), the
 * closeOn guards (an empty or handler-less event never closes the stream), a response that
 * resolves or a read that settles after cleanup, a response with no headers, the hidden-tab guard
 * and the exact listeners cleanup removes. Companion to createEventSource.test.js.
 */

const encoder = new TextEncoder();

// A scripted body the test pushes chunks into, so it controls where chunk boundaries fall.
function openStream() {
  let controller;
  const body = new ReadableStream({
    start(c) {
      controller = c;
    },
  });
  const response = new Response(body, { status: 200, headers: { 'Content-Type': 'text/event-stream' } });
  return {
    response,
    attach: (signal) => {
      signal.addEventListener('abort', () => {
        try {
          controller.error(new DOMException('aborted', 'AbortError'));
        } catch {
          // already closed
        }
      });
    },
    push: (chunk) => controller.enqueue(encoder.encode(chunk)),
  };
}

async function settle() {
  await vi.advanceTimersByTimeAsync(0);
}

const openConnections = [];

function createEventSource(...args) {
  const close = createEventSourceUntracked(...args);
  openConnections.push(close);
  return close;
}

describe('createEventSource edges', () => {
  let fetchMock;
  let scripted;

  beforeEach(() => {
    vi.useFakeTimers();
    scripted = [];
    fetchMock = vi.fn((requestUrl, requestInit) => {
      const next = scripted.shift();
      if (next) {
        next.attach?.(requestInit.signal);
        return Promise.resolve(next.response);
      }
      return new Promise((resolve, reject) => {
        requestInit.signal.addEventListener('abort', () => reject(new DOMException('aborted', 'AbortError')));
      });
    });
    vi.stubGlobal('fetch', fetchMock);
    vi.stubGlobal('localStorage', { getItem: vi.fn(() => 'test-token') });
  });

  afterEach(() => {
    for (const close of openConnections.splice(0)) close();
    delete document.visibilityState;
    vi.useRealTimers();
    vi.unstubAllGlobals();
  });

  // A response the test hands out itself, with a reader whose reads it settles by hand.
  function manualResponse() {
    const resolvers = [];
    const reader = {
      read: vi.fn(() => new Promise((resolve) => resolvers.push(resolve))),
      cancel: vi.fn(() => Promise.resolve()),
    };
    const body = { getReader: vi.fn(() => reader), cancel: vi.fn(() => Promise.resolve()) };
    const response = {
      ok: true,
      status: 200,
      headers: { get: () => 'text/event-stream' },
      body,
    };
    return { response, reader, body, resolvers };
  }

  describe('closeOn guards', () => {
    it('does not treat any event name as a close trigger when closeOn is not given', async () => {
      const handler = vi.fn();
      const stream = openStream();
      scripted.push(stream);
      createEventSource('/api/test', {}, { 'Stryker was here': handler });
      await settle();
      stream.push('event: Stryker was here\ndata: {"n":1}\n\nevent: Stryker was here\ndata: {"n":2}\n\n');
      await settle();
      expect(handler).toHaveBeenNthCalledWith(1, { n: 1 });
      expect(handler).toHaveBeenNthCalledWith(2, { n: 2 });
      expect(fetchMock).toHaveBeenCalledOnce();
    });

    it('does not close on a closeOn event that carries no data, and keeps delivering', async () => {
      const done = vi.fn();
      const other = vi.fn();
      const stream = openStream();
      scripted.push(stream);
      createEventSource('/api/test', {}, { done, other }, { closeOn: 'done' });
      await settle();
      stream.push('event: done\n\nevent: other\ndata: {"n":1}\n\n');
      await settle();
      expect(done).not.toHaveBeenCalled();
      expect(other).toHaveBeenCalledExactlyOnceWith({ n: 1 });
    });

    it('does not close on a closeOn event that has no handler, and keeps delivering', async () => {
      const other = vi.fn();
      const stream = openStream();
      scripted.push(stream);
      createEventSource('/api/test', {}, { other }, { closeOn: 'done' });
      await settle();
      stream.push('event: done\ndata: {"x":1}\n\nevent: other\ndata: {"n":1}\n\n');
      await settle();
      expect(other).toHaveBeenCalledExactlyOnceWith({ n: 1 });
    });

    it('closes on a closeOn event whose data is a bare "data" line with no colon', async () => {
      const done = vi.fn();
      const other = vi.fn();
      const stream = openStream();
      scripted.push(stream);
      createEventSource('/api/test', {}, { done, other }, { closeOn: 'done' });
      await settle();
      // `data` with no colon is a data field with an empty value: the event has a (blank) payload,
      // which fails to parse, so the handler is skipped but the stream still closes.
      stream.push('event: done\ndata\n\nevent: other\ndata: {"n":1}\n\n');
      await settle();
      expect(done).not.toHaveBeenCalled();
      expect(other).not.toHaveBeenCalled();
      await vi.advanceTimersByTimeAsync(10000);
      expect(fetchMock).toHaveBeenCalledOnce();
    });
  });

  describe('line parsing', () => {
    it('keeps the event name across fields that are neither event nor data', async () => {
      const foo = vi.fn();
      const message = vi.fn();
      const stream = openStream();
      scripted.push(stream);
      createEventSource('/api/test', {}, { foo, message });
      await settle();
      stream.push('event: foo\nid: 7\nretry: 100\nbogus: x\ndata: {"a":1}\n\n');
      await settle();
      expect(foo).toHaveBeenCalledExactlyOnceWith({ a: 1 });
      expect(message).not.toHaveBeenCalled();
    });

    it('resets the event name when an `event` line has no colon', async () => {
      const foo = vi.fn();
      const message = vi.fn();
      const stream = openStream();
      scripted.push(stream);
      createEventSource('/api/test', {}, { foo, message });
      await settle();
      stream.push('event: foo\nevent\ndata: {"a":1}\n\n');
      await settle();
      expect(foo).not.toHaveBeenCalled();
      expect(message).toHaveBeenCalledExactlyOnceWith({ a: 1 });
    });

    it('does not mistake a no-colon `data` line for a truncated field name', async () => {
      const handler = vi.fn();
      const stream = openStream();
      scripted.push(stream);
      createEventSource('/api/test', {}, { greet: handler });
      await settle();
      // The empty data line contributes a line feed between the two values.
      stream.push('event: greet\ndata: [1,\ndata\ndata: 2]\n\n');
      await settle();
      expect(handler).toHaveBeenCalledExactlyOnceWith([1, 2]);
    });

    it('keeps a lone CR that ends a chunk from swallowing the next chunk\'s line feed wrongly', async () => {
      const handler = vi.fn();
      const stream = openStream();
      scripted.push(stream);
      createEventSource('/api/test', {}, { a: handler });
      await settle();
      stream.push('event: a\r');
      await settle();
      stream.push('\ndata: {"n":1}\r\n\r\n');
      await settle();
      expect(handler).toHaveBeenCalledExactlyOnceWith({ n: 1 });
    });

    it('treats a lone CR followed by text as a complete line break, not the start of CRLF', async () => {
      const handler = vi.fn();
      const stream = openStream();
      scripted.push(stream);
      createEventSource('/api/test', {}, { a: handler });
      await settle();
      stream.push('event: a\rdata: {"n":1}\n\n');
      await settle();
      expect(handler).toHaveBeenCalledExactlyOnceWith({ n: 1 });
    });
  });

  describe('after cleanup', () => {
    it('does not open a reader when the response arrives after cleanup', async () => {
      const { response, body } = manualResponse();
      let release;
      fetchMock.mockImplementationOnce(() => new Promise((resolve) => {
        release = () => resolve(response);
      }));
      const onError = vi.fn();
      const cleanup = createEventSource('/api/test', {}, {}, { onError });
      cleanup();
      release();
      await settle();
      expect(body.getReader).not.toHaveBeenCalled();
      expect(onError).not.toHaveBeenCalled();
    });

    it('does not read again when a read settles after cleanup', async () => {
      const handler = vi.fn();
      const { response, reader, resolvers } = manualResponse();
      fetchMock.mockImplementationOnce(() => Promise.resolve(response));
      const cleanup = createEventSource('/api/test', {}, { a: handler });
      await settle();
      expect(reader.read).toHaveBeenCalledOnce();

      cleanup();
      resolvers[0]({ done: false, value: encoder.encode('event: a\ndata: {"n":1}\n\n') });
      await settle();

      expect(reader.read).toHaveBeenCalledOnce();
      expect(handler).not.toHaveBeenCalled();
    });
  });

  describe('a response with no headers', () => {
    it('is reported as a failure with its status, cancels the body, and retries', async () => {
      const onError = vi.fn();
      const cancel = vi.fn(() => Promise.resolve());
      fetchMock.mockImplementationOnce(() => Promise.resolve({
        ok: true,
        status: 200,
        body: { cancel, getReader: vi.fn() },
      }));
      createEventSource('/api/test', {}, {}, { onError });
      await settle();
      expect(onError).toHaveBeenCalledExactlyOnceWith({ status: 200 });
      expect(cancel).toHaveBeenCalledOnce();
      await vi.advanceTimersByTimeAsync(5000);
      expect(fetchMock).toHaveBeenCalledTimes(2);
    });
  });

  describe('reconnectOnVisible', () => {
    it('does not reconnect while the tab is hidden, on visibilitychange or focus', async () => {
      scripted.push({ response: new Response('{}', { status: 500, headers: { 'Content-Type': 'application/json' } }) });
      createEventSource('/api/test', {}, {}, { reconnectOnVisible: true });
      await settle();
      Object.defineProperty(document, 'visibilityState', { configurable: true, get: () => 'hidden' });

      document.dispatchEvent(new Event('visibilitychange'));
      window.dispatchEvent(new Event('focus'));

      expect(fetchMock).toHaveBeenCalledOnce();
      // The throttled retry is still the way back.
      await vi.advanceTimersByTimeAsync(5000);
      expect(fetchMock).toHaveBeenCalledTimes(2);
    });

    it('removes exactly the listeners it added, from the targets it added them to', async () => {
      const docAdd = vi.spyOn(document, 'addEventListener');
      const winAdd = vi.spyOn(window, 'addEventListener');
      const docRemove = vi.spyOn(document, 'removeEventListener');
      const winRemove = vi.spyOn(window, 'removeEventListener');
      try {
        const cleanup = createEventSource('/api/test', {}, {}, { reconnectOnVisible: true });
        const visible = docAdd.mock.calls.find(([type]) => type === 'visibilitychange')[1];
        const focus = winAdd.mock.calls.find(([type]) => type === 'focus')[1];
        expect(docRemove.mock.calls.filter(([type]) => type === 'visibilitychange')).toEqual([]);

        cleanup();

        expect(docRemove.mock.calls.filter(([type]) => type === 'visibilitychange'))
          .toEqual([['visibilitychange', visible]]);
        expect(winRemove.mock.calls.filter(([type]) => type === 'focus')).toEqual([['focus', focus]]);
      } finally {
        docAdd.mockRestore();
        winAdd.mockRestore();
        docRemove.mockRestore();
        winRemove.mockRestore();
      }
    });

    it('removes no listeners on cleanup when the option is off', () => {
      const docRemove = vi.spyOn(document, 'removeEventListener');
      const winRemove = vi.spyOn(window, 'removeEventListener');
      try {
        const cleanup = createEventSource('/api/test');
        cleanup();
        expect(docRemove.mock.calls.filter(([type]) => type === 'visibilitychange')).toEqual([]);
        expect(winRemove.mock.calls.filter(([type]) => type === 'focus')).toEqual([]);
      } finally {
        docRemove.mockRestore();
        winRemove.mockRestore();
      }
    });
  });
});
