/**
 * Per-URL memo of the last raw JSON reply and the object parsed from it
 * (docs/engineering/performance-review-2026-10-plan.md, P3b).
 *
 * <p>The polled endpoints answer the same bytes most cycles. Parsing each reply afresh made a new object
 * every time, which re-rendered the provider chain and repainted the map for nothing. When the raw text
 * equals the last raw text for the URL, {@link parseMemoised} hands back the SAME parsed object, so callers
 * can tell "unchanged" with {@code Object.is}.
 *
 * <p>Cleared by {@code clearSwrCache} (logout, session expiry), so one account's parsed object is never
 * handed to the next account on the same page.
 */
const memo = new Map();

/** Forgets every remembered reply. */
export function clearJsonMemo() {
  memo.clear();
}

/**
 * Parses a reply requested with {@code responseType: 'text'}, returning the previous object when the text
 * is identical to the previous reply for {@code key}.
 *
 * <p>A non-string (a test double, or a client that already parsed it) passes through untouched; an empty
 * string passes through as it always did (a 204 has no body); text that is not JSON is returned as the
 * string, as axios's silent JSON parsing did.
 *
 * @param {string} key the request URL
 * @param {*} data the response data
 * @returns {*} the parsed body
 */
export function parseMemoised(key, data) {
  if (typeof data !== 'string' || data === '') return data;
  const held = memo.get(key);
  if (held && held.raw === data) return held.parsed;
  let parsed;
  try {
    parsed = JSON.parse(data);
  } catch {
    return data;
  }
  memo.set(key, { raw: data, parsed });
  return parsed;
}

/**
 * GETs {@code url} as text and returns the response with {@code data} parsed through
 * {@link parseMemoised}; a rejection has its body re-parsed by {@link parseErrorBody}.
 *
 * <p>Done here rather than in a {@code transformResponse}, which would also run on error bodies.
 *
 * @param {{get: Function}} client the shared axios instance
 * @param {string} url the request URL (also the memo key)
 * @param {object} [config] extra axios config, e.g. {@code params}
 * @returns {Promise<object>} the axios response, with parsed {@code data}
 */
export async function getMemoised(client, url, config = {}) {
  let response;
  try {
    response = await client.get(url, { ...config, responseType: 'text' });
  } catch (err) {
    throw parseErrorBody(err);
  }
  response.data = parseMemoised(url, response.data);
  return response;
}

/**
 * Re-parses an error response's body in place, because {@code responseType: 'text'} leaves it a string
 * where axios would have parsed it. Callers' {@code err.response.data} keeps being parsed JSON. Not
 * memoised. Returns the same error.
 *
 * @param {*} err the rejection
 * @returns {*} the same rejection
 */
export function parseErrorBody(err) {
  const body = err?.response?.data;
  if (typeof body === 'string' && body !== '') {
    try {
      err.response.data = JSON.parse(body);
    } catch {
      // not JSON — leave the text
    }
  }
  return err;
}
