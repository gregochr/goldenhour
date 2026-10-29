import { useContext, useEffect } from 'react';
import { AuroraViewlineContext } from '../context/AuroraViewlineContext.jsx';
import { useViewlinePoll } from './useViewlinePoll.js';

/**
 * What a consumer (the banner's thumbnail, the Map tab's overlay) calls to ask for the southern viewline.
 *
 * <p>One shared poll serves every consumer (performance-review-2026-10-plan.md P3d, F16): under an
 * {@code AuroraViewlineProvider} this hook does not poll. While {@code enabled} it registers its demand
 * with the provider (a refcount, released on disable and on unmount) and returns the provider's line; the
 * provider polls while at least one consumer wants it. Each consumer keeps its own predicate, so the
 * poll's condition is the union of them, and a consumer whose predicate is false gets {@code null}
 * whatever the other holds.
 *
 * <p>Outside a provider (unit tests of a single consumer) it runs the poll itself, exactly as before, so
 * the hook never silently does nothing.
 *
 * @param {boolean} enabled whether THIS consumer wants the line
 * @param {object} [options]
 * @param {boolean} [options.includeForecast=true] offer the forecast line as the fallback; see
 *   {@code useViewlinePoll}. Under the provider (which always asks both endpoints) false only stops the
 *   forecast line being offered to this caller.
 * @returns {{ viewline: object|null, source: 'live'|'forecast'|null }}
 */
export function useAuroraViewline(enabled, { includeForecast = true } = {}) {
  const shared = useContext(AuroraViewlineContext);
  const register = shared?.register ?? null;

  useEffect(() => {
    if (!enabled || !register) return undefined;
    return register();
  }, [enabled, register]);

  // Without a provider, poll here; with one, the poll is inert (`enabled` false makes it so).
  const own = useViewlinePoll(enabled && !shared, { includeForecast });

  if (!shared) return own;
  if (!enabled) return { viewline: null, source: null };
  if (shared.source === 'forecast' && !includeForecast) return { viewline: null, source: null };
  return { viewline: shared.viewline, source: shared.source };
}
