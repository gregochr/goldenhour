/**
 * The Plan tab's map overlay opens focused on ONE location: the target lifts and its neighbours
 * recede. That treatment is pure CSS (`.photocast-marker--focus` / `--muted` in MapView's
 * popupStyles), so it only exists if `makeMarkerIcon` puts the modifier on the DivIcon's
 * className. It previously computed the modifier, folded it into the icon cache key, and then
 * built every icon with the bare `photocast-marker` class — so the rules matched nothing and the
 * emphasis was inert with no test, no warning and no visible error.
 *
 * These tests assert on the className handed to `L.divIcon`, which is the only observable the
 * behaviour actually depends on.
 */
import React from 'react';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render } from '@testing-library/react';

// Capture the options of every DivIcon MapView builds.
const divIconCalls = [];
vi.mock('leaflet', () => {
  const icon = () => ({});
  const divIcon = (options) => {
    divIconCalls.push(options);
    return { options };
  };
  return { default: { icon, divIcon, point: (x, y) => ({ x, y }) }, icon, divIcon };
});
vi.mock('leaflet/dist/leaflet.css', () => ({}));

vi.mock('react-leaflet', () => ({
  MapContainer: ({ children }) => <div>{children}</div>,
  TileLayer: () => null,
  Marker: ({ children }) => <div>{children}</div>,
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

/** The className of the marker icon built for `locationName`, or undefined. */
function classNameFor(locationName) {
  const call = divIconCalls.find((o) => o?.html?.includes(`title="${locationName}"`));
  return call?.className;
}

// `makeMarkerIcon` memoises DivIcons in a module-level cache keyed by name + scores + emphasis, so
// a second render with identical props is a cache hit and never calls L.divIcon. Each test
// therefore needs its own location names, or it would assert on an empty call log.
let suffix = 0;
function names() {
  suffix += 1;
  return { target: `Copt Hill ${suffix}`, other: `Tynemouth Priory ${suffix}` };
}

function renderMap({ target, other }, overrides = {}) {
  return render(
    <MapView
      locations={[makeLocation(target, 54.8), makeLocation(other, 55.0)]}
      date={TODAY}
      autoEventType={null}
      {...overrides}
    />,
  );
}

beforeEach(() => {
  divIconCalls.length = 0;
  localStorage.clear();
  vi.useFakeTimers({ toFake: ['Date'] });
  vi.setSystemTime(new Date(`${TODAY}T12:00:00Z`));
});
afterEach(() => {
  vi.useRealTimers();
  localStorage.clear();
});

describe('MapView marker emphasis className', () => {
  it('the emphasised location carries the focus modifier', () => {
    const n = names();
    renderMap(n, { emphasiseLocationName: n.target });
    expect(classNameFor(n.target)).toBe('photocast-marker photocast-marker--focus');
  });

  it('every other location carries the muted modifier', () => {
    const n = names();
    renderMap(n, { emphasiseLocationName: n.target });
    expect(classNameFor(n.other)).toBe('photocast-marker photocast-marker--muted');
  });

  it('the Map tab (no emphasis) keeps every pin equal — bare class, no modifier', () => {
    const n = names();
    renderMap(n);
    expect(classNameFor(n.target)).toBe('photocast-marker');
    expect(classNameFor(n.other)).toBe('photocast-marker');
  });

  it('no marker is muted when the emphasised location is filtered out', () => {
    // Nothing relaxes the star threshold for a handoff, so drilling into a spot that the filter
    // hides leaves no pin to focus. Muting the survivors would dim the whole overlay to 40% in
    // deference to a marker that is not on the map.
    const n = names();
    const locations = [makeLocation(n.other, 55.0)];
    render(
      <MapView
        locations={locations}
        date={TODAY}
        autoEventType={null}
        emphasiseLocationName={`${n.target} (filtered out)`}
      />,
    );
    expect(classNameFor(n.other)).toBe('photocast-marker');
  });

  // The icon cache is keyed on emphasis for exactly this reason: without it the Map tab's plain
  // icon would be served to the overlay, or the overlay's muted icon would leak back.
  it('the same location renders a different className in the two contexts', () => {
    const n = names();
    const { unmount } = renderMap(n);
    const plain = classNameFor(n.target);
    expect(plain).toBe('photocast-marker');
    unmount();
    divIconCalls.length = 0;
    renderMap(n, { emphasiseLocationName: n.target });
    expect(classNameFor(n.target)).toBe('photocast-marker photocast-marker--focus');
    expect(classNameFor(n.target)).not.toBe(plain);
  });
});
