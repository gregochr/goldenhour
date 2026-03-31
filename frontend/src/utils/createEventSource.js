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
 * @returns {Function} Cleanup function that closes the connection
 */
export default function createEventSource(path, params = {}, eventHandlers = {}, options = {}) {
  const RECONNECT_DELAY = 5000;
  let closed = false;
  let source;

  function connect() {
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
        setTimeout(() => {
          if (!closed) connect();
        }, RECONNECT_DELAY);
      }
    };
  }

  connect();

  return () => {
    closed = true;
    source.close();
  };
}
