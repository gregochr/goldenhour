import { useEffect, useState } from 'react';
import { getReachVerdicts } from '../api/settingsApi.js';

/**
 * The Plan headline recomputed for each drive tier, fetched once per (briefing version, origin)
 * (docs/engineering/reach-scoped-card-plan.md R2 step 7).
 *
 * <p><b>It fetches only when the answer can be true.</b> {@code enabled} is the provider's conjunction:
 * Pro or Admin, not drive-locked, not a body shaped by sub-region picks, and the reader's drives measured for the
 * active origin ({@code reachMeasured}, D2). Lite never asks (the endpoint is 403 for it), and a reader with no
 * measured drive has no tier that could act.
 *
 * <p><b>One fetch per (briefingVersion, origin), and a tier tap never fetches.</b> All three tiers arrive in one
 * body, so the active tier is a lookup the caller makes; the tier is deliberately not an input here. A new
 * {@code briefingVersion} (the briefing was rebuilt or re-rated) or a new origin is a new question and fetches once.
 *
 * <p><b>A mismatched answer is never used.</b> The body echoes the {@code briefingVersion} it was computed from.
 * If that is not the version of the briefing the page holds (the two requests straddled a rebuild), the answer is
 * discarded and the request is made ONCE more. A second mismatch is discarded too, and the page keeps the served
 * words until its next briefing arrives with a version to match.
 *
 * <p><b>Until a matching answer exists the hook answers null</b>, and the card then wears today's word WITHOUT
 * scope words, which is true as printed. A rejection answers null as well: a failed request is an absence, never a
 * different headline.
 *
 * @param {object}  args
 * @param {boolean} args.enabled        whether the endpoint may be asked at all
 * @param {?string} args.briefingVersion the version of the briefing the page holds, or null/undefined when it
 *                                       carries none (an older backend mid-deploy): nothing is fetched
 * @param {?number} args.originId       the away origin's region id, or null for home
 * @returns {?Object<string, Array<Object>>} the tiers, keyed by minutes ({@code "45"}, {@code "90"},
 *          {@code "150"}), or null while there is no matching answer
 */
export default function useReachVerdicts({ enabled, briefingVersion, originId }) {
  const key = enabled && briefingVersion ? `${briefingVersion}|${originId ?? 'home'}` : null;
  const [held, setHeld] = useState({ key: null, tiers: null });

  useEffect(() => {
    if (key === null) return undefined;
    let cancelled = false;

    const ask = (retriesLeft) => {
      // Wrapped so that a synchronous throw (a missing export in a test double, a bad argument) is a rejection
      // handled below, not an exception out of the effect.
      Promise.resolve()
        .then(() => getReachVerdicts(originId ?? null))
        .then((body) => {
          if (cancelled) return;
          if (!body || body.measured !== true || !body.tiers) {
            setHeld({ key, tiers: null });
            return;
          }
          if (body.briefingVersion !== briefingVersion) {
            if (retriesLeft > 0) {
              ask(retriesLeft - 1);
            } else {
              setHeld({ key, tiers: null });
            }
            return;
          }
          setHeld({ key, tiers: body.tiers });
        })
        .catch(() => {
          if (!cancelled) setHeld({ key, tiers: null });
        });
    };
    ask(1);

    return () => { cancelled = true; };
  }, [key, briefingVersion, originId]);

  // Derived, not cleared in an effect: an answer held for another version or origin is simply not this one's.
  return key !== null && held.key === key ? held.tiers : null;
}
