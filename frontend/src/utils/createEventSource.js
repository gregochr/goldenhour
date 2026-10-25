const BASE_URL = import.meta.env.VITE_API_BASE_URL || '';
const TOKEN_KEY = 'goldenhour_token';

// The connection states EventSource reports as `readyState`, kept so `handleVisible` can ask the
// same question it always did: is this stream dead?
const CONNECTING = 0;
const OPEN = 1;
const CLOSED = 2;

const RECONNECT_DELAY = 5000;
const NO_CONTENT = 204;
const EVENT_STREAM = 'text/event-stream';

/**
 * Creates a server-sent-event connection with standardised bearer-token auth, JSON parsing,
 * error handling, and optional auto-close.
 *
 * The stream is read with `fetch`, not `EventSource`, so the access token travels in an
 * `Authorization: Bearer` header and never in the URL (a query string is written to web server logs
 * and to a Referer). It also means a 401 is visible: `onError` receives `{ status }` whenever the
 * server answered. The parser follows the WHATWG event-stream interpretation; `id` and `retry`
 * fields are ignored because the retry below is ours.
 *
 * @param {string} path - API path (e.g. '/api/status/stream')
 * @param {Object} params - Query params. The token is never one: it goes in the header.
 * @param {Object} eventHandlers - { 'event-name': (parsedData) => void }
 * @param {Object} [options]
 * @param {Function} [options.onError] - Called on every connection failure (non-2xx, a response that
 *   is not an event stream, the body ending, a network error) with `{ status }` when the server
 *   answered, otherwise with no argument. Never called for a deliberate close.
 * @param {string|string[]} [options.closeOn] - Event name (or names) that close the connection,
 *   without reconnecting, after the handler fires
 * @param {Function} [options.getToken] - Custom token getter (default: localStorage), read afresh
 *   on every (re)connect
 * @param {boolean} [options.reconnectOnVisible] - Reconnect immediately when the tab becomes
 *   visible/focused if the connection has died. Background tabs have their timers throttled, so
 *   the delayed retry below may not fire while hidden; this recovers on return without a reload.
 * @returns {Function} Cleanup function that closes the connection
 */
