/**
 * The shared harness of the glance-to-map G1 Map tests (not a test file): the render of a `MapView` over the shared
 * fixtures inside a `WarningsProvider`, and the per-test reset. The caller registers its own `vi.mock` for
 * `../api/warningsApi.js` (and any other mock) and passes the `MapView` it imported, so a test that imports the view
 * late (the real-sheet file) uses the same helper.
 *
 * <p>Imports `mapViewMocks.jsx` first, which registers the Leaflet and network mocks; a test imports this module
 * before `MapView`.
 */
import React from 'react';
import { afterEach, beforeEach, vi } from 'vitest';
import { act, render } from '@testing-library/react';
import { mapViewState } from './mapViewMocks.jsx';
import { getWarnings } from '../api/warningsApi.js';
import { WarningsProvider } from '../context/WarningsContext.jsx';
import { buildRegionVerdictIndex } from '../utils/mapVerdict.js';
import { response } from './warningsFixture.js';
import {
  TODAY, TOMORROW, AMBER, DAYS, heatProp, locations, cardsByKey,
} from './mapViewGlanceFixtures.js';

// Registered here so every importing test file gets them (a `vi.mock` in an imported module applies to the file's
// whole module graph, as `mapViewMocks.jsx` relies on): the warnings feed, and a callout that only names its place.
vi.mock('../api/warningsApi.js', () => ({ getWarnings: vi.fn() }));
vi.mock('../components/map/MapCallout.jsx', () => ({
  default: ({ location }) => <b data-testid="probe-callout-name">{location?.name}</b>,
}));

/**
 * Renders the Map on tonight's sunset with the shared fixtures; {@code props} override any of them.
 *
 * @param {{onOpenList: ?Function}} door the shell's List door stand-in, from {@link installGlanceHooks}
 * @param {object} [props] overrides for the view's props
 * @returns {Promise<object>} the render result plus {@code again(extra)}, which re-renders with extra props
 */
async function renderGlanceMap(door, props = {}) {
  // Imported on first use, so a test that sets `mapViewState.realSheets` first gets the real `BottomSheet`.
  const { default: MapView } = await import('../components/MapView.jsx');
  let result;
  getWarnings.mockResolvedValue(response([AMBER]));
  const locs = locations();
  const element = (extra = {}) => (
    <WarningsProvider>
      <MapView
        locations={locs}
        date={TODAY}
        forecastDates={[TODAY, TOMORROW]}
        autoEventType="SUNSET"
        heat={heatProp()}
        regionVerdictIndex={buildRegionVerdictIndex(DAYS)}
        windowCardsByKey={cardsByKey()}
        onOpenList={door.onOpenList}
        {...props}
        {...extra}
      />
    </WarningsProvider>
  );
  await act(async () => { result = render(element()); });
  return { ...result, again: (extra) => act(async () => { result.rerender(element(extra)); }) };
}

/**
 * Installs the per-test hooks for a Map test file: a Pro reader on desktop (or a phone), a fresh warnings mock, the
 * clock at the fixtures' morning, and a fresh List door that opens. Call once at the top of the file.
 *
 * @param {{isMobile?: boolean}} [options]
 * @returns {{door: {onOpenList: ?Function}, renderMap: Function}} the door (its {@code onOpenList} is replaced before
 *          every test) and {@code renderMap(props)}, which renders the Map over the shared fixtures
 */
export function installGlanceHooks({ isMobile = false } = {}) {
  const door = { onOpenList: null };
  beforeEach(() => {
    localStorage.clear();
    mapViewState.role = 'PRO_USER';
    mapViewState.isMobile = isMobile;
    getWarnings.mockReset();
    vi.useFakeTimers({ toFake: ['Date'] });
    vi.setSystemTime(new Date(`${TODAY}T09:40:00Z`));
    door.onOpenList = vi.fn(() => true);
  });
  afterEach(() => {
    vi.useRealTimers();
    localStorage.clear();
  });
  return { door, renderMap: (props) => renderGlanceMap(door, props) };
}
