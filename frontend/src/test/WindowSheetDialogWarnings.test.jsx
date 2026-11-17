/**
 * The WARNING row inside the window popup (weather-warnings-plan.md W3).
 *
 * <p>What breaks if these fail: the row is not in the dialog, sits after the ratings instead of above the topic
 * rows, leaks while the flag is off, or changes the header's verdict.
 */
import React from 'react';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { act, cleanup, render, screen, within } from '@testing-library/react';

vi.mock('../api/warningsApi.js', () => ({ getWarnings: vi.fn() }));
vi.mock('../utils/heatField.js', async (importOriginal) => {
  const actual = await importOriginal();
  return {
    ...actual,
    load: vi.fn(() => Promise.resolve({ type: 'FeatureCollection', features: [] })),
    land: vi.fn(() => ({ type: 'FeatureCollection', features: [] })),
    drawGeo: vi.fn(() => ([lng, lat]) => [lng * 10, lat * 10]),
  };
});

import { getWarnings } from '../api/warningsApi.js';
import { WarningsProvider } from '../context/WarningsContext.jsx';
import WindowSheetDialog from '../components/WindowSheetDialog.jsx';
import { response, warning } from './warningsFixture.js';

const TODAY = '2026-08-20';
const KEY = `${TODAY}:SUNSET`;

/**
 * Two named locations per region, and the second of each is what separates the region LABEL from the
 * location CHIP under the stub projection.
 *
 * <p>`centroid` is the mean of a region's projected points, so with one spot per region the label
 * and the chip land on the same pixel — the greedy placer's own overlap rule then drops every chip,
 * and a test asserting their absence would be pinning the placer rather than the placement. The
 * extra spots put each centroid 100px below its chip.
 */
const HEAT_SPOTS = [
  { id: 1, name: 'Bamburgh Beach', lat: 6, lng: 4, regionName: 'Coast', rid: 'Coast', skySubject: true, scores: [5] },
  { id: 3, name: 'Craster', lat: 26, lng: 4, regionName: 'Coast', rid: 'Coast', skySubject: true, scores: [3] },
  { id: 2, name: 'Malham Cove', lat: 6, lng: 24, regionName: 'Dales', rid: 'Dales', skySubject: true, scores: [4] },
  { id: 4, name: 'Gordale', lat: 26, lng: 24, regionName: 'Dales', rid: 'Dales', skySubject: true, scores: [3] },
];

function spot(overrides = {}) {
  return {
    key: '1',
    locationId: 1,
    locationName: 'Bamburgh Beach',
    regionName: 'Coast',
    solarEventTime: `${TODAY}T20:41:00`,
    rating: 5,
    driveMinutes: 30,
    distanceMiles: 21,
    far: false,
    ...overrides,
  };
}

const NEAR = spot();
const DALES = spot({
  key: '2', locationId: 2, locationName: 'Malham Cove', regionName: 'Dales', rating: 4, driveMinutes: 55,
});

const EVENT_SUMMARY = {
  targetType: 'SUNSET',
  regions: [
    {
      regionName: 'Coast',
      displayVerdict: 'WORTH_IT',
      summary: 'A clean eastern horizon.',
      meanRating: 4.4,
      bestRating: 5,
      meanRatingDelta: 0.5,
      slots: [{ canopy: false }],
    },
    {
      regionName: 'Dales',
      displayVerdict: 'MAYBE',
      summary: 'A thin high veil.',
      meanRating: 3.1,
      bestRating: 4,
      slots: [{ canopy: false }],
    },
  ],
  unregioned: [],
};

