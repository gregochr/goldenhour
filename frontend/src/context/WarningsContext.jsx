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

/** What every consumer reads until the first answer, outside a provider, and when the feature is off. */
export const NO_WARNINGS = Object.freeze({
  enabled: false,
  stale: false,
  lastCheckedAt: null,
  warnings: Object.freeze([]),
  byId: new Map(),
  metOfficeUrl: DEFAULT_MET_OFFICE_URL,
  attribution: DEFAULT_ATTRIBUTION,
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
  const requestedRef = useRef(0);
  const appliedRef = useRef(0);

  const fetchWarnings = useCallback(async () => {
    requestedRef.current += 1;
    const request = requestedRef.current;
    try {
      const data = await getWarnings();
      if (request < appliedRef.current) return;
      appliedRef.current = request;
      setResponse(data);
    } catch {
      // Transient: keep what is on screen. The next poll is the retry.
    }
  }, []);

  useVisiblePoll(fetchWarnings, WARNINGS_POLL_MS);

  const value = useMemo(() => {
    if (!response || typeof response !== 'object' || response.enabled !== true) return NO_WARNINGS;
    const warnings = orderWarnings(response.warnings);
    return {
      enabled: true,
      stale: response.stale === true,
      lastCheckedAt: response.lastCheckedAt ?? null,
      warnings,
      byId: new Map(warnings.map((w) => [w.warningId, w])),
      metOfficeUrl: response.metOfficeUrl || DEFAULT_MET_OFFICE_URL,
      attribution: response.attribution || DEFAULT_ATTRIBUTION,
    };
  }, [response]);

  return <WarningsContext.Provider value={value}>{children}</WarningsContext.Provider>;
}

WarningsProvider.propTypes = {
  children: PropTypes.node,
};

/**
 * The shared warnings. Outside a provider, before the first answer and with the feature off it is
 * {@link NO_WARNINGS}, so every consumer degrades to showing nothing.
 *
 * @returns {{enabled: boolean, stale: boolean, lastCheckedAt: ?string, warnings: object[], byId: Map,
 *            metOfficeUrl: string, attribution: string}}
 */
export function useWarnings() {
  return useContext(WarningsContext);
}
