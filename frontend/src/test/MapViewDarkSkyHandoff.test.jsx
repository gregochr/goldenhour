/**
 * Tests for MapView's Coming up handoff effect — the `coastal-spots`/`dark-sky-spots` chronology
 * card actions (D8, plan §6b). `darkSkyFilter` is component-local `MapView` state with no handoff
 * path before this phase; the effect gives it one, and — unlike the STANDALONE `handoffFilterAction`
 * effect (still used by topic pills), which only ever turns a filter ON — it must be able to turn
 * both the dark-sky toggle AND the type filter back OFF on the very next handoff, because the same
 * `MapView` instance backing the full Map tab is never unmounted between tab visits (plan D8:
 * "copying [`handoffFilterAction`'s] shape for a boolean latches the filter permanently" — which
 * turned out to apply to the type filter too, not just the boolean: an external review pass on the
 * pushed PR (Codex) found that a coastal-spots handoff followed by a dark-sky-spots one left the
 * stale SEASCAPE filter ANDed with the new dark-sky one).
 *
 * Leaflet is stubbed exactly as `MapViewTypeFilter.test.jsx` does; one popup renders per visible
 * marker, so counting popups counts visible locations.
 *
 * ⚠️ Touched (not substantially rewritten) for map-tab-v2-plan.md §3 P6, which removed
 * `ForecastTypeSelector` from the Map TAB mount and replaced it with
 * `components/map/WindowControl.jsx`. This file's own tests never drove that selector at all —
 * `ForecastTypeSelector.jsx` is mocked to a plain, prop-less `<div/>` throughout, and every
 * assertion here is about the dark-sky/type-filter handoff effect and the Filters drawer, neither
 * of which the window control touches. So there is no old pin to replace; the one addition below
 * (`the window control coexists…`) states that explicitly instead of leaving it implicit.
 */
import React from 'react';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';

vi.mock('leaflet', () => {
  const icon = () => ({});
  const divIcon = (options) => ({ options });
  return { default: { icon, divIcon, point: (x, y) => ({ x, y }) }, icon, divIcon };
});

vi.mock('leaflet/dist/leaflet.css', () => ({}));
vi.mock('leaflet.markercluster/dist/MarkerCluster.css', () => ({}));

vi.mock('react-leaflet', () => ({
  MapContainer: ({ children }) => <div data-testid="map-container">{children}</div>,
  TileLayer: () => null,
  Marker: ({ children }) => <div>{children}</div>,
  Popup: ({ children }) => <div>{children}</div>,
  Polyline: () => null,
  useMapEvents: () => null,
  useMap: () => ({
    eachLayer: () => {},
    getContainer: () => ({ clientHeight: 500 }),
  }),
}));

vi.mock('react-leaflet-cluster', () => ({
  default: ({ children }) => <div>{children}</div>,
}));

vi.mock('../context/AuthContext.jsx', () => ({
  useAuth: () => ({ role: 'ADMIN' }),
}));
vi.mock('../hooks/useIsMobile.js', () => ({ useIsMobile: () => false }));
vi.mock('../hooks/useAuroraStatus.js', () => ({ useAuroraStatus: () => ({ status: null }) }));
vi.mock('../api/auroraApi.js', () => ({
  getAuroraLocations: vi.fn().mockResolvedValue([]),
  getAuroraForecastResults: vi.fn().mockResolvedValue([]),
  getAuroraForecastAvailableDates: vi.fn().mockResolvedValue([]),
}));
vi.mock('../components/BottomSheet.jsx', () => ({ default: ({ children }) => <div>{children}</div> }));
vi.mock('../components/MarkerPopupContent.jsx', () => ({
  default: () => <div data-testid="popup-content" />,
}));
vi.mock('../components/ForecastTypeSelector.jsx', () => ({
  default: () => <div />,
}));
vi.mock('../components/InfoTip.jsx', () => ({ default: () => null }));
vi.mock('../components/markerUtils.js', () => ({
  buildMarkerSvg: () => '<svg></svg>',
  buildStandDownSvg: () => '<svg></svg>',
  markerLabelAndColour: () => ({ label: '4★', colour: '#E5A00D' }),
  createClusterIcon: () => ({ options: { html: '', iconSize: { x: 40, y: 40 }, className: '' } }),
  STAND_DOWN_COLOUR: '#501313',
}));

