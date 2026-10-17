import apiClient from './axiosClient.js';

/**
 * Ask PhotoCast's three endpoints (`docs/engineering/ask-photocast-plan.md` §2.9).
 *
 * <p>Every refusal is rethrown as an {@link AskApiError} carrying the three things the client
 * decides on: the HTTP {@code status}, the server's {@code code} (the three 429s and the 503 are told
 * apart by that alone, never by their wording) and the human {@code error} sentence, which is also
 * the Error's {@code message}, so the app's shared {@code utils/apiError.js#apiErrorMessage} reads
 * the same sentence. The axios response interceptor's single-flight 401 refresh is
 * untouched — it runs before a request ever reaches the catch below, so an expired token is
 * retried once and only a refresh that fails reaches a caller as a 401.
 *
 * <p>Nothing here is cached. `/api/ask/ready` is ETag-revalidated by the browser below the XHR layer
 * (it is user-independent), and the other two are personal: the allowance and an answer must never
 * be persisted where a logout cannot reach them, which is why none of this module, its hooks or its
 * provider touches `utils/swrCache.js`.
 */

/**
 * How long a typed question may be out. The server's own deadline is 30 s (plan §2.3); a request
 * that outlives it by this much has been lost on the way, and without a ceiling a dropped mobile
 * connection leaves the conversation on its busy line until the browser gives up.
 */
export const ASK_TIMEOUT_MS = 40_000;

/** A refused or failed Ask request. */
export class AskApiError extends Error {
  /**
   * @param {object} fields
   * @param {?number} fields.status the HTTP status, or null when no response came back at all
   * @param {?string} fields.code the server's error code (`INVALID`, `RATE_LIMITED`, …), or null
   * @param {?string} fields.error the server's human sentence, or null when it sent none
   * @param {string} [fields.message] the Error message; defaults to the sentence, then the status
   * @param {unknown} [fields.cause] the original error
   */
  constructor({ status, code, error, message, cause }) {
    super(error ?? message ?? (status != null ? `Ask request failed with status ${status}` : 'Ask request failed'),
      cause === undefined ? undefined : { cause });
    this.name = 'AskApiError';
    this.status = status;
    this.code = code;
    this.error = error;
  }
}

/** Reads `{status, code, error}` off an axios failure; never throws on a body it did not expect. */
function toAskApiError(err) {
  const body = err?.response?.data;
  const fields = body !== null && typeof body === 'object' ? body : {};
  return new AskApiError({
    status: err?.response?.status ?? null,
    code: typeof fields.code === 'string' && fields.code !== '' ? fields.code : null,
    error: typeof fields.error === 'string' && fields.error.trim() !== '' ? fields.error : null,
    message: err?.message,
    cause: err,
  });
}

/**
 * The Ready questions of a scope that are still true against live data, each with its answer.
 *
 * @param {string|number} [scope='all'] `all`, or an enabled region's id
 * @returns {Promise<{scope: string, questions: Array<object>}>}
 * @throws {AskApiError} 400 for an unknown scope, 404 while Ask is switched off
 */
export async function getReady(scope = 'all') {
  try {
    const response = await apiClient.get('/api/ask/ready', { params: { scope: String(scope) } });
    return response.data;
  } catch (err) {
    throw toAskApiError(err);
  }
}

/**
 * A typed question.
 *
 * @param {{question: string, windowId?: string, regionIds: Array<number>, view: string}} body
 *        `regionIds` empty means every region; `view` is `map`, `plan` or `coming-up`
 * @returns {Promise<object>} the 200 body: `{answerable, kind, summary, picks, events, missing, try,
 *          allowanceLeft, allowanceLimit, charged, generatedAt, runLabel}`
 * @throws {AskApiError} 400 `INVALID`, 404 (off), 429 `RATE_LIMITED`/`ALLOWANCE_EXHAUSTED`/
 *         `DAILY_LIMIT`, 502 `ENGINE_FAILED`, 503 `TYPED_UNAVAILABLE`, 401 `UNAUTHENTICATED`
 */
export async function ask(body) {
  try {
    const response = await apiClient.post('/api/ask', body, { timeout: ASK_TIMEOUT_MS });
    return response.data;
  } catch (err) {
    throw toAskApiError(err);
  }
}

/**
 * The caller's allowance for typed questions — what the empty state shows before any question is
 * asked. Always a 200; with Ask off it is `{enabled: false, …}` and zeros.
 *
 * @returns {Promise<{enabled: boolean, used: number, limit: number, left: number,
 *          typedAvailable: boolean}>}
 * @throws {AskApiError}
 */
export async function getAskSettings() {
  try {
    const response = await apiClient.get('/api/user/settings/ask');
    return response.data;
  } catch (err) {
    throw toAskApiError(err);
  }
}
