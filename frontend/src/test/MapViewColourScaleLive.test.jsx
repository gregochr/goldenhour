/**
 * Stage 6 (heat-scale-unification-plan.md) is the first time anything in the running app calls
 * `scoreRamp.setMode('temp')` from a live control. Before that, `MapView`'s marker icon cache
 * (`markerIconCache`, keyed by name + scores + flags) and `MapView` itself (wrapped in
 * `React.memo`, and never unmounted once the Map pane has been visited — see `WindowFirstMapPane`)
 * both silently assumed the active ramp never changed for the life of the tab. Neither assumption
 * held once Stage 6 shipped a live switch:
 *
 * - the cache key carried nothing that changed when only the mode did, so a marker built under the
 *   old scale would be served back unchanged forever;
 * - `React.memo`'s shallow prop comparison skips a re-render when none of MapView's own props
 *   change, and nothing else in its normal prop set changes when only the colour preference does.
 *
 * These tests pin the fix: the cache key now reads `scoreRamp.getMode()` directly (the same call
 * `rampHex` itself makes, so the key can never disagree with the colour actually painted), and
 * `mapColourScale` is a genuine prop whose CHANGING VALUE — not its content, which is deliberately
 * never read — is enough to break `React.memo`'s shallow prop compare and let a re-render happen
 * at all. A caller must still flip the real ramp via `scoreRamp.setMode()` (as `App.jsx`'s
 * `loadHomeCoords` does) alongside passing a new `mapColourScale` value; the prop only unblocks
 * the render, it does not itself select a colour.
 */
import React from 'react';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { setMode, getMode } from '../utils/scoreRamp.js';

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
vi.mock('leaflet.markercluster/dist/MarkerCluster.css', () => ({}));

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
vi.mock('react-leaflet-cluster', () => ({ default: ({ children }) => <div>{children}</div> }));

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

/** The most recent divIcon call whose html names `locationName`. */
function iconFor(locationName) {
  const calls = divIconCalls.filter((o) => o?.html?.includes(`title="${locationName}"`));
  return calls.at(-1);
}

// The module-level markerIconCache survives every render in this file, so each test needs its own
// location name — otherwise a later test's render could be a cache HIT from an earlier one and
// prove nothing about the mode that produced it.
let suffix = 0;
function uniqueName() {
  suffix += 1;
  return `Colour Scale Test ${suffix}`;
}

beforeEach(() => {
  divIconCalls.length = 0;
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
  it('a mapColourScale prop change on an already-mounted, memoised MapView produces a fresh, differently-coloured icon', () => {
    // Mirrors what App.jsx's loadHomeCoords actually does: flip the real ramp AND pass a new
    // mapColourScale value together. The prop's own content is never read (see MapView's own
    // comment) — only ITS CHANGE matters, to break React.memo's shallow prop compare.
    setMode('verdict');
    const name = uniqueName();
    const { rerender } = render(
      <MapView
        locations={[makeLocation(name, 54.8)]}
        date={TODAY}
        autoEventType={null}
        mapColourScale="verdict"
      />,
    );
    const verdictIcon = iconFor(name);
    expect(verdictIcon).toBeTruthy();
    const callsAfterFirstRender = divIconCalls.length;

    setMode('temp');
    rerender(
      <MapView
        locations={[makeLocation(name, 54.8)]}
        date={TODAY}
        autoEventType={null}
        mapColourScale="temp"
      />,
    );

    // React.memo must not have swallowed this: the prop changed, so a second call to L.divIcon
    // should exist for the same marker — a bare re-render with the same props would leave the
    // call count unchanged, which is exactly the bug this pins.
    expect(divIconCalls.length).toBeGreaterThan(callsAfterFirstRender);
    const tempIcon = iconFor(name);
    expect(tempIcon).toBeTruthy();
    expect(tempIcon).not.toBe(verdictIcon);
    // Different ramp, same rating → a different fill colour embedded in the SVG. Comparing the
    // whole html catches the cache silently returning the OLD icon (same content) as well as no
    // repaint happening at all.
    expect(tempIcon.html).not.toEqual(verdictIcon.html);
  });

  it('falls back to the live scoreRamp mode when no mapColourScale prop is given', () => {
    // The overlay's MapView mount never passes this prop — it always mounts fresh, so reading
    // getMode() directly is correct there. Pin that the fallback actually reads the CURRENT mode,
    // not a stale default baked in at import time.
    const name = uniqueName();
    setMode('temp');
    render(
      <MapView locations={[makeLocation(name, 54.8)]} date={TODAY} autoEventType={null} />,
    );
    const tempIcon = iconFor(name);
    expect(tempIcon).toBeTruthy();

    setMode('verdict');
    const otherName = uniqueName();
    render(
      <MapView locations={[makeLocation(otherName, 55.0)]} date={TODAY} autoEventType={null} />,
    );
    const verdictIcon = iconFor(otherName);
    expect(verdictIcon).toBeTruthy();
    expect(verdictIcon.html).not.toEqual(tempIcon.html);
    expect(getMode()).toBe('verdict');
  });
});