export default function createEventSource(path, params = {}, eventHandlers = {}, options = {}) {
  const closeOn = [].concat(options.closeOn ?? []);
  let closed = false;
  let state = CLOSED;
  let controller = null;
  let generation = 0;
  let retryTimer = null;

  function scheduleReconnect(delay) {
    if (retryTimer) return;
    retryTimer = setTimeout(() => {
      retryTimer = null;
      if (!closed) connect();
    }, delay);
  }

  function closeConnection() {
    if (controller) controller.abort();
  }

  // A failure nobody asked for: report it and retry. A superseded or deliberately closed
  // connection reports nothing.
  function fail(myGeneration, status) {
    if (closed || myGeneration !== generation) return;
    state = CLOSED;
    options.onError?.(status === undefined ? undefined : { status });
    scheduleReconnect(RECONNECT_DELAY);
  }

  function dispatch(myGeneration, parser) {
    const { data } = parser;
    const name = parser.event || 'message';
    parser.event = '';
    parser.data = '';
    if (data === '') return;
    const handler = eventHandlers[name];
    if (typeof handler !== 'function') return;
    try {
      handler(JSON.parse(data.slice(0, -1)));
    } catch {
      // ignore parse errors
    }
    if (closeOn.includes(name)) {
      closed = true;
      state = CLOSED;
      if (myGeneration === generation) closeConnection();
    }
  }

  function processLine(myGeneration, parser, line) {
    if (line === '') {
      dispatch(myGeneration, parser);
      return;
    }
    if (line.startsWith(':')) return;
    const colon = line.indexOf(':');
    const field = colon < 0 ? line : line.slice(0, colon);
    let value = colon < 0 ? '' : line.slice(colon + 1);
    if (value.startsWith(' ')) value = value.slice(1);
    if (field === 'data') parser.data += `${value}\n`;
    else if (field === 'event') parser.event = value;
  }

  // Splits decoded text into lines on CRLF, CR or LF. A CR ends its line at once and marks the
  // next LF, which may arrive in the next chunk, as part of the same line break.
  function feed(myGeneration, parser, text) {
    let lineStart = 0;
    for (let i = 0; i < text.length; i += 1) {
      if (closed || myGeneration !== generation) return;
      const ch = text[i];
      if (parser.skipLF) {
        parser.skipLF = false;
        if (ch === '\n') {
          lineStart = i + 1;
          continue;
        }
      }
      if (ch === '\n' || ch === '\r') {
        processLine(myGeneration, parser, parser.partial + text.slice(lineStart, i));
        parser.partial = '';
        lineStart = i + 1;
        if (ch === '\r') parser.skipLF = true;
      }
    }
    if (!closed && myGeneration === generation) parser.partial += text.slice(lineStart);
  }

  async function run(myGeneration, myController, url, headers) {
    let response;
    try {
      response = await fetch(url, { headers, cache: 'no-store', signal: myController.signal });
    } catch {
      fail(myGeneration, undefined);
      return;
    }
    if (closed || myGeneration !== generation) return;

    if (response.status === NO_CONTENT) {
      // Native behaviour: the server says there is nothing to stream, so stop for good.
      closed = true;
      state = CLOSED;
      options.onError?.({ status: NO_CONTENT });
      return;
    }
    const contentType = response.headers?.get('content-type') ?? '';
    if (!response.ok || !contentType.toLowerCase().startsWith(EVENT_STREAM) || !response.body) {
      fail(myGeneration, response.status);
      return;
    }

    state = OPEN;
    const reader = response.body.getReader();
    const decoder = new TextDecoder('utf-8');
    const parser = { partial: '', skipLF: false, event: '', data: '' };
    for (;;) {
      let result;
      try {
        result = await reader.read();
      } catch {
        fail(myGeneration, response.status);
        return;
      }
      if (closed || myGeneration !== generation) return;
      if (result.done) {
        // Flush the decoder; an event without its closing blank line is discarded, per the spec.
        feed(myGeneration, parser, decoder.decode());
        fail(myGeneration, response.status);
        return;
      }
      feed(myGeneration, parser, decoder.decode(result.value, { stream: true }));
    }
  }

  function connect() {
    // Abort any previous connection before opening a new one. Its generation is now stale, so a
    // late chunk, rejection or EOF from it dispatches nothing and cannot race the new connection.
    closeConnection();
    generation += 1;
    // CONNECTING is set before fetch is awaited, so a visibilitychange and a focus arriving
    // together find a live connection and open one stream, not two.
    state = CONNECTING;
    controller = new AbortController();

    const token = options.getToken ? options.getToken() : localStorage.getItem(TOKEN_KEY);
    const query = new URLSearchParams(params).toString();
    const url = `${BASE_URL}${path}${query ? `?${query}` : ''}`;
    const headers = { Accept: EVENT_STREAM };
    if (token) headers.Authorization = `Bearer ${token}`;
    run(generation, controller, url, headers);
  }

  // When the tab wakes from being backgrounded, its throttled retry timer may not
  // have fired. If the connection is dead, reconnect right now instead of waiting.
  function handleVisible() {
    if (closed || document.visibilityState !== 'visible') return;
    if (state === CLOSED) {
      if (retryTimer) {
        clearTimeout(retryTimer);
        retryTimer = null;
      }
      connect();
    }
  }

  connect();

  if (options.reconnectOnVisible) {
    document.addEventListener('visibilitychange', handleVisible);
    window.addEventListener('focus', handleVisible);
  }

  return () => {
    closed = true;
    state = CLOSED;
    if (retryTimer) {
      clearTimeout(retryTimer);
      retryTimer = null;
    }
    if (options.reconnectOnVisible) {
      document.removeEventListener('visibilitychange', handleVisible);
      window.removeEventListener('focus', handleVisible);
    }
    closeConnection();
  };
}
