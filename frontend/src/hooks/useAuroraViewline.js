import { useEffect, useState } from 'react';
import { getAuroraViewline, getAuroraForecastViewline } from '../api/auroraApi.js';
import { viewlineDrawable } from '../utils/viewlineDrawable.js';

const POLL_INTERVAL_MS = 5 * 60 * 1000; // 5 minutes

/**
 * Polls both aurora viewline endpoints every 5 minutes and offers ONE line: always live, the
 * forecast as the fallback, never both.
 *
 * Only fetches when `enabled` is true (PRO/ADMIN user with aurora active at MODERATE or STRONG
 * level). When it is, both `GET /viewline` (the OVATION nowcast's 5% contour) and
 * `GET /viewline/forecast` (the Kp rule, drawn for the highest Kp NOAA forecasts for the rest of
 * tonight) are asked on mount and again every 5 minutes. The forecast endpoint reads the live
 * forecast per request, so the forecast line follows NOAA's revisions with no help from the alert
 * state: this hook no longer needs the trigger type or the alert's Kp.
 *
 * The line on offer is the live one when it is drawable ({@code viewlineDrawable}: active, with
 * points), else the forecast one when that is drawable, else null. {@code source} says which, so a
 * caller can tell without reading {@code isForecast}.
 *
 * ⚠️ Each answer is held on its own and replaced only by a later answer from the same endpoint, so a
 * failed poll keeps that endpoint's previous line (a transient error must not blank the map), while
 * an {@code active: false} answer is an answer and drops it, handing the map to the forecast line.
 * Both are cleared whenever the hook is disabled — a later alert starts with no line rather than the
 * ended alert's — and `cancelled` drops any response from a run that has since been superseded or
 * disabled, so a late answer can never land in a newer run's state.
 *
 * @param {boolean} enabled whether to poll
 * @param {object} [options]
 * @param {boolean} [options.includeForecast=true] ask the forecast endpoint too. A caller whose
 *   copy is about the live OVATION line alone passes false, so the forecast is neither asked for
 *   (the endpoint reads NOAA's forecast per request) nor ever offered to it. (The banner did, until
 *   its redesign dropped the viewline sentence; no production caller passes it today.)
 * @returns {{ viewline: object|null, source: 'live'|'forecast'|null }}
 */
export function useAuroraViewline(enabled, { includeForecast = true } = {}) {
  const [live, setLive] = useState(null);
  const [forecast, setForecast] = useState(null);

  useEffect(() => {
    if (!enabled) {
      // Inline async wrapper satisfies react-hooks/set-state-in-effect while the setState still
      // applies synchronously this tick — the idiom MapView's own fetch effects use.
      (async () => {
        setLive(null);
        setForecast(null);
      })();
      return undefined;
    }

    let cancelled = false;

    async function fetchBoth() {
      // Independent: one endpoint failing or lagging must not hold up or discard the other.
      const askLive = getAuroraViewline().then(
        (data) => { if (!cancelled) setLive(data); },
        () => { /* transient error — keep whatever is held */ },
      );
      const askForecast = !includeForecast ? Promise.resolve() : getAuroraForecastViewline().then(
        (data) => { if (!cancelled) setForecast(data); },
        () => { /* transient error — keep whatever is held */ },
      );
      await Promise.all([askLive, askForecast]);
    }

    fetchBoth();
    const interval = setInterval(fetchBoth, POLL_INTERVAL_MS);

    return () => {
      cancelled = true;
      clearInterval(interval);
    };
  }, [enabled, includeForecast]);

  // `enabled &&` covers the render in which the hook is disabled, before the clear above has run.
  if (enabled && viewlineDrawable(live)) return { viewline: live, source: 'live' };
  if (enabled && includeForecast && viewlineDrawable(forecast)) return { viewline: forecast, source: 'forecast' };
  return { viewline: null, source: null };
}
