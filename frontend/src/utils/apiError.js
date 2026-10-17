/**
 * Reads the sentence a refused API request carries, for display.
 *
 * The backend's {@code GlobalExceptionHandler} answers every refusal with a JSON body whose key is
 * {@code error} ({@code {"error": "…"}}) — status-carrying exceptions, bad input and the catch-all
 * 500 alike — and the few controllers that build their own refusal use the same key. A handler that
 * reads {@code message} instead finds nothing and falls through to axios's generic "Request failed
 * with status code 400". This is the one place the key is named, so a caller never has to guess it.
 *
 * Resolution order: the server's {@code error} string, then its {@code message} string (a
 * defensive second key, never what the backend sends today), then the caller's {@code fallback},
 * then {@code err.message}. Only non-blank strings count — a string body (a proxy's HTML error
 * page, a plain-text 400) is never shown, because it is not a sentence meant for the reader.
 *
 * @param {unknown} err - the value caught from an axios call.
 * @param {string} [fallback] - what to show when the server said nothing usable.
 * @returns {string|undefined} the message; undefined only when there is no server sentence, no
 *   fallback and no {@code err.message}.
 */
export function apiErrorMessage(err, fallback) {
  const data = err?.response?.data;
  if (data !== null && typeof data === 'object') {
    for (const key of ['error', 'message']) {
      const value = data[key];
      if (typeof value === 'string' && value.trim() !== '') return value;
    }
  }
  if (typeof fallback === 'string' && fallback !== '') return fallback;
  return err?.message;
}