import MapView from '../components/MapView.jsx';

const TODAY = '2026-01-15';

beforeEach(() => {
  vi.useFakeTimers({ toFake: ['Date'] });
  vi.setSystemTime(new Date(`${TODAY}T12:00:00Z`));
  localStorage.clear();
});

afterEach(() => {
  vi.useRealTimers();
  localStorage.clear();
});

function forecasts(rating = 4) {
  return new Map([
    [TODAY, {
      sunset: { rating, solarEventTime: `${TODAY}T18:00:00`, fierySkyPotential: 70, goldenHourPotential: 60 },
      sunrise: { rating, solarEventTime: `${TODAY}T06:00:00`, fierySkyPotential: 60, goldenHourPotential: 50 },
    }],
  ]);
}

// Bortle 3 clears the DARK_SKY_THRESHOLD (4); Bortle 7 does not; the third is unmeasured.
const LOCATIONS = [
  { name: 'Kielder', lat: 55.0, lon: -2.5, forecastsByDate: forecasts(4), locationType: ['LANDSCAPE'], bortleClass: 3 },
  { name: 'City Edge', lat: 55.1, lon: -1.6, forecastsByDate: forecasts(4), locationType: ['LANDSCAPE'], bortleClass: 7 },
  { name: 'Unmeasured', lat: 55.2, lon: -1.7, forecastsByDate: forecasts(4), locationType: ['LANDSCAPE'], bortleClass: null },
];

const visibleCount = () => screen.queryAllByTestId('popup-content').length;

