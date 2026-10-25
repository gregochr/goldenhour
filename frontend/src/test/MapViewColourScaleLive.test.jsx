/**
 * Stage 6 (heat-scale-unification-plan.md) is the first time anything in the running app calls
 * `scoreRamp.setMode('temp')` from a live control. `MapView` (wrapped in `React.memo`, and never
 * unmounted once the Map pane has been visited — see `WindowFirstMapPane`) silently assumed the
 * active ramp never changed for the life of the tab, and that assumption did not hold once Stage 6
 * shipped a live switch: `React.memo`'s shallow prop comparison skips a re-render when none of
 * MapView's own props change, and nothing else in its normal prop set changes when only the colour
 * preference does.
 *
 * These tests pin the fix: `mapColourScale` is a genuine prop whose CHANGING VALUE — not its content,
 * which is deliberately never read — is enough to break `React.memo`'s shallow prop compare and let a
 * re-render happen at all. It is forwarded to the heat layer as its paint-dependency key, so that
 * forwarded prop is what is observed here: the field's bitmap is memoised on data that does not change
 * when only the preference does. A caller must still flip the real ramp via `scoreRamp.setMode()` (as
 * `App.jsx`'s `useReaderSettings` does) alongside passing a new `mapColourScale` value; the prop only
 * unblocks the render, it does not itself select a colour.
 *
 * (The map's per-location medallions used to be the probe here, through a cache key that read
 * `getMode()`. They are gone; so is the "falls back to the live mode" case, whose only observable was
 * a medallion's fill.)
 */
import React from 'react';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import { setMode } from '../utils/scoreRamp.js';

vi.mock('leaflet', () => {
  const icon = () => ({});
  const divIcon = (options) => ({ options });
  return { default: { icon, divIcon, point: (x, y) => ({ x, y }) }, icon, divIcon };
});
vi.mock('leaflet/dist/leaflet.css', () => ({}));

vi.mock('react-leaflet', () => ({
  MapContainer: ({ children }) => <div>{children}</div>,
  TileLayer: () => null,
  Popup: ({ children }) => <div>{children}</div>,
  Polyline: () => null,
  useMapEvents: () => null,
  useMap: () => ({
    eachLayer: () => {},
    getContainer: () => ({ clientHeight: 500 }),
    getZoom: () => 9,
    flyTo: () => {},
    fitBounds: () => {},
  }),
}));

vi.mock('../context/AuthContext.jsx', () => ({ useAuth: () => ({ role: 'ADMIN' }) }));
vi.mock('../hooks/useIsMobile.js', () => ({ useIsMobile: () => false }));
vi.mock('../hooks/useAuroraStatus.js', () => ({ useAuroraStatus: () => ({ status: null }) }));
vi.mock('../api/auroraApi.js', () => ({
  getAuroraLocations: vi.fn().mockResolvedValue([]),
  getAuroraForecastResults: vi.fn().mockResolvedValue([]),
  getAuroraForecastAvailableDates: vi.fn().mockResolvedValue([]),
}));
vi.mock('../components/BottomSheet.jsx', () => ({ default: ({ children }) => <div>{children}</div> }));
vi.mock('../components/MarkerPopupContent.jsx', () => ({ default: () => <div data-testid="popup-content" /> }));
vi.mock('../components/InfoTip.jsx', () => ({ default: () => null }));
// `MapLabels` and the heat layer need a real Leaflet pane (their own tests' job). The heat layer is a
// probe recording the props `MapView` hands it, `colourMode` among them.
vi.mock('../components/map/MapLabels.jsx', () => ({ default: () => <div data-testid="map-labels-stub" /> }));
const heatLayerProps = [];
vi.mock('../components/MapHeatLayer.jsx', () => ({
  default: (props) => { heatLayerProps.push(props); return <div data-testid="map-heat-layer" />; },
}));

import MapView from '../components/MapView.jsx';

const TODAY = '2026-01-15';

