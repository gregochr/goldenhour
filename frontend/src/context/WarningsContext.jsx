import React, { createContext, useCallback, useContext, useMemo, useRef, useState } from 'react';
import PropTypes from 'prop-types';
import { getWarnings } from '../api/warningsApi.js';
import { useVisiblePoll } from '../hooks/useVisiblePoll.js';
import { DEFAULT_ATTRIBUTION, DEFAULT_MET_OFFICE_URL, orderWarnings } from '../utils/warnings.js';

/**
 * How often the provider asks while the page is visible. The licence wants a new or cancelled warning on screen
 * within 10 minutes of the Met Office showing it; the poller adds up to 5 and the briefing memo 30 seconds, so a
 * 2-minute client poll keeps the worst case near 7.5. A repeat is a 304 (the path is ETag-revalidated).
 */
export const WARNINGS_POLL_MS = 2 * 60 * 1000;

/** While the last answer said the feature is off, ask this often (a flag flip needs a restart anyway). */
export const WARNINGS_POLL_OFF_MS = 30 * 60 * 1000;

/**
 * How long without a successful reply before the provider calls its own data stale, whatever the last reply said:
 * the licence's ten-minute bound is the reason a warning that was withdrawn must not linger unannounced on a reader
 * whose polls keep failing. It reads the real clock: it measures elapsed polling health, not the app's date.
 */
export const WARNINGS_CLIENT_STALE_MS = 10 * 60 * 1000;

/** What every consumer reads until the first answer, outside a provider, and when the feature is off. */
export const NO_WARNINGS = Object.freeze({
  enabled: false,
  stale: false,
  lastCheckedAt: null,
  warnings: Object.freeze([]),
  metOfficeUrl: DEFAULT_MET_OFFICE_URL,
  attribution: DEFAULT_ATTRIBUTION,
  // Outside a provider there is nothing to refresh: null says so (true applied a reply, false a failed request).
  refresh: () => Promise.resolve(null),
});

const WarningsContext = createContext(NO_WARNINGS);

/**
 * Fetches {@code GET /api/warnings} once and on a visible-page poll, and shares it, so the sheet rows and the map
 * callout never each ask. A failed poll keeps the last answer. Answers are applied in the order they were asked, so
 * a slow older reply cannot put back a warning a newer one withdrew.
 *
 * <p>Warnings are for every role: nothing here reads the reader's role. With the feature off the server answers
 * {@code enabled: false} and no warnings, and the value says so; consumers then print nothing (never an all-clear).
 *
 * @param {{children: React.ReactNode}} props
 */
export function WarningsProvider({ children }) {
  const [response, setResponse] = useState(null);
  // The real-clock instant of the last successful reply, and whether the client has gone without one too long.
  const lastOkAtRef = useRef(null);
  const [lastOkAt, setLastOkAt] = useState(null);
  const [clientStale, setClientStale] = useState(false);
  const requestedRef = useRef(0);
  const appliedRef = useRef(0);

  const inflightRef = useRef(null);

  const fetchOnce = useCallback(async () => {
    requestedRef.current += 1;
    const request = requestedRef.current;
    try {
      const data = await getWarnings();
      if (request < appliedRef.current) return true;
      appliedRef.current = request;
      lastOkAtRef.current = Date.now();
      setLastOkAt(lastOkAtRef.current);
      setClientStale(false);
      setResponse(data);
      return true;
    } catch {
      // Transient: keep what is on screen, but once no reply has landed for too long say so (stale line).
      if (lastOkAtRef.current != null && Date.now() - lastOkAtRef.current > WARNINGS_CLIENT_STALE_MS) {
        setClientStale(true);
      }
      return false;
    }
  }, []);

  /** One request, remembered while it is out so an on-demand refresh can join it instead of doubling up. */
  const fetchWarnings = useCallback(() => {
    const promise = fetchOnce().finally(() => {
      if (inflightRef.current === promise) inflightRef.current = null;
    });
    inflightRef.current = promise;
    return promise;
  }, [fetchOnce]);

  /**
   * Fetches now for a consumer that needs a list at least as new as its own facts, joining a request already out.
   * Resolves true when a reply was applied and false when the request failed.
   */
  const refresh = useCallback(() => inflightRef.current ?? fetchWarnings(), [fetchWarnings]);

  useVisiblePoll(fetchWarnings, response?.enabled === false ? WARNINGS_POLL_OFF_MS : WARNINGS_POLL_MS);

  const value = useMemo(() => {
    if (!response || typeof response !== 'object' || response.enabled !== true) {
      return { ...NO_WARNINGS, refresh };
    }
    const warnings = orderWarnings(response.warnings);
    return {
      enabled: true,
      stale: response.stale === true || clientStale,
      // When the client itself went quiet, the honest "last checked" is its own last good reply.
      lastCheckedAt: clientStale && lastOkAt != null
        ? new Date(lastOkAt).toISOString()
        : (response.lastCheckedAt ?? null),
      warnings,
      metOfficeUrl: response.metOfficeUrl || DEFAULT_MET_OFFICE_URL,
      attribution: response.attribution || DEFAULT_ATTRIBUTION,
      refresh,
    };
  }, [response, clientStale, lastOkAt, refresh]);

  return <WarningsContext.Provider value={value}>{children}</WarningsContext.Provider>;
}

WarningsProvider.propTypes = {
  children: PropTypes.node,
};

/**
 * The shared warnings. Outside a provider, before the first answer and with the feature off it is
 * {@link NO_WARNINGS}, so every consumer degrades to showing nothing.
 *
 * @returns {{enabled: boolean, stale: boolean, lastCheckedAt: ?string, warnings: object[],
 *            metOfficeUrl: string, attribution: string}}
 */
export function useWarnings() {
  return useContext(WarningsContext);
}
