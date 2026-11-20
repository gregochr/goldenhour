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
import { vi } from 'vitest';
import { act, render } from '@testing-library/react';
import { mapViewState } from './mapViewMocks.jsx';
import { getWarnings } from '../api/warningsApi.js';
import { WarningsProvider } from '../context/WarningsContext.jsx';
import { buildRegionVerdictIndex } from '../utils/mapVerdict.js';
import { response } from './warningsFixture.js';
import {
  TODAY, TOMORROW, AMBER, DAYS, heatProp, locations, cardsByKey,
} from './mapViewGlanceFixtures.js';

export { mapViewState };

/**
 * Renders the Map on tonight's sunset with the shared fixtures; {@code props} override any of them.
 *
 * @param {Function} MapView the (memo'd) Map view
 * @param {?Function} onOpenList the shell's List door stand-in
 * @param {object} [props] overrides for the view's props
 * @returns {Promise<object>} the render result plus {@code again(extra)}, which re-renders with extra props
 */
export async function renderGlanceMap(MapView, onOpenList, props = {}) {
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
        onOpenList={onOpenList}
        {...props}
        {...extra}
      />
    </WarningsProvider>
  );
  await act(async () => { result = render(element()); });
  return { ...result, again: (extra) => act(async () => { result.rerender(element(extra)); }) };
}

/**
 * The per-test reset: a Pro reader on desktop, a fresh warnings mock, and the clock at the fixtures' morning.
 *
 * @param {{isMobile?: boolean}} [options]
 */
export function resetGlanceMap({ isMobile = false } = {}) {
  localStorage.clear();
  mapViewState.role = 'PRO_USER';
  mapViewState.isMobile = isMobile;
  getWarnings.mockReset();
  vi.useFakeTimers({ toFake: ['Date'] });
  vi.setSystemTime(new Date(`${TODAY}T09:40:00Z`));
}

/** The per-test teardown matching {@link resetGlanceMap}. */
export function restoreGlanceClock() {
  vi.useRealTimers();
  localStorage.clear();
}
