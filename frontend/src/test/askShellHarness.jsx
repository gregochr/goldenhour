import React from 'react';
import { vi } from 'vitest';
import { render } from '@testing-library/react';
import WindowFirstShell from '../components/WindowFirstShell.jsx';
import { AskProvider } from '../context/AskContext.jsx';
import * as briefingContext from '../context/WindowFirstBriefingContext.jsx';
import { getAskSettings } from '../api/askApi.js';
import { installViewport } from './askViewport.js';
import { briefing, SALTBURN, settings, WHITBY } from './askFixtures.js';

/**
 * The Plan shell with Ask mounted around it, the way `App.jsx` mounts it (F1b).
 *
 * <p>Shared by `AskShellEntry.test.jsx` and `AskShellDialogs.test.jsx`, which are split only so each
 * file's first test pays the lazy-boundary cost once and neither runs long. The briefing context is
 * spied (the shell suites' idiom) and carries the Ask fixtures' briefing, so the real
 * {@code AskProvider} joins picks against real days; every API call is a mock the caller sets up
 * (`askApi.js` is mocked in the calling file — `vi.mock` is hoisted per file and cannot live here).
 *
 * <p>{@code renderAskShell} installs a width-aware `matchMedia` ({@link installViewport}); a test names
 * the width it means and the band follows from the code under test, not from this helper.
 */

export const TODAY = '2026-10-05';

const card = () => ({
  key: `${TODAY}:SUNSET`,
  date: TODAY,
  targetType: 'SUNSET',
  lead: true,
  kicker: 'Tonight',
  when: 'Sunset',
  time: '17:41',
  verdict: 'WORTH_IT',
  verdictLabel: 'Worth it',
  bestRating: 5,
  confidence: 'high',
  badges: [],
  rows: [],
  pick: null,
  spots: [],
  allSpots: [],
  reachTotal: 0,
});

/** What the shell reads from the briefing provider, with the Ask fixtures' briefing in it. */
export const shellCtx = (overrides = {}) => {
  const cards = overrides.windowCards ?? [card()];
  return {
    briefing: { ...briefing(), hotTopics: [] },
    loading: false,
    heatStripCards: [],
    heatSpots: [],
    heatPointSets: new Map(),
    windowCards: cards,
    paneItems: cards.map((c) => ({ kind: 'card', key: c.key, card: c })),
    upcomingEvents: [],
    travelDayDates: new Set(),
    evaluationScores: new Map(),
    scoreIndex: new Map(),
    reachById: new Map([
      [WHITBY, { locationId: WHITBY, driveMinutes: 95, distanceMiles: 60 }],
      [SALTBURN, { locationId: SALTBURN, driveMinutes: 50, distanceMiles: 35 }],
    ]),
    todayStr: TODAY,
    tomorrowStr: '2026-10-06',
    homePlace: 'Newcastle',
    isPro: false,
    isLiteUser: true,
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
    ...overrides,
  };
};

/**
 * The matrix's descriptor and catalogue, so a window card exists to press and the popup it opens has
 * something to show — the same minimum `WindowFirstShellSheet.test.jsx` uses (one card, one spot,
 * nothing that paints). Spread into {@code ctx}.
 */
export const matrixCtx = () => {
  const near = {
    key: '1', locationId: WHITBY, locationName: 'Whitby', regionName: 'North York Moors & Coast',
    rating: 4, driveMinutes: 95, distanceMiles: 60, far: false,
  };
  const far = {
    key: '2', locationId: SALTBURN, locationName: 'Saltburn', regionName: 'North York Moors & Coast',
    rating: 5, driveMinutes: 400, distanceMiles: 300, far: true,
  };
  // Two spots, one beyond the reach tier: the popup's "all spots" trigger only draws when there is
  // something the tier left out.
  return {
    windowCards: [{ ...card(), spots: [near], allSpots: [near, far], reachTotal: 2 }],
    ...matrixDescriptors(near),
  };
};

const matrixDescriptors = (near) => ({
  heatStripCards: [{
    key: `${TODAY}:SUNSET`,
    date: TODAY,
    targetType: 'SUNSET',
    dow: 'Mon',
    sunrise: false,
    label: 'Tonight Sunset',
    time: '17:41',
    verdict: 'WORTH_IT',
    verdictLabel: 'Worth it',
    pickKind: null,
    away: false,
    confidence: 'high',
    pool: [near],
    badges: [],
  }],
  heatSpots: [{
    id: WHITBY,
    name: 'Whitby',
    lat: 54.49,
    lng: -0.61,
    regionName: 'North York Moors & Coast',
    rid: 'North York Moors & Coast',
    skySubject: true,
    bortleClass: 3,
    scores: [4],
  }],
});

/** A stand-in Map pane: the shell gives a tab to whatever pane it is handed. */
export const MAP_PANE = <div data-testid="map-pane-stub">map</div>;
export const OPERATIONS_PANE = <div data-testid="operations-pane-stub">operations</div>;

/**
 * Renders the shell under a real {@code AskProvider}.
 *
 * @param {object} [options]
 * @param {number} [options.width=390] the viewport width in CSS px
 * @param {object} [options.ctx] overrides for the briefing context the shell reads
 * @param {object} [options.props] overrides for the shell's own props
 * @param {object|false} [options.askSettings] what `GET /api/user/settings/ask` answers; `false` leaves
 *        the mock as the caller set it
 * @returns the RTL render result plus {@code viewport} (to resize) and {@code rerenderShell} (new props)
 */
export function renderAskShell({
  width = 390, ctx = {}, props = {}, askSettings = settings(),
} = {}) {
  const viewport = installViewport(width);
  vi.spyOn(briefingContext, 'useWindowFirstBriefing').mockReturnValue(shellCtx(ctx));
  if (askSettings !== false) getAskSettings.mockResolvedValue(askSettings);
  const baseProps = {
    onOpenSettings: vi.fn(),
    onSignOut: vi.fn(),
    onShowOnMap: vi.fn(),
    locations: [],
    ...props,
  };
  const ui = (next = {}) => (
    <AskProvider>
      <WindowFirstShell {...baseProps} {...next} />
    </AskProvider>
  );
  const view = render(ui());
  return {
    ...view,
    viewport,
    props: baseProps,
    rerenderShell: (next) => view.rerender(ui(next)),
  };
}
