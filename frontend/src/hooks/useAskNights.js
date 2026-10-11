import { useEffect, useMemo, useState } from 'react';
import { getAstroConditions } from '../api/astroApi.js';
import { getAuroraForecastResults } from '../api/auroraApi.js';
import { nightRequestsOf } from '../utils/askNights.js';

const EMPTY_MAP = new Map();
const EMPTY_HELD = Object.freeze({ answerId: null, astro: EMPTY_MAP, aurora: EMPTY_MAP });

/** Asks for each date through {@code fetchOne}; a date that failed, or answered with no list, is left out. */
async function settle(dates, fetchOne) {
  const byDate = new Map();
  const results = await Promise.allSettled(dates.map((date) => fetchOne(date)));
  results.forEach((result, i) => {
    if (result.status === 'fulfilled' && Array.isArray(result.value)) {
      byDate.set(dates[i], result.value);
    }
  });
  return byDate;
}

/**
 * The night payloads a live Ask answer's night picks join to (`docs/engineering/ask-night-plan.md` §2.6),
 * fetched ONCE for that answer and held only for it.
 *
 * <h2>By the dates the picks name — the client never works out "tonight"</h2>
 * <p>Each night pick's own {@code date} and {@code targetType} say which payload it needs, so this asks
 * {@code GET /api/astro/conditions?date=} once per distinct astro date and
 * {@code GET /api/aurora/forecast/results?date=} once per distinct aurora date, and nothing else. It does
 * NOT ask for "tonight and tomorrow": "tonight" is the running night, which before nautical dawn is
 * YESTERDAY, the server's own answer is served only by the PRO/ADMIN aurora status, and the civil date
 * the client could fall back to names the wrong night — an answer whose pick sat on the night in progress
 * would then have no payload and its card would be dropped.
 *
 * <h2>The aurora endpoint is PRO/ADMIN's — a LITE client never calls it</h2>
 * <p>{@code canSeeAurora} false asks for no aurora date at all (the endpoint is 403 for LITE, and a
 * request that can only fail is a request not made); an aurora pick can then never get a card.
 *
 * <h2>Held for the answer, never stored</h2>
 * <p>Component state keyed on the answer's id: a new answer fetches its own dates, a cleared one shows
 * nothing, and nothing goes to {@code swrCache}, {@code localStorage} or module scope — a logout unmounts
 * the provider and the next reader starts empty. (The Map's own {@code astroConditionsByDate} /
 * {@code auroraResultsByDate} are {@code useState} inside {@code MapView}, not a cache this can read.)
 * A new briefing run for the SAME answer asks again, so the cards follow the forecast the way a solar
 * card does; what is held meanwhile stays until the new copy lands, so the cards do not blink out.
 *
 * <h2>A failed fetch drops its cards, and says nothing</h2>
 * <p>A date whose request failed is simply absent, so that night's picks have no card (never a
 * half-drawn one); the answer's own sentence is untouched. The next answer, and the next briefing run,
 * ask again.
 *
 * @param {?{id: number, picks: Array<object>}} answer the live answer, or null
 * @param {boolean} canSeeAurora PRO or ADMIN: the aurora endpoint may be asked
 * @param {?string} runKey the briefing run's {@code generatedAt}, so a rebuilt forecast re-asks
 * @returns {{astro: Map<string, Array<object>>, aurora: Map<string, Array<object>>}} the payloads held
 *   for THIS answer, by date; both empty while nothing is held for it
 */
export default function useAskNights(answer, canSeeAurora, runKey) {
  const answerId = answer?.id ?? null;
  const requests = useMemo(() => nightRequestsOf(answer?.picks), [answer]);
  const astroKey = requests.astro.join(',');
  const auroraKey = canSeeAurora ? requests.aurora.join(',') : '';
  const [held, setHeld] = useState(EMPTY_HELD);

  useEffect(() => {
    if (answerId === null || (astroKey === '' && auroraKey === '')) return undefined;
    let cancelled = false;
    const astroDates = astroKey === '' ? [] : astroKey.split(',');
    const auroraDates = auroraKey === '' ? [] : auroraKey.split(',');
    Promise.all([
      settle(astroDates, getAstroConditions),
      settle(auroraDates, getAuroraForecastResults),
    ]).then(([astro, aurora]) => {
      if (!cancelled) setHeld({ answerId, astro, aurora });
    });
    return () => { cancelled = true; };
    // `runKey` is the trigger for asking again on a rebuilt forecast; the dates are what is asked.
  }, [answerId, astroKey, auroraKey, runKey]);

  return answerId !== null && held.answerId === answerId ? held : EMPTY_HELD;
}
