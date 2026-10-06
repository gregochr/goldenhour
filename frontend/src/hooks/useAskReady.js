import { useEffect, useMemo, useState } from 'react';
import { getReady } from '../api/askApi.js';

/** Shared, so a hook with nothing to show hands every render the same array. */
const NONE = [];
const NOTHING = { key: null, questions: NONE, error: null };

/**
 * The Ready questions for a scope, from `GET /api/ask/ready?scope=…`.
 *
 * <p><b>Keyed on the scope AND the briefing's {@code generatedAt}.</b> The server withholds a
 * question whenever its answer no longer agrees with live data, all or nothing, so a list fetched
 * before a new briefing landed can name a question whose prose is now false. The hook therefore
 * refetches when the briefing is rebuilt, and — the part that makes this safe rather than merely
 * fresh — only ever hands out a list fetched for the key it is currently asked about: while a
 * refetch is in flight, or after the scope changes, the questions are an empty list, never the old
 * key's. A failed fetch is an empty list too ({@code failed} is true), for the same reason.
 *
 * <p>Tapping a Ready question never calls this endpoint again: the answer is already in the list.
 * Nothing is cached in `utils/swrCache.js`; the response is user-independent and the browser's own
 * ETag revalidation covers it.
 *
 * @param {string|number} [scope='all'] `all`, or an enabled region's id
 * @param {?string} [generatedAt] the briefing's {@code generatedAt}; a new value refetches
 * @param {{enabled?: boolean}} [options] {@code enabled: false} fetches nothing (a hidden surface)
 * @returns {{questions: Array<object>, loading: boolean, failed: boolean, error: ?Error}}
 */
export default function useAskReady(scope = 'all', generatedAt = null, { enabled = true } = {}) {
  const key = `${scope}|${generatedAt ?? ''}`;
  const [state, setState] = useState(NOTHING);
  // A hidden surface forgets its list. Without this a surface that is shown again under the SAME key
  // would hand out the list it held when it was hidden — possibly hours old, for a rule (freshness)
  // the server decides per request — while the new fetch is still in flight. The reset is made
  // during render, React's own pattern for state that follows a prop.
  const [wasEnabled, setWasEnabled] = useState(enabled);
  if (wasEnabled !== enabled) {
    setWasEnabled(enabled);
    if (!enabled) setState(NOTHING);
  }

  useEffect(() => {
    if (!enabled) return undefined;
    let live = true;
    getReady(scope)
      .then((body) => {
        if (!live) return;
        const questions = Array.isArray(body?.questions) ? body.questions : NONE;
        setState({ key, questions, error: null });
      })
      // A superseded request's failure must not blank the key the newest request answered.
      .catch((error) => {
        if (live) setState({ key, questions: NONE, error });
      });
    return () => { live = false; };
  }, [scope, generatedAt, enabled, key]);

  return useMemo(() => {
    const current = enabled && state.key === key;
    return {
      questions: current ? state.questions : NONE,
      loading: enabled && !current,
      failed: current && state.error !== null,
      error: current ? state.error : null,
    };
  }, [enabled, key, state]);
}