describe('MapView — the dark-sky handoff (D8, plan §6b)', () => {
  it('with no handoff, every location is visible regardless of Bortle class', () => {
    render(<MapView locations={LOCATIONS} date={TODAY} autoEventType={null} />);
    expect(visibleCount()).toBe(3);
  });

  it('a truthy handoffDarkSky filters to Bortle-class-4-or-better locations', () => {
    render(
      <MapView
        locations={LOCATIONS}
        date={TODAY}
        autoEventType={null}
        handoffDarkSky
        handoffNonce={1}
      />,
    );
    expect(visibleCount()).toBe(1);
    expect(screen.getAllByTestId('popup-content')).toHaveLength(1);
  });

  it('the SAME nonce with no darkSky handoff never turns the filter on', () => {
    render(<MapView locations={LOCATIONS} date={TODAY} autoEventType={null} handoffNonce={1} />);
    expect(visibleCount()).toBe(3);
  });

  it('a later handoff explicitly carrying darkSky=false clears a previously-applied one — the '
      + '"second handoff" the plan calls out (dark-sky, then coastal, on the same never-unmounted '
      + 'pane). handoffDarkSky={false}, not omitted: the real coastal-spots trigger always sends an '
      + 'explicit boolean (mapOverlay.js: darkSky: !!trigger.darkSky), never leaves the field absent.', () => {
    const { rerender } = render(
      <MapView locations={LOCATIONS} date={TODAY} autoEventType={null} handoffDarkSky handoffNonce={1} />,
    );
    expect(visibleCount()).toBe(1);

    rerender(
      <MapView
        locations={LOCATIONS}
        date={TODAY}
        autoEventType={null}
        handoffFilterAction="SEASCAPE"
        handoffDarkSky={false}
        handoffNonce={2}
      />,
    );
    // No location here is SEASCAPE, so the type filter itself shows none — but the point of this
    // test is that the dark-sky filter no longer applies at all: toggling it back on manually
    // would need to show all three again, not just the (now zero) SEASCAPE-filtered set. Assert
    // the toggle's own rendered state instead, which is unambiguous either way.
    fireEvent.click(screen.getByTestId('advanced-filters-toggle'));
    expect(screen.getByTestId('dark-sky-filter-toggle').className).not.toContain('bg-indigo-900/40');
  });

  it('an UNRELATED handoff (handoffDarkSky omitted, not false) never clears a manually-set '
      + 'filter — found by adversarial review: handoffNonce is one counter shared by every map '
      + 'handoff in the app, not just coming-up ones', () => {
    const { rerender } = render(<MapView locations={LOCATIONS} date={TODAY} autoEventType={null} />);
    fireEvent.click(screen.getByTestId('advanced-filters-toggle'));
    fireEvent.click(screen.getByTestId('dark-sky-filter-toggle')); // reader manually turns it on
    expect(visibleCount()).toBe(1);

    // An unrelated handoff (an event-type override, e.g. from a Plan card): carries no darkSky
    // field at all (App.jsx never sets one for that trigger kind), but DOES bump the shared nonce.
    rerender(
      <MapView locations={LOCATIONS} date={TODAY} autoEventType={null} handoffEventType="SUNRISE" handoffNonce={7} />,
    );
    expect(visibleCount()).toBe(1); // still filtered — the manual toggle survived
  });

  it('a coastal-spots handoff followed by a dark-sky-spots one clears the stale type filter — '
      + 'found by external review (Codex) on the pushed PR: without this, "dark-sky spots" showed '
      + 'only dark-sky COASTAL spots, ANDed with the still-latched SEASCAPE filter', () => {
    const coastalAndDark = [
      { name: 'Coastal Dark', lat: 55.0, lon: -2.5, forecastsByDate: forecasts(4), locationType: ['SEASCAPE'], bortleClass: 3 },
      { name: 'Inland Dark', lat: 55.1, lon: -1.6, forecastsByDate: forecasts(4), locationType: ['LANDSCAPE'], bortleClass: 3 },
      { name: 'Coastal Bright', lat: 55.2, lon: -1.7, forecastsByDate: forecasts(4), locationType: ['SEASCAPE'], bortleClass: 8 },
    ];
    const { rerender } = render(
      <MapView
        locations={coastalAndDark}
        date={TODAY}
        autoEventType={null}
        handoffFilterAction="SEASCAPE"
        handoffDarkSky={false}
        handoffNonce={1}
      />,
    );
    // Coastal-spots: both SEASCAPE locations, regardless of Bortle class.
    expect(visibleCount()).toBe(2);

    rerender(
      <MapView
        locations={coastalAndDark}
        date={TODAY}
        autoEventType={null}
        handoffFilterAction={null}
        handoffDarkSky
        handoffNonce={2}
      />,
    );
    // Dark-sky-spots: both Bortle-3 locations, INCLUDING the inland (non-SEASCAPE) one — proving
    // the SEASCAPE filter from the previous handoff was cleared, not left ANDed with the new one.
    // (Were it still latched, only "Coastal Dark" would clear both filters and visibleCount() would
    // read 1, not 2 — that was the bug Codex's review found.)
    expect(visibleCount()).toBe(2);
  });

  it('re-applies on a repeat tap of the SAME action — the nonce forces the effect to re-run', () => {
    const { rerender } = render(
      <MapView locations={LOCATIONS} date={TODAY} autoEventType={null} handoffDarkSky handoffNonce={1} />,
    );
    fireEvent.click(screen.getByTestId('advanced-filters-toggle'));
    fireEvent.click(screen.getByTestId('dark-sky-filter-toggle')); // reader manually turns it off
    expect(visibleCount()).toBe(3);

    // A second dark-sky-spots tap: same darkSky value, new nonce — must re-apply, not no-op.
    rerender(
      <MapView locations={LOCATIONS} date={TODAY} autoEventType={null} handoffDarkSky handoffNonce={2} />,
    );
    expect(visibleCount()).toBe(1);
  });

  it('the window control coexists with the dark-sky handoff — a P6 addition, not an old pin', () => {
    // Stated explicitly per the file header: this suite has nothing to rewrite onto the new
    // control, since it never drove the removed `ForecastTypeSelector` in the first place. What
    // is worth ADDING is proof the two features do not fight each other on the same render — the
    // primary row now carries the window control instead of the old event selector, beside the
    // same Filters toggle this file's other tests already click.
    render(
      <MapView
        locations={LOCATIONS}
        date={TODAY}
        forecastDates={[TODAY]}
        autoEventType={null}
        handoffDarkSky
        handoffNonce={1}
      />,
    );
    expect(screen.getByTestId('wf-win-pill')).toBeInTheDocument();
    expect(visibleCount()).toBe(1); // the dark-sky handoff still applies, unaffected
  });
});