function card(overrides = {}) {
  const spots = overrides.spots ?? [NEAR, DALES];
  return {
    key: KEY,
    date: TODAY,
    targetType: 'SUNSET',
    lead: true,
    kicker: 'Tonight',
    when: 'Sunset',
    time: '20:41',
    verdict: 'WORTH_IT',
    verdictLabel: 'Worth it',
    bestRating: 5,
    confidence: 'high',
    movement: { regionName: 'Coast', delta: 0.5 },
    pick: null,
    rows: [],
    allBadges: [],
    allSpots: spots,
    // ⚠️ Derived the way `buildWindowCards` derives it — from the ORIGIN SCOPE, not from the drawn
    // set — so a fixture that empties `spots` to model a lens gate does not accidentally also model
    // an account with no drive times. Overridable, and the no-postcode cases override it by handing
    // over an `allSpots` whose every entry is unmeasured.
    reachMeasured: (overrides.allSpots ?? spots).some((sp) => sp?.driveMinutes != null),
    pool: spots,
    bestReach: spots[0] ?? null,
    reachTotal: spots.length,
    withinReachCount: spots.length,
    ...overrides,
    spots,
  };
}

function field(overrides = {}) {
  return {
    eventSummary: EVENT_SUMMARY,
    spots: HEAT_SPOTS,
    points: [{ id: 1, name: 'Bamburgh Beach', lat: 6, lng: 4, rid: 'Coast', r: [5] }],
    windows: [{ key: KEY, dow: 'Thu', sunrise: false, label: 'Tonight Sunset', time: '20:41' }],
    series: new Map(),
    reachById: new Map(),
    lens: { limitMinutes: 45, tierLabel: '45 min', minRating: null, ratingLabel: 'Any rating' },
    onSelectRegion: vi.fn(),
    selectedRegion: null,
    singleRegionScope: false,
    origin: null,
    // Null by default — no saved postcode, the ordinary account. The field-geography plan §3.1
    // wiring: converted to `[lng, lat]` and handed to `WindowRowFieldMap` as `homePoint`.
    homeCoords: null,
    ...overrides,
  };
}


const AMBER = warning({
  warningId: 'amber-snow', level: 'AMBER', weatherTypes: ['SNOW'], locationIds: [1], likelihood: 3, impact: 3,
  headline: 'Heavy snow', whatToExpect: ['Travel delays'], validFrom: `${TODAY}T00:00:00Z`, validTo: `${TODAY}T23:59:00Z`,
});

const mount = async (served, cardOver = {}) => {
  getWarnings.mockResolvedValue(served);
  await act(async () => {
    render(
      <WarningsProvider>
        <WindowSheetDialog
          card={{ ...card(), eventIso: `${TODAY}T19:41:00`, warningIds: [], warningPlaceCounts: [], ...cardOver }}
          index={0}
          total={6}
          field={field()}
          topicIndex={new Map()}
          scopeNames={['Coast', 'Dales']}
          todayStr={TODAY}
          onClose={vi.fn()}
          onStep={vi.fn()}
        />
      </WarningsProvider>,
    );
  });
};

let originalGetContext;
beforeEach(() => {
  getWarnings.mockReset();
  originalGetContext = HTMLCanvasElement.prototype.getContext;
  HTMLCanvasElement.prototype.getContext = () => ({});
});
afterEach(() => {
  cleanup();
  HTMLCanvasElement.prototype.getContext = originalGetContext;
});

describe('the window popup warning row', () => {
  it('shows the warning in full inside the dialog, below the field and above the topic rows', async () => {
    await mount(response([AMBER]));
    const dialog = screen.getByTestId('window-sheet');
    const row = within(dialog).getByTestId('window-sheet-warnings');
    expect(within(row).getByTestId('window-warning-headline')).toHaveTextContent('Heavy snow');
    const grid = within(dialog).getByTestId('window-sheet-grid');
    expect(grid.compareDocumentPosition(row) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
  });

  it('does not touch the header\'s verdict or its rating words', async () => {
    await mount(response([]));
    const before = screen.getByTestId('window-sheet-head').textContent;
    cleanup();
    await mount(response([AMBER]));
    expect(screen.getByTestId('window-sheet-head').textContent).toBe(before);
  });

  it('shows nothing for a window no warning covers, and nothing while the feature is off', async () => {
    await mount(response([AMBER]), { placeIds: [99], allSpots: [] });
    expect(screen.queryByTestId('window-sheet-warnings')).toBeNull();
    cleanup();
    await mount({ enabled: false, warnings: [AMBER], stale: false }, { placeIds: [1] });
    expect(screen.queryByTestId('window-sheet-warnings')).toBeNull();
  });
});
