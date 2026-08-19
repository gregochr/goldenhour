import { useEffect, useState } from 'react';
import { getTodaysLight } from '../api/lightApi.js';

/**
 * Today's light at the caller's home, for the masthead's light rule.
 *
 * <p>Sun times change once per day, so this resolves on mount and on nothing else — there is no
 * poll. The one thing that can change them mid-session is the user saving a different home
 * postcode, which is what `settingsVersion` is for: `App.jsx` already bumps a counter when the
 * settings modal closes, for exactly this reason, so the rule lights up without a reload.
 *
 * <p><b>Three states in one value.</b> `undefined` when there is no answer — not asked yet, or asked
 * and the request failed; `null` once the server has answered that there is no home saved; the day's
 * light otherwise. The masthead renders all three differently, and a separate `ready` flag would be
 * a second thing every call site has to remember to thread through — the reason the "set a postcode"
 * nudge would otherwise flash at every reader who already has one.
 *
 * <p>A failure resolving to `undefined` rather than `null` is deliberate: see the catch below.
 *
 * <p>`enabled` exists because only the window-first arm has this masthead, and a hook cannot be
 * called conditionally. Without it, every v1 reader would pay for a request whose answer nothing
 * renders — the same reason `WindowFirstBriefingProvider` is mounted inside App's flag branch
 * rather than beside its siblings.
 *
 * @param {boolean} enabled whether this arm renders the light rule at all
 * @param {number} [settingsVersion] bumped when the caller's home settings may have changed
 * @returns {object|null|undefined} the day's light, null when no home is saved, undefined while
 *   the answer is outstanding
 */
export default function useTodaysLight(enabled, settingsVersion = 0) {
  const [light, setLight] = useState(undefined);

  useEffect(() => {
    if (!enabled) return undefined;
    let live = true;
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
  }, [enabled, settingsVersion]);

  return light;
}
