import { useEffect, useRef, useState } from 'react';
import { getTodaysLight } from '../api/lightApi.js';
import { ukDateStr } from '../utils/mapDates.js';

/**
 * Today's light at the caller's home, for the masthead's light rule.
 *
 * <p>Sun times change once per day, so there is no poll. Two things move them, and both are events
 * rather than intervals. The user saving a different home postcode, which is what `settingsVersion`
 * is for — `App.jsx` already bumps a counter when the settings modal closes, so the rule lights up
 * without a reload. And the UK civil day turning over: this is a planning dashboard, a tab left open
 * from one evening to the next morning is an ordinary way to use it, and a mount-only fetch left
 * yesterday's gradient and yesterday's clock times on screen under a label that names only the
 * postcode — nothing on the band carries a date, so there was no way to tell. Checked when the tab
 * comes back to the foreground and only when the date has actually changed, which keeps the "no
 * polling" the design asks for.
 *
 * <p><b>Three states in one value.</b> `undefined` when there is no answer — not asked yet, or asked
 * and the request failed; `null` once the server has answered that there is no home saved; the day's
 * light otherwise. The masthead renders all three differently, and a separate `ready` flag would be
 * a second thing every call site has to remember to thread through — the reason the "set a postcode"
 * nudge would otherwise flash at every reader who already has one.
 *
 * <p>A failure resolving to `undefined` rather than `null` is deliberate: see the catch below.
 *
 * @param {number} [settingsVersion] bumped when the caller's home settings may have changed
 * @returns {object|null|undefined} the day's light, null when no home is saved, undefined while
 *   the answer is outstanding
 */
export default function useTodaysLight(settingsVersion = 0) {
  const [light, setLight] = useState(undefined);
  // Bumped when the tab returns on a later UK day; drives the refetch through the same effect.
  const [dayTick, setDayTick] = useState(0);
  // The UK civil date the current answer describes. A ref, not state: it is read inside an event
  // listener and must never itself cause a render.
  const answeredFor = useRef(null);

  useEffect(() => {
    let live = true;
    answeredFor.current = ukDateStr();
    getTodaysLight()
      .then((result) => {
        if (live) setLight(result ?? null);
      })
      // ⚠️ `undefined`, NOT `null`. A failed request and a 204 are different facts and the masthead
      // says different things about them: `null` renders "Set your home postcode…", which is a
      // positive claim about the reader's ACCOUNT that a 502 or a dropped connection is no evidence
      // for. `undefined` renders the unlit rule and a blank row — no claim at all, which is the
      // honest picture and needs no fourth state. This was `null` and an adversarial review caught
      // that it contradicted the principle stated two lines up in MastheadLight's own docs.
      .catch(() => {
        if (live) setLight(undefined);
      });
    return () => { live = false; };
  }, [settingsVersion, dayTick]);

  useEffect(() => {
    const refetchIfTheDayTurned = () => {
      if (document.visibilityState !== 'visible') return;
      if (answeredFor.current && ukDateStr() !== answeredFor.current) {
        setDayTick((tick) => tick + 1);
      }
    };
    // Both events, because they answer different returns: `visibilitychange` covers a tab switched
    // back to, `focus` covers a window raised from behind another with the tab already visible.
    // The date guard makes the pair idempotent, so the double-fire costs nothing.
    document.addEventListener('visibilitychange', refetchIfTheDayTurned);
    window.addEventListener('focus', refetchIfTheDayTurned);
    return () => {
      document.removeEventListener('visibilitychange', refetchIfTheDayTurned);
      window.removeEventListener('focus', refetchIfTheDayTurned);
    };
  }, []);

  return light;
}
