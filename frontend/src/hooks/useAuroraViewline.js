import { useEffect, useRef, useState } from 'react';
import { getAuroraViewline, getAuroraForecastViewline } from '../api/auroraApi.js';

const POLL_INTERVAL_MS = 5 * 60 * 1000; // 5 minutes

/**
 * Polls the aurora viewline endpoint every 5 minutes.
 *
 * Only fetches when `enabled` is true (PRO/ADMIN user with aurora active at
 * MODERATE or STRONG level).
 *
 * When `triggerType` is `'forecast'`, fetches the forecast viewline — a lookup built from the Kp the
 * alert was triggered on, which the status serves as `forecastKp` — again only when that Kp changes,
 * or on the poll cadence until a first answer has landed. Otherwise fetches the live OVATION
 * viewline with 5-minute polling.
 *
 * ⚠️ A line is offered only as what it was fetched as: the live nowcast, or the forecast line for
 * one Kp. Each answer is held with that key and handed out only while the key is still in force, so
 * a trigger flip or a changed forecast Kp withholds the old line in the very render that makes the
 * change — not a commit later, after an effect. Three ways a wrong line used to stand:
 *   - A STALE WINDOW. Nothing withheld the previous line on a trigger change, so it was offered as
 *     the new mode's until the new request landed — and for good if that request failed, since the
 *     forecast mode never re-asked.
 *   - A CHANGED KP. An escalation inside a forecast-triggered alert re-sets the Kp the forecast line
 *     is built from without moving `enabled` or the mode, so the pre-escalation line stood for the
 *     rest of the alert, under an overlay label quoting the new Kp.
 *   - A LATE RESPONSE. A live request still out when the trigger flipped to forecast could land
 *     after the forecast line and replace it. Hence `cancelled`, which drops any answer from a run
 *     that has since been superseded.
 * And the held line is cleared whenever the hook is disabled, because a later alert may want the
 * very same key — the live line, or a forecast at the same Kp — and must start with no line rather
 * than the ended alert's.
 *
 * @param {boolean} enabled whether to poll
 * @param {string|null} triggerType 'forecast' or 'realtime' (or null)
 * @param {number|null} forecastKp the Kp a forecast-triggered alert was raised on — the status's
 *   `forecastKp`; read only in forecast mode
 * @returns {{ viewline: object|null }}
 */
export function useAuroraViewline(enabled, triggerType = null, forecastKp = null) {
  const isForecast = triggerType === 'forecast';
  // What the line on offer must have been fetched as. `null` and `'realtime'` are one key — both
  // mean the live line, whose endpoint reads no trigger state at all — so a flip between them
  // neither withholds the line nor re-asks for it. The forecast line is keyed on its Kp because the
  // backend builds it from exactly that (`AuroraController.getForecastViewline` reads
  // `lastTriggerKp`, the same field the status serves as `forecastKp`).
  const lineKey = isForecast ? `forecast:${forecastKp ?? ''}` : 'live';
  const [held, setHeld] = useState(null); // { key, data }: the last line landed, and what it was fetched as
  const intervalRef = useRef(null);

  useEffect(() => {
    if (!enabled) {
      // Inline async wrapper satisfies react-hooks/set-state-in-effect while the setState still
      // applies synchronously this tick — the idiom MapView's own fetch effects use.
      (async () => setHeld(null))();
      return undefined;
    }

    let cancelled = false;
    let landed = false;

    async function fetchViewline() {
      try {
        const data = isForecast
          ? await getAuroraForecastViewline()
          : await getAuroraViewline();
        if (cancelled) return;
        landed = true;
        setHeld({ key: lineKey, data });
      } catch {
        // Transient error — keep whatever is held. It is handed out only while its key is still in
        // force (see the return below), so a failure can never leave another mode's line, or another
        // Kp's, on offer: a failed live poll keeps the previous poll's line, and a failed first
        // request leaves none.
      }
    }

    fetchViewline();

    // Re-asked on the poll cadence: always for the live line, which is a nowcast; for the forecast
    // line only until one has landed — it changes only with its Kp, but one failed request must not
    // leave the map without a line for the rest of the alert.
    intervalRef.current = setInterval(() => {
      if (!isForecast || !landed) fetchViewline();
    }, POLL_INTERVAL_MS);

    return () => {
      cancelled = true;
      clearInterval(intervalRef.current);
    };
  }, [enabled, isForecast, lineKey]);

  // `enabled &&` covers the render in which the hook is disabled, before the clear above has run;
  // the key comparison covers every render in which the line in force has changed.
  return { viewline: enabled && held?.key === lineKey ? held.data : null };
}
