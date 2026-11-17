/**
 * Served `GET /api/warnings` bodies for the weather-warning tests (weather-warnings-plan.md W2).
 * Instants are the wire's `Instant` form (Z-suffixed).
 */
export const METOFFICE_URL = 'https://www.metoffice.gov.uk/public/weather/warnings/';
export const ATTRIBUTION = 'Met Office Weather Warnings © Crown Copyright 2026';

/** A served `WarningDto`, with only what the chip reads unless overridden. */
export function warning(overrides = {}) {
  return {
    warningId: 'w-yellow-wind',
    warningVersion: '1.0',
    level: 'YELLOW',
    weatherTypes: ['WIND'],
    likelihood: 3,
    impact: 2,
    validFrom: '2026-08-14T12:00:00Z',
    validTo: '2026-08-16T12:00:00Z',
    headline: 'Strong winds',
    whatToExpect: [],
    updated: false,
    affectedAreas: ['NE'],
    locationIds: [7],
    ...overrides,
  };
}

/** A served `WarningsResponse`. */
export function response(warnings, overrides = {}) {
  return {
    enabled: true,
    lastCheckedAt: '2026-08-14T13:05:00Z',
    stale: false,
    warnings,
    metOfficeUrl: METOFFICE_URL,
    attribution: ATTRIBUTION,
    ...overrides,
  };
}
