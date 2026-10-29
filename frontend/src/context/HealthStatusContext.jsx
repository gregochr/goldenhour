import React, { createContext, useContext } from 'react';
import PropTypes from 'prop-types';
import { useHealthStatus } from '../hooks/useHealthStatus.js';
import useAfterFirstPaint from '../hooks/useAfterFirstPaint.js';

const EMPTY_HEALTH = {
  status: null, degraded: [], checkedAt: null,
  build: null, session: null, database: null, services: null,
  appVersion: null, startedAt: null,
};

/** The full health value, {@code checkedAt} included. Read by the admin pill only. */
const HealthStatusContext = createContext(EMPTY_HEALTH);

/**
 * Whether the service is reported DOWN, as a bare boolean. A second context rather than a selector
 * on the first: a context consumer re-renders whenever its context value changes by identity, and a
 * boolean is the same value on every tick that does not flip it, so a consumer of this context is
 * untouched by {@code checkedAt} or any other field changing. (A {@code useSyncExternalStore}
 * selector would also work but needs a store, subscription and snapshot cache for one boolean.)
 */
const HealthDownContext = createContext(false);

/**
 * Owns the health SSE stream and publishes it through two contexts, so the 30-second health tick
 * re-renders the pill and not the app tree. The stream is held back until after first paint so it
 * does not compete with the critical boot fetches.
 *
 * <p>{@code children} keeps its element identity across ticks (the parent does not re-render), so
 * only context consumers re-render when the value changes.
 *
 * @param {{children: React.ReactNode}} props
 */
export function HealthStatusProvider({ children }) {
  const streamsReady = useAfterFirstPaint();
  const health = useHealthStatus(streamsReady);
  return (
    <HealthDownContext.Provider value={health.status === 'DOWN'}>
      <HealthStatusContext.Provider value={health}>
        {children}
      </HealthStatusContext.Provider>
    </HealthDownContext.Provider>
  );
}

HealthStatusProvider.propTypes = {
  children: PropTypes.node,
};

/**
 * The full health value. Re-renders on every change, {@code checkedAt} included; use
 * {@link useHealthIsDown} unless the time is shown.
 *
 * @returns {object} the health value
 */
export function useHealthStatusValue() {
  return useContext(HealthStatusContext);
}

/**
 * Whether the service is reported DOWN; re-renders only when that flips.
 *
 * @returns {boolean} true when status is DOWN
 */
export function useHealthIsDown() {
  return useContext(HealthDownContext);
}
