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

/** The least a window card and field need for the dialog to open: no places, no regions, no map. */
const card = () => ({
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
  rows: [],
  allBadges: [],
  spots: [],
  allSpots: [{ locationId: 1 }],
  pool: [],
  reachTotal: 0,
});
const field = () => ({
  eventSummary: { targetType: 'SUNSET', regions: [], unregioned: [] },
  spots: [],
  points: [],
  windows: [],
  series: new Map(),
  reachById: new Map(),
  lens: { limitMinutes: null, tierLabel: 'Any', minRating: null, ratingLabel: 'Any rating' },
  onSelectRegion: vi.fn(),
  selectedRegion: null,
  singleRegionScope: true,
  origin: null,
  homeCoords: null,
});

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
