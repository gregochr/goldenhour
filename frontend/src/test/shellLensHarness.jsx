import React from 'react';
import { vi } from 'vitest';
import { render } from '@testing-library/react';
import WindowFirstShell from '../components/WindowFirstShell.jsx';
import { registerMapDoor } from './shellMapDoor.js';

/**
 * The minimum a lens-focused shell test needs: an empty briefing context with both lenses, observers that record
 * themselves so a test can drive them by hand, and the bare shell render. Shared so `WindowFirstShellLensPastWeek.test.jsx`
 * does not carry a copy of the boilerplate its sibling shell tests each hold.
 */

/** The briefing context value: no cards, no places, a 45-minute reach tier and no rating floor. */
export const lensShellCtx = (today = '2026-08-08') => ({
  briefing: { generatedAt: `${today}T12:00:00`, hotTopics: [] },
  loading: false,
  heatStripCards: [],
  heatSpots: [],
  heatPointSets: new Map(),
  windowCards: [],
  paneItems: [],
  upcomingEvents: [],
  travelDayDates: new Set(),
  evaluationScores: new Map(),
  scoreIndex: new Map(),
  reachById: new Map(),
  todayStr: today,
  tomorrowStr: '2026-08-09',
  homePlace: 'Newcastle',
  isPro: true,
  isLiteUser: false,
  reachLens: {
    tier: { id: '45', label: '45 min', limitMinutes: 45 },
    tierId: '45',
    defaultTier: { id: '45', label: '45 min', limitMinutes: 45 },
    defaultTierId: '45',
    weekend: false,
    overridden: false,
    locked: false,
    selectTier: vi.fn(),
    resetToDefault: vi.fn(),
  },
  ratingLens: {
    floor: { id: 'any', min: null, label: 'Any rating' },
    floorId: 'any',
    minRating: null,
    selectFloor: vi.fn(),
  },
});

/**
 * Stubs IntersectionObserver (each instance pushes itself onto {@code observers}) and ResizeObserver (inert).
 *
 * @param {Array} observers the array each IntersectionObserver records itself in
 */
export function stubLensObservers(observers) {
  vi.stubGlobal('IntersectionObserver', class {
    constructor(callback, options) {
      this.callback = callback;
      this.options = options;
      observers.push(this);
    }

    observe(element) { this.element = element; }

    disconnect() {}
  });
  vi.stubGlobal('ResizeObserver', class {
    observe() {}

    unobserve() {}

    disconnect() {}
  });
}

/** Renders the shell with no locations and the shared map door. */
export const renderLensShell = () => render(
  <WindowFirstShell
    onOpenSettings={vi.fn()}
    onSignOut={vi.fn()}
    onShowOnMap={vi.fn()}
    locations={[]}
    registerOpenMap={registerMapDoor}
  />,
);