function makeLocation(name, lat) {
  return {
    name,
    lat,
    lon: -1.7,
    locationType: ['LANDSCAPE'],
    forecastsByDate: new Map([[TODAY, {
      sunset: { rating: 4, solarEventTime: `${TODAY}T18:00:00`, fierySkyPotential: 70, goldenHourPotential: 60 },
      sunrise: { rating: 4, solarEventTime: `${TODAY}T06:00:00`, fierySkyPotential: 60, goldenHourPotential: 50 },
    }]]),
  };
}

/** A roster join, so the heat field is offered and the heat layer mounts. */
function heatFor(locations) {
  const spots = locations.map((l) => ({ name: l.name, lat: l.lat, lng: l.lon, rid: 'North East' }));
  return {
    enabled: true,
    hasHome: false,
    spots,
    areaSpots: spots,
    pointsByKey: new Map([[`${TODAY}:SUNSET`, [{ lat: 54.8, lng: -1.7, rid: 'North East', r: [4] }]]]),
    windows: [{
      key: `${TODAY}:SUNSET`, date: TODAY, targetType: 'SUNSET', label: 'Tonight sunset', time: '18:00', bestRating: 4, conf: 1,
    }],
    areaBounds: [[54.3, -3.4], [55.7, -1.3]],
    catalogueBounds: [[54.3, -3.4], [56.4, -1.3]],
  };
}

const lastColourMode = () => heatLayerProps.at(-1)?.colourMode;

let suffix = 0;
function uniqueName() {
  suffix += 1;
  return `Colour Scale Test ${suffix}`;
}

beforeEach(() => {
  heatLayerProps.length = 0;
  vi.useFakeTimers({ toFake: ['Date'] });
  vi.setSystemTime(new Date(`${TODAY}T12:00:00Z`));
});
afterEach(() => {
  vi.useRealTimers();
  // The real scoreRamp singleton — restore it so a leftover 'temp' cannot bleed into another test
  // file running in the same process.
  setMode('verdict');
});

describe('MapView repaints when the live colour scale changes', () => {
  it('a mapColourScale prop change on an already-mounted, memoised MapView reaches the heat layer', async () => {
    // Mirrors what App.jsx's useReaderSettings actually does: flip the real ramp AND pass a new
    // mapColourScale value together. The prop's own content is never read by MapView (see its own
    // comment) — only ITS CHANGE matters, to break React.memo's shallow prop compare.
    setMode('verdict');
    const locations = [makeLocation(uniqueName(), 54.8)];
    const heat = heatFor(locations);
    const props = { locations, date: TODAY, forecastDates: [TODAY], autoEventType: null, heat };
    const { rerender } = render(<MapView {...props} mapColourScale="verdict" />);
    await screen.findByTestId('map-heat-layer');
    expect(lastColourMode()).toBe('verdict');
    const rendersAfterFirst = heatLayerProps.length;

    setMode('temp');
    rerender(<MapView {...props} mapColourScale="temp" />);

    // React.memo must not have swallowed this: the prop changed, so the heat layer is handed the new
    // key — a bare re-render with the same props would leave it on 'verdict', which is exactly the
    // bug this pins.
    expect(heatLayerProps.length).toBeGreaterThan(rendersAfterFirst);
    expect(lastColourMode()).toBe('temp');
  });
});

/** The Stage 7 "colours changed" notice was removed 2026-10-24 at the owner's request. */
describe('MapView no longer shows a "colours changed" notice', () => {
  beforeEach(() => {
    localStorage.clear();
  });

  it('renders no notice on the temp ramp with fresh storage', () => {
    setMode('temp');
    render(
      <MapView
        locations={[makeLocation(uniqueName(), 54.8)]}
        date={TODAY}
        forecastDates={[TODAY]}
        autoEventType={null}
      />,
    );
    expect(screen.queryByTestId('colour-scale-notice')).toBeNull();
    expect(screen.queryByText('Colours now run cold to hot.')).toBeNull();
  });
});