/**
 * Stage 7's one-time "colours changed" notice — the only part of the default flip that reaches a
 * reader who was misreading the old map (heat-scale-unification-plan.md, Stage 7). It needs BOTH
 * signals true to show: the stored preference was never chosen (`colourScaleDefaulted`) AND the
 * live ramp is actually temp (`getMode()`) — see `MapView`'s own comment on `showColourScaleNotice`
 * for why the second check exists on top of the first.
 */
describe('MapView\'s Stage 7 "colours changed" notice', () => {
  beforeEach(() => {
    localStorage.clear();
  });

  it('shows when the preference was never chosen and the live ramp is temp', () => {
    setMode('temp');
    const name = uniqueName();
    render(
      <MapView
        locations={[makeLocation(name, 54.8)]}
        date={TODAY}
        autoEventType={null}
        colourScaleDefaulted
      />,
    );
    expect(screen.getByTestId('colour-scale-notice')).toBeInTheDocument();
    expect(screen.getByText('Colours now run cold to hot.')).toBeInTheDocument();
  });

  it('stays hidden for an explicit choice — colourScaleDefaulted is false', () => {
    // The reader who explicitly picked verdict or temp is not being told anything changed,
    // because for them nothing did.
    setMode('temp');
    const name = uniqueName();
    render(
      <MapView
        locations={[makeLocation(name, 54.8)]}
        date={TODAY}
        autoEventType={null}
        colourScaleDefaulted={false}
      />,
    );
    expect(screen.queryByTestId('colour-scale-notice')).toBeNull();
  });

  it('stays hidden while the live ramp is verdict, even if colourScaleDefaulted is true', () => {
    // Belt-and-braces: `colourScaleDefaulted` always resolves to a live temp ramp today (both come
    // from the same settings fetch), but the notice's own words only make sense while the ramp
    // actually IS temp — this pins that the component checks rather than assumes.
    setMode('verdict');
    const name = uniqueName();
    render(
      <MapView
        locations={[makeLocation(name, 54.8)]}
        date={TODAY}
        autoEventType={null}
        colourScaleDefaulted
      />,
    );
    expect(screen.queryByTestId('colour-scale-notice')).toBeNull();
  });

  it('dismissing hides it, persists the dismissal, and a fresh mount stays hidden', () => {
    setMode('temp');
    const name = uniqueName();
    render(
      <MapView
        locations={[makeLocation(name, 54.8)]}
        date={TODAY}
        autoEventType={null}
        colourScaleDefaulted
      />,
    );
    fireEvent.click(screen.getByTestId('colour-scale-notice-dismiss'));
    expect(screen.queryByTestId('colour-scale-notice')).toBeNull();
    expect(localStorage.getItem('colourScaleNoticeDismissed')).toBe('1');

    const otherName = uniqueName();
    render(
      <MapView
        locations={[makeLocation(otherName, 55.0)]}
        date={TODAY}
        autoEventType={null}
        colourScaleDefaulted
      />,
    );
    expect(screen.queryAllByTestId('colour-scale-notice')).toHaveLength(0);
  });

  // Several of MapView's own localStorage-backed `useState` initialisers run during the first
  // render, before any error boundary has anything mounted to catch — a storage-denied browser
  // (Safari "Block all cookies", an enterprise site-data policy) throws SecurityError on bare
  // access, and unguarded that crashes the whole app, not just this notice.
  it('a storage-denied browser does not crash on mount, and reads as "not yet dismissed"', () => {
    const getItemSpy = vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new DOMException('The operation is insecure.', 'SecurityError');
    });
    setMode('temp');
    const name = uniqueName();
    expect(() => render(
      <MapView
        locations={[makeLocation(name, 54.8)]}
        date={TODAY}
        autoEventType={null}
        colourScaleDefaulted
      />,
    )).not.toThrow();
    expect(screen.getByTestId('colour-scale-notice')).toBeInTheDocument();
    getItemSpy.mockRestore();
  });

  it('a storage-denied browser does not crash on dismiss, and still hides for the session', () => {
    setMode('temp');
    const name = uniqueName();
    render(
      <MapView
        locations={[makeLocation(name, 54.8)]}
        date={TODAY}
        autoEventType={null}
        colourScaleDefaulted
      />,
    );
    const setItemSpy = vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new DOMException('The operation is insecure.', 'SecurityError');
    });
    expect(() => fireEvent.click(screen.getByTestId('colour-scale-notice-dismiss'))).not.toThrow();
    expect(screen.queryByTestId('colour-scale-notice')).toBeNull();
    setItemSpy.mockRestore();
  });
});
