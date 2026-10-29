import React, { createContext, useCallback, useMemo, useState } from 'react';
import PropTypes from 'prop-types';
import { useAuth } from './AuthContext.jsx';
import { useViewlinePoll } from '../hooks/useViewlinePoll.js';

/** The roles allowed the viewline endpoints (Pro and Admin). */
const POLLING_ROLES = new Set(['PRO_USER', 'ADMIN']);

/** The shared viewline, or null outside a provider ({@code useAuroraViewline} then polls for itself). */
export const AuroraViewlineContext = createContext(null);

/**
 * Runs the ONE aurora viewline poll that {@code AuroraBanner} and {@code MapView} share
 * (performance-review-2026-10-plan.md P3d, F16); before it each ran its own pair of requests.
 *
 * <p><b>Demand registration is a refcount.</b> A consumer's {@code useAuroraViewline(enabled)} calls
 * {@code register()} while its own predicate holds and calls the returned release when the predicate
 * goes false or the consumer unmounts. The poll is enabled while the count is above zero, so its
 * condition is the union of the consumers' predicates without this provider knowing any of them. A
 * published per-consumer "wants" flag would need an identity for each consumer and a stale flag would
 * outlive an unmount; a release function returned by the same effect that registered cannot leak.
 *
 * <p>It polls only for PRO_USER and ADMIN (an allow-list), so never for a LITE reader, whatever a consumer asks (Lite has no viewline; the banner
 * makes no viewline request). Rewind re-keys the provider above it, so a rewound session starts clean.
 *
 * @param {{children: React.ReactNode}} props
 */
export function AuroraViewlineProvider({ children }) {
  const { role } = useAuth();
  const [wanted, setWanted] = useState(0);

  const register = useCallback(() => {
    setWanted((n) => n + 1);
    return () => setWanted((n) => n - 1);
  }, []);

  // An allow-list: a null or unknown role, and Lite, never poll.
  const { viewline, source } = useViewlinePoll(wanted > 0 && POLLING_ROLES.has(role));

  const value = useMemo(() => ({ register, viewline, source }), [register, viewline, source]);
  return <AuroraViewlineContext.Provider value={value}>{children}</AuroraViewlineContext.Provider>;
}

AuroraViewlineProvider.propTypes = {
  children: PropTypes.node,
};
