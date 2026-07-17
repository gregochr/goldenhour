const BASE_URL = import.meta.env.VITE_API_BASE_URL || '';
const TOKEN_KEY = 'goldenhour_token';

/**
 * Creates an EventSource connection with standardised token auth,
 * JSON parsing, error handling, and optional auto-close.
 *
 * @param {string} path - API path (e.g. '/api/status/stream')
 * @param {Object} params - Additional query params (token added automatically)
 * @param {Object} eventHandlers - { 'event-name': (parsedData) => void }
 * @param {Object} [options]
 * @param {Function} [options.onError] - Called on connection error (when readyState !== CLOSED)
 * @param {string} [options.closeOn] - Event name that triggers source.close() after handler fires
 * @param {Function} [options.getToken] - Custom token getter (default: localStorage)
 * @param {boolean} [options.reconnectOnVisible] - Reconnect immediately when the tab becomes
 *   visible/focused if the connection has died. Background tabs have their timers throttled, so
 *   the delayed retry below may not fire while hidden; this recovers on return without a reload.
 * @returns {Function} Cleanup function that closes the connection
 */
export default function createEventSource(path, params = {}, eventHandlers = {}, options = {}) {
  const RECONNECT_DELAY = 5000;
  let closed = false;
  let source;
  let retryTimer = null;

  function scheduleReconnect(delay) {
    if (retryTimer) return;
    retryTimer = setTimeout(() => {
      retryTimer = null;
      if (!closed) connect();
    }, delay);
  }

  function connect() {
    // Close any previous connection before opening a new one. Its onerror handler
    // stays attached otherwise, so a stale source (e.g. still CONNECTING after a
    // network drop) could keep firing onError and race with the new connection.
    if (source) source.close();

    const token = options.getToken ? options.getToken() : localStorage.getItem(TOKEN_KEY);
    const qp = new URLSearchParams({ ...params, token });
    const url = `${BASE_URL}${path}?${qp.toString()}`;
    source = new EventSource(url);

    for (const [eventName, handler] of Object.entries(eventHandlers)) {
      source.addEventListener(eventName, (event) => {
        try {
          handler(JSON.parse(event.data));
        } catch {
          // ignore parse errors
        }
        if (options.closeOn === eventName) {
          closed = true;
          source.close();
        }
      });
    }

    source.onerror = () => {
      options.onError?.();
      // EventSource with readyState CLOSED won't auto-reconnect (e.g. non-200
      // response from server). Manually retry after a delay.
      if (!closed && source.readyState === EventSource.CLOSED) {
        scheduleReconnect(RECONNECT_DELAY);
      }
    };
  }

  // When the tab wakes from being backgrounded, its throttled retry timer may not
  // have fired. If the connection is dead, reconnect right now instead of waiting.
  function handleVisible() {
    if (closed || document.visibilityState !== 'visible') return;
    if (!source || source.readyState === EventSource.CLOSED) {
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
    if (retryTimer) {
      clearTimeout(retryTimer);
      retryTimer = null;
    }
    if (options.reconnectOnVisible) {
      document.removeEventListener('visibilitychange', handleVisible);
      window.removeEventListener('focus', handleVisible);
    }
    source.close();
  };
}
