import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import createEventSourceUntracked from '../utils/createEventSource.js';

const encoder = new TextEncoder();

// One scripted server response. `push` and `end` feed the body the way a socket would, so a test
// controls exactly where the chunk boundaries fall.
function openStream({ status = 200, contentType = 'text/event-stream; charset=utf-8' } = {}) {
  let controller;
  const body = new ReadableStream({
    start(c) {
      controller = c;
    },
  });
  const response = new Response(status === 204 ? null : body, {
    status,
    headers: contentType ? { 'Content-Type': contentType } : {},
  });
  return {
    response,
    // Browsers error the body with an AbortError when the request's signal aborts.
    attach: (signal) => {
      signal.addEventListener('abort', () => {
        try {
          controller.error(new DOMException('aborted', 'AbortError'));
        } catch {
          // already closed
        }
      });
    },
    push: (chunk) => controller.enqueue(typeof chunk === 'string' ? encoder.encode(chunk) : chunk),
    end: () => controller.close(),
    fail: (error) => controller.error(error),
  };
}

function failure(status, contentType = 'application/json') {
  return { response: new Response('{}', { status, headers: { 'Content-Type': contentType } }) };
}

// Lets the awaits inside the connection (fetch, each read) run to completion on the fake clock.
async function settle() {
  await vi.advanceTimersByTimeAsync(0);
}

// Every test opens its connections through this wrapper so `afterEach` can close them. The cleanup
// `createEventSource` returns is the only handle on a connection's retry timer and its visibility
// listeners. Calling the import directly opens a connection nothing will close.
const openConnections = [];

function createEventSource(...args) {
  const close = createEventSourceUntracked(...args);
  openConnections.push(close);
  return close;
}

