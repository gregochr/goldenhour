/**
 * `MapView` + the real `MapWarningChips` (weather-warnings-plan.md W4): the warning chips sit in the top-left chrome
 * group as a row of their own (never inside the hot-topics bar), follow the active window, and leave the rest of the
 * tab untouched. Leaflet and the layers that need a real Leaflet pane are mocked (`mapViewMocks.jsx`).
 */
import React from 'react';
import {
  describe, it, expect, vi, beforeEach, afterEach,
} from 'vitest';
import {
  act, render, screen, within,
} from '@testing-library/react';
import { mapViewState } from './mapViewMocks.jsx';

vi.mock('../api/warningsApi.js', () => ({ getWarnings: vi.fn() }));

import MapView from '../components/MapView.jsx';
import { getWarnings } from '../api/warningsApi.js';
import { WarningsProvider } from '../context/WarningsContext.jsx';
import { windowKey } from '../utils/windowKeys.js';
import { response, warning } from './warningsFixture.js';

const TODAY = '2026-10-22';
const NEXT = '2026-10-23';
const SPOT = {
  id: 1, name: 'Bamburgh-0', lat: 55.61, lng: -1.71, rid: 'North East', bortleClass: 4,
};
// In force on TODAY only.
const AMBER = warning({
  warningId: 'a',
  level: 'AMBER',
  weatherTypes: ['SNOW'],
  locationIds: [1],
  validFrom: `${TODAY}T00:00:00Z`,
  validTo: `${TODAY}T23:00:00Z`,
});

function heatProp() {
  const point = {
    id: SPOT.id, name: SPOT.name, lat: SPOT.lat, lng: SPOT.lng, rid: SPOT.rid, r: [5],
  };
  const windowOf = (date) => ({
    key: windowKey(date, 'SUNSET'), date, targetType: 'SUNSET', label: 'Sunset', time: '18:12', bestRating: 5, conf: 1,
  });
  return {
    enabled: true,
    hasHome: false,
    spots: [SPOT],
    areaSpots: [SPOT],
    pointsByKey: new Map([TODAY, NEXT].map((date) => [windowKey(date, 'SUNSET'), [point]])),
    windows: [windowOf(TODAY), windowOf(NEXT)],
    areaBounds: [[54.3, -3.4], [55.7, -1.3]],
    catalogueBounds: [[54.3, -3.4], [55.7, -1.3]],
    tideByWindow: new Map(),
  };
}

const rowOn = (date) => [date, { sunset: { rating: 5, solarEventTime: `${date}T17:12:00` } }];
const roster = (id = SPOT.id) => [{
  id,
  name: SPOT.name,
  lat: SPOT.lat,
  lon: SPOT.lng,
  regionName: SPOT.rid,
  bortleClass: SPOT.bortleClass,
  locationType: ['LANDSCAPE'],
  tideType: ['HIGH'],
  forecastsByDate: new Map([rowOn(TODAY), rowOn(NEXT)]),
}];

function WarnedMap({ date = TODAY, locations }) {
  return (
    <WarningsProvider>
      <MapView
        locations={locations}
        date={date}
        forecastDates={[TODAY, NEXT]}
        autoEventType="SUNSET"
        heat={heatProp()}
      />
    </WarningsProvider>
  );
}

async function renderMap(served, { locations = roster(), date = TODAY } = {}) {
  getWarnings.mockResolvedValue(served);
  let result;
  await act(async () => { result = render(<WarnedMap date={date} locations={locations} />); });
  return result;
}

beforeEach(() => {
  localStorage.clear();
  mapViewState.role = 'PRO_USER';
  mapViewState.isMobile = false;
  getWarnings.mockReset();
  vi.useFakeTimers({ toFake: ['Date'] });
  vi.setSystemTime(new Date(`${TODAY}T09:40:00Z`));
});
afterEach(() => {
  vi.useRealTimers();
  localStorage.clear();
});

describe('MapView warning chips', () => {
  it('draws the active window\'s warning in the chrome group, outside the hot-topics bar', async () => {
    await renderMap(response([AMBER]));
    const chrome = screen.getByTestId('wf-map-chrome-tl');
    const chip = within(chrome).getByTestId('warning-chip');
    expect(chip).toHaveTextContent('Amber warning of snow');
    expect(chrome.contains(screen.getByTestId('wf-win-pill'))).toBe(true);
    expect(chip.closest('[data-testid="wf-topics"]')).toBeNull();
  });

  it.each(['LITE_USER', 'ADMIN'])('shows to a %s reader too', async (role) => {
    mapViewState.role = role;
    await renderMap(response([AMBER]));
    expect(screen.getByTestId('warning-chip')).toBeInTheDocument();
  });

  it('draws the chip on the phone too, in the same group', async () => {
    mapViewState.isMobile = true;
    await renderMap(response([AMBER]));
    expect(within(screen.getByTestId('wf-map-chrome-tl')).getByTestId('warning-chip')).toBeInTheDocument();
  });

  it('follows the active window: the chip is gone on a day the warning is not in force', async () => {
    const { rerender } = await renderMap(response([AMBER]));
    expect(screen.getByTestId('warning-chip')).toBeInTheDocument();
    await act(async () => { rerender(<WarnedMap date={NEXT} locations={roster()} />); });
    expect(screen.queryByTestId('warning-chip')).toBeNull();
    await act(async () => { rerender(<WarnedMap date={TODAY} locations={roster()} />); });
    expect(screen.getByTestId('warning-chip')).toBeInTheDocument();
  });

  it('matches a roster id that arrives as a string against the served numeric locationIds', async () => {
    await renderMap(response([AMBER]), { locations: roster('1') });
    expect(screen.getByTestId('warning-chip')).toBeInTheDocument();
  });

  it('adds no geometry layer to the map when warnings are served: the chip is the whole map presence', async () => {
    await renderMap(response([AMBER]));
    expect(screen.getByTestId('warning-chip')).toBeInTheDocument();
    expect(screen.queryAllByTestId('leaflet-geometry')).toHaveLength(0);
  });
});