describe('createEventSource', () => {
  let fetchMock;
  let scripted;

  // Queue the responses the next fetches return, in order. A fetch beyond the queue hangs open.
  function respondWith(...responses) {
    scripted.push(...responses);
  }

  const url = (n = 0) => fetchMock.mock.calls[n][0];
  const init = (n = 0) => fetchMock.mock.calls[n][1];

  beforeEach(() => {
    // The whole file runs on the fake clock, so no retry a test arms can fire once the test is
    // over (a real 5 s retry once outlived `afterEach` and failed a whole run with every test
    // passing).
    vi.useFakeTimers();
    scripted = [];
    fetchMock = vi.fn((requestUrl, requestInit) => {
      const next = scripted.shift();
      if (next) {
        next.attach?.(requestInit.signal);
        return Promise.resolve(next.response);
      }
      // Unscripted: a connection that never answers, until aborted.
      return new Promise((resolve, reject) => {
        requestInit.signal.addEventListener('abort', () => reject(new DOMException('aborted', 'AbortError')));
      });
    });
    vi.stubGlobal('fetch', fetchMock);
    vi.stubGlobal('localStorage', { getItem: vi.fn(() => 'test-token') });
  });

  afterEach(() => {
    // Close every connection the test opened, whether it passed or threw.
    for (const close of openConnections.splice(0)) close();
    // Then check nothing was missed. A cleanup aborts its connection and a reconnect aborts the one
    // it replaces, so a signal still live belongs to a connection nothing released; a timer still
    // pending is a retry nothing cancelled. Each check sees a leak the other cannot.
    const leftOpen = fetchMock.mock.calls.filter(([, requestInit]) => !requestInit.signal.aborted);
    const leftArmed = vi.getTimerCount();
    vi.useRealTimers();
    vi.unstubAllGlobals();
    expect(leftOpen.map(([requestUrl]) => requestUrl), 'connections left open').toEqual([]);
    expect(leftArmed, 'retries left armed').toBe(0);
  });

  describe('the request', () => {
    it('requests the path with its params and never puts the token in the URL', () => {
      createEventSource('/api/test', { foo: 'bar' });
      expect(url()).toBe('/api/test?foo=bar');
      expect(url()).not.toContain('token');
    });

    it('sends no query string at all when there are no params', () => {
      createEventSource('/api/test');
      expect(url()).toBe('/api/test');
    });

    it('sends the token as a bearer header, asks for an event stream, and bypasses the cache', () => {
      createEventSource('/api/test');
      expect(init().headers).toEqual({ Accept: 'text/event-stream', Authorization: 'Bearer test-token' });
      expect(init().cache).toBe('no-store');
      expect(init().signal).toBeInstanceOf(AbortSignal);
    });

    it('uses custom getToken', () => {
      createEventSource('/api/test', {}, {}, { getToken: () => 'custom-tok' });
      expect(init().headers.Authorization).toBe('Bearer custom-tok');
    });

    it('omits the Authorization header when there is no token', () => {
      createEventSource('/api/test', {}, {}, { getToken: () => null });
      expect(init().headers).toEqual({ Accept: 'text/event-stream' });
    });

    it('reads a freshly-provided token on reconnect, not a stale captured one', async () => {
      let current = 'tok-1';
      respondWith(failure(500));
      createEventSource('/api/test', {}, {}, { getToken: () => current });
      expect(init(0).headers.Authorization).toBe('Bearer tok-1');
      await settle();

      current = 'tok-2'; // token refreshed (e.g. by the axios interceptor) between attempts
      await vi.advanceTimersByTimeAsync(5000);

      expect(fetchMock).toHaveBeenCalledTimes(2);
      expect(init(1).headers.Authorization).toBe('Bearer tok-2');
      expect(url(1)).not.toContain('token');
    });
  });

  describe('events', () => {
    it('calls the handler registered for the event name with the JSON-parsed data', async () => {
      const handler = vi.fn();
      const stream = openStream();
      respondWith(stream);
      createEventSource('/api/test', {}, { 'my-event': handler });
      await settle();
      stream.push('event: my-event\ndata: {"value":42}\n\n');
      await settle();
      expect(handler).toHaveBeenCalledExactlyOnceWith({ value: 42 });
    });

    it('routes each event to its own handler', async () => {
      const a = vi.fn();
      const b = vi.fn();
      const stream = openStream();
      respondWith(stream);
      createEventSource('/api/test', {}, { a, b });
      await settle();
      stream.push('event: b\ndata: {"n":2}\n\nevent: a\ndata: {"n":1}\n\n');
      await settle();
      expect(a).toHaveBeenCalledExactlyOnceWith({ n: 1 });
      expect(b).toHaveBeenCalledExactlyOnceWith({ n: 2 });
    });

    it('dispatches an event with no name to the "message" handler', async () => {
      const handler = vi.fn();
      const stream = openStream();
      respondWith(stream);
      createEventSource('/api/test', {}, { message: handler });
      await settle();
      stream.push('data: {"a":1}\n\n');
      await settle();
      expect(handler).toHaveBeenCalledExactlyOnceWith({ a: 1 });
    });

    it('does not carry an event name over to the next event', async () => {
      const named = vi.fn();
      const unnamed = vi.fn();
      const stream = openStream();
      respondWith(stream);
      createEventSource('/api/test', {}, { named, message: unnamed });
      await settle();
      stream.push('event: named\ndata: {"n":1}\n\ndata: {"n":2}\n\n');
      await settle();
      expect(named).toHaveBeenCalledExactlyOnceWith({ n: 1 });
      expect(unnamed).toHaveBeenCalledExactlyOnceWith({ n: 2 });
    });

    it('ignores JSON parse errors and keeps reading', async () => {
      const handler = vi.fn();
      const stream = openStream();
      respondWith(stream);
      createEventSource('/api/test', {}, { update: handler });
      await settle();
      stream.push('event: update\ndata: not-json\n\nevent: update\ndata: {"ok":true}\n\n');
      await settle();
      expect(handler).toHaveBeenCalledExactlyOnceWith({ ok: true });
    });

    it('ignores an event whose name has no handler', async () => {
      const handler = vi.fn();
      const stream = openStream();
      respondWith(stream);
      createEventSource('/api/test', {}, { known: handler });
      await settle();
      stream.push('event: stranger\ndata: {"x":1}\n\n');
      await settle();
      expect(handler).not.toHaveBeenCalled();
    });

    it('skips a handler that is not a function rather than throwing', async () => {
      const stream = openStream();
      respondWith(stream);
      expect(() => createEventSource('/api/test', {}, { later: undefined })).not.toThrow();
      await settle();
      stream.push('event: later\ndata: {}\n\n');
      await settle();
    });

    it('does not dispatch an event until its blank line arrives', async () => {
      const handler = vi.fn();
      const stream = openStream();
      respondWith(stream);
      createEventSource('/api/test', {}, { update: handler });
      await settle();
      stream.push('event: update\ndata: {"a":1}\n');
      await settle();
      expect(handler).not.toHaveBeenCalled();
      stream.push('\n');
      await settle();
      expect(handler).toHaveBeenCalledOnce();
    });

    it('joins several data lines with a line feed', async () => {
      const handler = vi.fn();
      const stream = openStream();
      respondWith(stream);
      createEventSource('/api/test', {}, { update: handler });
      await settle();
      stream.push('event: update\ndata: {"a":\ndata: 1}\n\n');
      await settle();
      expect(handler).toHaveBeenCalledExactlyOnceWith({ a: 1 });
    });

    it('ignores comment lines, id and retry fields', async () => {
      const handler = vi.fn();
      const stream = openStream();
      respondWith(stream);
      createEventSource('/api/test', {}, { update: handler });
      await settle();
      stream.push(': heartbeat\nid: 7\nretry: 100\nevent: update\n: another\ndata: {"a":1}\n\n');
      await settle();
      expect(handler).toHaveBeenCalledExactlyOnceWith({ a: 1 });
      // A comment on its own is not an event.
      stream.push(': ping\n\n');
      await settle();
      expect(handler).toHaveBeenCalledOnce();
    });

    it('strips exactly one leading space from a field value', async () => {
      const handler = vi.fn();
      const stream = openStream();
      respondWith(stream);
      createEventSource('/api/test', {}, { update: handler });
      await settle();
      // No space, one space, and two spaces (one survives, as JSON whitespace).
      stream.push('event:update\ndata:{"a":\ndata:  1}\n\n');
      await settle();
      expect(handler).toHaveBeenCalledExactlyOnceWith({ a: 1 });
    });

    it('keeps the remaining spaces when more than one leads the value', async () => {
      const handler = vi.fn();
      const stream = openStream();
      respondWith(stream);
      createEventSource('/api/test', {}, { update: handler });
      await settle();
      stream.push('event: update\ndata:   "x"\n\n');
      await settle();
      expect(handler).toHaveBeenCalledExactlyOnceWith('x');
    });

    it('treats a line with no colon as a field with an empty value', async () => {
      const handler = vi.fn();
      const stream = openStream();
      respondWith(stream);
      createEventSource('/api/test', {}, { update: handler });
      await settle();
      // `data` alone appends an empty line to the data buffer, so the JSON is "\n{...}".
      stream.push('event: update\ndata\ndata: {"a":1}\n\n');
      await settle();
      expect(handler).toHaveBeenCalledExactlyOnceWith({ a: 1 });
    });

    it('splits lines on CRLF and on a bare CR', async () => {
      const handler = vi.fn();
      const stream = openStream();
      respondWith(stream);
      createEventSource('/api/test', {}, { update: handler });
      await settle();
      stream.push('event: update\r\ndata: {"a":1}\r\n\r\nevent: update\rdata: {"a":2}\r\r');
      await settle();
      expect(handler.mock.calls).toEqual([[{ a: 1 }], [{ a: 2 }]]);
    });

    it('reassembles an event split across chunk boundaries', async () => {
      const handler = vi.fn();
      const stream = openStream();
      respondWith(stream);
      createEventSource('/api/test', {}, { update: handler });
      await settle();
      for (const piece of ['eve', 'nt: upd', 'ate\nda', 'ta: {"a"', ':1}\n', '\n']) {
        stream.push(piece);
        await settle();
      }
      expect(handler).toHaveBeenCalledExactlyOnceWith({ a: 1 });
    });

    it('treats a CRLF split across two chunks as one line break', async () => {
      const handler = vi.fn();
      const stream = openStream();
      respondWith(stream);
      createEventSource('/api/test', {}, { update: handler });
      await settle();
      stream.push('event: update\r\ndata: {"a":1}\r');
      await settle();
      stream.push('\n\r');
      await settle();
      stream.push('\n');
      await settle();
      // One event: had the split CRLF been two breaks, the stray blank line would have dispatched
      // early or an empty event would have followed.
      expect(handler).toHaveBeenCalledExactlyOnceWith({ a: 1 });
    });

    it('decodes a multibyte UTF-8 character split across two chunks', async () => {
      const handler = vi.fn();
      const stream = openStream();
      respondWith(stream);
      createEventSource('/api/test', {}, { update: handler });
      await settle();
      const bytes = encoder.encode('event: update\ndata: {"name":"Whitley Bay ☀️ café"}\n\n');
      const sun = bytes.indexOf(0xe2); // first byte of the three-byte sun
      stream.push(bytes.slice(0, sun + 1));
      await settle();
      stream.push(bytes.slice(sun + 1, sun + 2));
      await settle();
      stream.push(bytes.slice(sun + 2));
      await settle();
      expect(handler).toHaveBeenCalledExactlyOnceWith({ name: 'Whitley Bay ☀️ café' });
    });

    it('keeps reading after a handler throws a non-parse error', async () => {
      const later = vi.fn();
      const stream = openStream();
      respondWith(stream);
      createEventSource('/api/test', {}, {
        boom: () => {
          throw new Error('handler bug');
        },
        later,
      });
      await settle();
      stream.push('event: boom\ndata: {}\n\nevent: later\ndata: {"n":1}\n\n');
      await settle();
      expect(later).toHaveBeenCalledExactlyOnceWith({ n: 1 });
    });

    it('strips a leading byte order mark so the first field still parses', async () => {
      const handler = vi.fn();
      const stream = openStream();
      respondWith(stream);
      createEventSource('/api/test', {}, { update: handler });
      await settle();
      stream.push('\uFEFFevent: update\ndata: {"a":1}\n\n');
      await settle();
      expect(handler).toHaveBeenCalledExactlyOnceWith({ a: 1 });
    });

    it('discards an incomplete final event when the stream ends, then reports the end', async () => {
      const handler = vi.fn();
      const onError = vi.fn();
      const stream = openStream();
      respondWith(stream);
      createEventSource('/api/test', {}, { update: handler }, { onError });
      await settle();
      stream.push('event: update\ndata: {"a":1}\n');
      stream.end();
      await settle();
      expect(handler).not.toHaveBeenCalled();
      expect(onError).toHaveBeenCalledOnce();
    });
  });

  describe('closeOn', () => {
    it('closes on the closeOn event after its handler fires, and does not reconnect', async () => {
      const handler = vi.fn();
      const onError = vi.fn();
      const stream = openStream();
      respondWith(stream);
      createEventSource('/api/test', {}, { done: handler }, { closeOn: 'done', onError });
      await settle();
      stream.push('event: done\ndata: {"ok":true}\n\n');
      await settle();

      expect(handler).toHaveBeenCalledExactlyOnceWith({ ok: true });
      expect(init().signal.aborted).toBe(true);
      await vi.advanceTimersByTimeAsync(20000);
      expect(fetchMock).toHaveBeenCalledOnce();
      expect(onError).not.toHaveBeenCalled();
      expect(vi.getTimerCount()).toBe(0);
    });

    it('still closes, without reconnecting, when the closeOn handler throws', async () => {
      const onError = vi.fn();
      const stream = openStream();
      respondWith(stream);
      createEventSource('/api/test', {}, {
        done: () => {
          throw new Error('handler bug');
        },
      }, { closeOn: 'done', onError });
      await settle();
      stream.push('event: done\ndata: {}\n\n');
      await settle();
      await vi.advanceTimersByTimeAsync(20000);
      expect(init().signal.aborted).toBe(true);
      expect(fetchMock).toHaveBeenCalledOnce();
      expect(onError).not.toHaveBeenCalled();
    });

    it('closes on any of several closeOn events, and only those', async () => {
      const done = vi.fn();
      const gone = vi.fn();
      const other = vi.fn();
      const stream = openStream();
      respondWith(stream);
      createEventSource('/api/test', {}, { done, gone, other }, { closeOn: ['done', 'gone'] });
      await settle();
      stream.push('event: other\ndata: {}\n\n');
      await settle();
      expect(init().signal.aborted).toBe(false);
      stream.push('event: gone\ndata: {"expired":true}\n\n');
      await settle();
      expect(gone).toHaveBeenCalledExactlyOnceWith({ expired: true });
      expect(init().signal.aborted).toBe(true);
    });

    it('does not dispatch the rest of the chunk once a closeOn event has fired', async () => {
      const done = vi.fn();
      const after = vi.fn();
      const stream = openStream();
      respondWith(stream);
      createEventSource('/api/test', {}, { done, after }, { closeOn: 'done' });
      await settle();
      stream.push('event: done\ndata: {"n":1}\n\nevent: after\ndata: {"n":2}\n\n');
      await settle();
      expect(done).toHaveBeenCalledOnce();
      expect(after).not.toHaveBeenCalled();
    });
  });

  describe('failure and retry', () => {
    it('reports a 401 with its status and retries after 5 s', async () => {
      const onError = vi.fn();
      respondWith(failure(401));
      createEventSource('/api/test', {}, {}, { onError });
      await settle();

      expect(onError).toHaveBeenCalledExactlyOnceWith({ status: 401 });
      expect(fetchMock).toHaveBeenCalledOnce();
      await vi.advanceTimersByTimeAsync(4999);
      expect(fetchMock).toHaveBeenCalledOnce();
      await vi.advanceTimersByTimeAsync(1);
      expect(fetchMock).toHaveBeenCalledTimes(2);
    });

    it('treats a 2xx that is not an event stream as a failure and retries', async () => {
      const onError = vi.fn();
      const handler = vi.fn();
      // The body even parses as an event: the content type alone must be what rejects it.
      const body = 'event: update\ndata: {"a":1}\n\n';
      respondWith({ response: new Response(body, { status: 200, headers: { 'Content-Type': 'text/html' } }) });
      createEventSource('/api/test', {}, { update: handler }, { onError });
      await settle();

      expect(handler).not.toHaveBeenCalled();
      expect(onError).toHaveBeenCalledExactlyOnceWith({ status: 200 });
      await vi.advanceTimersByTimeAsync(5000);
      expect(fetchMock).toHaveBeenCalledTimes(2);
    });

    it('treats a 204 as a failure and retries, as the wrapper always did', async () => {
      const onError = vi.fn();
      respondWith({ response: new Response(null, { status: 204 }) });
      createEventSource('/api/test', {}, {}, { onError });
      await settle();

      expect(onError).toHaveBeenCalledExactlyOnceWith({ status: 204 });
      await vi.advanceTimersByTimeAsync(5000);
      expect(fetchMock).toHaveBeenCalledTimes(2);
    });

    it('still retries when onError throws', async () => {
      const onError = vi.fn(() => {
        throw new Error('handler bug');
      });
      respondWith(failure(500));
      createEventSource('/api/test', {}, {}, { onError });
      await settle();

      expect(onError).toHaveBeenCalledOnce();
      await vi.advanceTimersByTimeAsync(5000);
      expect(fetchMock).toHaveBeenCalledTimes(2);
    });

    it('reports the end of the body and retries after 5 s', async () => {
      const onError = vi.fn();
      const stream = openStream();
      respondWith(stream);
      createEventSource('/api/test', {}, {}, { onError });
      await settle();
      stream.end();
      await settle();

      expect(onError).toHaveBeenCalledExactlyOnceWith({ status: 200 });
      expect(fetchMock).toHaveBeenCalledOnce();
      await vi.advanceTimersByTimeAsync(5000);
      expect(fetchMock).toHaveBeenCalledTimes(2);
    });

    it('reports a body that errors mid-stream and retries', async () => {
      const onError = vi.fn();
      const stream = openStream();
      respondWith(stream);
      createEventSource('/api/test', {}, {}, { onError });
      await settle();
      stream.fail(new TypeError('network error'));
      await settle();

      expect(onError).toHaveBeenCalledOnce();
      await vi.advanceTimersByTimeAsync(5000);
      expect(fetchMock).toHaveBeenCalledTimes(2);
    });

    it('reports a fetch rejection with no status and retries', async () => {
      const onError = vi.fn();
      fetchMock.mockRejectedValueOnce(new TypeError('Failed to fetch'));
      createEventSource('/api/test', {}, {}, { onError });
      await settle();

      expect(onError).toHaveBeenCalledExactlyOnceWith(undefined);
      await vi.advanceTimersByTimeAsync(5000);
      expect(fetchMock).toHaveBeenCalledTimes(2);
    });

    it('calls onError on every failure, not just the first', async () => {
      const onError = vi.fn();
      respondWith(failure(503), failure(503));
      createEventSource('/api/test', {}, {}, { onError });
      await settle();
      await vi.advanceTimersByTimeAsync(5000);
      expect(onError).toHaveBeenCalledTimes(2);
    });

    it('does not reconnect, or call onError, after cleanup', async () => {
      const onError = vi.fn();
      const stream = openStream();
      respondWith(stream);
      const cleanup = createEventSource('/api/test', {}, {}, { onError });
      await settle();

      cleanup();
      expect(init().signal.aborted).toBe(true);
      stream.fail(new DOMException('aborted', 'AbortError'));
      await vi.advanceTimersByTimeAsync(10000);
      expect(onError).not.toHaveBeenCalled();
      expect(fetchMock).toHaveBeenCalledOnce();
    });

    it('does not call onError when cleanup aborts a request still waiting for its response', async () => {
      const onError = vi.fn();
      const cleanup = createEventSource('/api/test', {}, {}, { onError });
      cleanup();
      await vi.advanceTimersByTimeAsync(10000);
      expect(onError).not.toHaveBeenCalled();
      expect(fetchMock).toHaveBeenCalledOnce();
    });

    it('cancels a pending retry when cleanup is called', async () => {
      respondWith(failure(500));
      const cleanup = createEventSource('/api/test');
      await settle();
      expect(vi.getTimerCount()).toBe(1); // the 5 s retry

      cleanup();

      // Counted, not advanced: `closed` alone stops a retry that fires from reconnecting, so the
      // pending-timer count is what tells a cancelled retry from one left armed as a no-op.
      expect(vi.getTimerCount()).toBe(0);
    });

    it('aborts the previous connection when reconnecting', async () => {
      respondWith(failure(500));
      createEventSource('/api/test');
      await settle();
      const first = init(0).signal;
      await vi.advanceTimersByTimeAsync(5000);

      expect(first.aborted).toBe(true);
      expect(fetchMock).toHaveBeenCalledTimes(2);
      expect(init(1).signal.aborted).toBe(false);
    });
  });

  describe('a superseded connection', () => {
    it('reports the first connection once and dispatches only from its replacement', async () => {
      const handler = vi.fn();
      const onError = vi.fn();
      const first = openStream();
      const second = openStream();
      respondWith(first, second);
      createEventSource('/api/test', {}, { update: handler }, { onError, reconnectOnVisible: true });
      await settle();

      // The first body ends, so the stream is CLOSED and the visible-tab reconnect opens the second
      // connection at once.
      first.end();
      await settle();
      expect(onError).toHaveBeenCalledOnce();
      document.dispatchEvent(new Event('visibilitychange'));
      await settle();
      expect(fetchMock).toHaveBeenCalledTimes(2);

      second.push('event: update\ndata: {"fresh":true}\n\n');
      await settle();
      expect(handler).toHaveBeenCalledExactlyOnceWith({ fresh: true });
      expect(onError).toHaveBeenCalledOnce();
    });

    it('does not dispatch, report an error or retry when an aborted stream\'s body errors with AbortError', async () => {
      const handler = vi.fn();
      const onError = vi.fn();
      const first = openStream();
      respondWith(first);
      const cleanup = createEventSource('/api/test', {}, { update: handler }, { onError });
      await settle();
      // An event that is half-received when the connection is closed.
      first.push('event: update\ndata: {"a":1}\n');
      await settle();
      cleanup(); // aborts: the mock errors the body with an AbortError, as a browser does
      await vi.advanceTimersByTimeAsync(10000);
      expect(handler).not.toHaveBeenCalled();
      expect(onError).not.toHaveBeenCalled();
      expect(vi.getTimerCount()).toBe(0);
      expect(fetchMock).toHaveBeenCalledOnce();
    });

    it('a handler that calls cleanup mid-chunk stops dispatch, error and retry', async () => {
      const second = vi.fn();
      const onError = vi.fn();
      const stream = openStream();
      respondWith(stream);
      let cleanup;
      cleanup = createEventSource('/api/test', {}, {
        first: () => cleanup(),
        second,
      }, { onError });
      await settle();
      stream.push('event: first\ndata: {}\n\nevent: second\ndata: {}\n\n');
      await settle();
      await vi.advanceTimersByTimeAsync(10000);
      expect(second).not.toHaveBeenCalled();
      expect(onError).not.toHaveBeenCalled();
      expect(fetchMock).toHaveBeenCalledOnce();
    });
  });

  describe('reconnectOnVisible', () => {
    it('reconnects immediately when the tab becomes visible and the stream is CLOSED', async () => {
      respondWith(failure(500));
      createEventSource('/api/test', {}, {}, { reconnectOnVisible: true });
      await settle();

      document.dispatchEvent(new Event('visibilitychange'));

      expect(fetchMock).toHaveBeenCalledTimes(2);
    });

    it('reconnects on window focus when the stream is CLOSED', async () => {
      respondWith(failure(500));
      createEventSource('/api/test', {}, {}, { reconnectOnVisible: true });
      await settle();

      window.dispatchEvent(new Event('focus'));

      expect(fetchMock).toHaveBeenCalledTimes(2);
    });

    it('opens one stream when visibilitychange and focus arrive together', async () => {
      respondWith(failure(500));
      createEventSource('/api/test', {}, {}, { reconnectOnVisible: true });
      await settle();

      document.dispatchEvent(new Event('visibilitychange'));
      window.dispatchEvent(new Event('focus'));

      expect(fetchMock).toHaveBeenCalledTimes(2);
    });

    it('does not reconnect while the stream is still CONNECTING', () => {
      createEventSource('/api/test', {}, {}, { reconnectOnVisible: true });

      document.dispatchEvent(new Event('visibilitychange'));

      expect(fetchMock).toHaveBeenCalledOnce();
    });

    it('does not reconnect while the stream is OPEN', async () => {
      const stream = openStream();
      respondWith(stream);
      createEventSource('/api/test', {}, {}, { reconnectOnVisible: true });
      await settle();

      document.dispatchEvent(new Event('visibilitychange'));
      window.dispatchEvent(new Event('focus'));

      expect(fetchMock).toHaveBeenCalledOnce();
    });

    it('cancels the pending throttled retry and reconnects at once on visibility', async () => {
      respondWith(failure(500));
      createEventSource('/api/test', {}, {}, { reconnectOnVisible: true });
      await settle(); // schedules the 5 s retry

      document.dispatchEvent(new Event('visibilitychange'));
      expect(fetchMock).toHaveBeenCalledTimes(2);

      // The previously scheduled retry must not fire a second reconnect.
      await vi.advanceTimersByTimeAsync(5000);
      expect(fetchMock).toHaveBeenCalledTimes(2);
    });

    it('removes visibility/focus listeners on cleanup', async () => {
      respondWith(failure(500));
      const cleanup = createEventSource('/api/test', {}, {}, { reconnectOnVisible: true });
      await settle();
      cleanup();

      document.dispatchEvent(new Event('visibilitychange'));
      window.dispatchEvent(new Event('focus'));

      expect(fetchMock).toHaveBeenCalledOnce();
    });

    it('does not register visibility listeners when the option is off', async () => {
      respondWith(failure(500));
      createEventSource('/api/test');
      await settle();

      document.dispatchEvent(new Event('visibilitychange'));

      expect(fetchMock).toHaveBeenCalledOnce();
    });
  });
});
