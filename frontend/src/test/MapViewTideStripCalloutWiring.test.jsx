/**
 * `MapView`'s `tideStripHeight` wiring (tide-window-plan.md T7 follow-up, Codex P1 on the T7 PR).
 * Modelled directly on `MapViewSelectionOrdering.test.jsx`'s own scaffold (the fixture that gets a
 * real selection through to a mocked `MapCallout` probe) — this file's own concern is narrower and
 * orthogonal to that one: does `MapView` actually carry `MapTideStrip`'s `onHeightChange` report
 * into `MapCallout`'s `tideStripHeight` prop?
 *
 * <p>⚠️ **Desktop/tablet-only since map-mobile-sheet-plan.md §3 M3 task 5.** Through T7 there were
 * TWO call sites sharing this state (desktop/tablet nested in `.wf-map-chrome-bl`, phone as its
 * sibling) — M3 retires the phone mount outright, so `tideStripHeight` now has exactly one writer.
 * On the phone `MapCallout`'s own band reads the peek sheet's fixed `--psh` instead (§5 D-7,
 * unchanged code, wired at M2) — never a live-measured strip height, which is what this file's own
 * phone-viewport test below proves: no `MapTideStrip` mounts at all, so the state this describe
 * block is about never moves on that viewport, full stop.
 *
 * `MapCallout.test.jsx` pins that a CHANGING `tideStripHeight` prop actually retriggers that
 * component's own repaint; `MapTideStrip.test.jsx` pins that `onHeightChange` itself is called with
 * the right numbers on the right occasions. This file is the missing middle link: that `MapView`
 * really does connect the two on the ONE viewport that still has a mount, rather than merely being
 * plausible from reading the three files in isolation.
 */
import React from 'react';
import {
  describe, it, expect, vi, beforeEach, afterEach,
} from 'vitest';
import { act, render, fireEvent, screen } from '@testing-library/react';

vi.mock('leaflet', () => {
  const icon = () => ({});
  const divIcon = (options) => ({ options });
  const point = (x, y) => ({ x, y });
  return { default: { icon, divIcon, point }, icon, divIcon, point };
});
vi.mock('leaflet/dist/leaflet.css', () => ({}));

vi.mock('react-leaflet', () => ({
  MapContainer: ({ children }) => <div>{children}</div>,
  TileLayer: () => null,
  Marker: React.forwardRef(function MockMarker({ children }, ref) {
    React.useImperativeHandle(ref, () => ({ openPopup: () => {} }));
    return <div>{children}</div>;
  }),
  Popup: ({ children }) => <div>{children}</div>,
  Polyline: () => null,
  useMapEvents: () => null,
  useMap: () => ({
    eachLayer: () => {},
    getContainer: () => ({ clientHeight: 500 }),
    getZoom: () => 9,
    once: () => {},
    off: () => {},
    flyTo: () => {},
    fitBounds: () => {},
  }),
}));

vi.mock('../components/MapHeatLayer.jsx', () => ({ default: () => <div /> }));

/** The selection probe — `MapViewSelectionOrdering.test.jsx`'s own pattern: a button that selects
 * the fixture spot, so `!overlayMode && selectedLoc && activeMapEvent` goes true and the real
 * `MapCallout` mount (below, itself replaced with a probe) actually renders. */
vi.mock('../components/map/MapLabels.jsx', () => ({
  default: (props) => (
    <button type="button" data-testid="probe-chip" onClick={() => props.onSelect('Bamburgh-0')}>
      chip
    </button>
  ),
}));
vi.mock('../components/map/PinsLayer.jsx', () => ({ default: () => null }));

/** This file's own core assertion target: every `tideStripHeight` value `MapCallout` was ever
 * handed, in render order. */
const calloutTideStripHeights = [];
vi.mock('../components/map/MapCallout.jsx', () => ({
  default: (props) => {
    calloutTideStripHeights.push(props.tideStripHeight);
    return <div data-testid="probe-callout">{JSON.stringify(props.tideStripHeight)}</div>;
  },
}));

/** The strip probe — exposes the SAME `onHeightChange` prop `MapView` hands it, via a button per
 * viewport-independent test value, so a test can invoke it directly without needing a real
 * `ResizeObserver`/`offsetHeight` or a served tide fact to make the real component visible at all
 * (`MapTideStrip.test.jsx`/`MapTideStrip`'s own `onHeightChange` contract is pinned there, not
 * here — this file only proves `MapView` WIRES the callback through, the same division of labour
 * `MapViewBriefingScoreWiring.test.jsx` draws for `briefingScore`). ⚠️ **Only ONE `MapView` call
 * site renders this mock since map-mobile-sheet-plan.md §3 M3 task 5** — the desktop/tablet mount
 * nested in `.wf-map-chrome-bl`. The phone's own former sibling mount (T7 #16) is retired; on the
 * phone `mockIsMobile` below picks a viewport where this mock never renders at all. */
const tideStripCalls = [];
vi.mock('../components/map/MapTideStrip.jsx', () => ({
  default: (props) => {
    tideStripCalls.push(props);
    return (
      <button
        type="button"
        data-testid="probe-tide-strip"
        onClick={() => props.onHeightChange?.(166)}
      >
        tide strip
      </button>
    );
  },
}));

vi.mock('../context/AuthContext.jsx', () => ({ useAuth: () => ({ role: 'PRO_USER' }) }));
let mockIsMobile = false;
vi.mock('../hooks/useIsMobile.js', () => ({ useIsMobile: () => mockIsMobile }));
vi.mock('../hooks/useAuroraStatus.js', () => ({ useAuroraStatus: () => ({ status: null }) }));
vi.mock('../hooks/useAuroraViewline.js', () => ({ useAuroraViewline: () => ({ viewline: null }) }));
vi.mock('../api/auroraApi.js', () => ({
  getAuroraLocations: vi.fn().mockResolvedValue([]),
  getAuroraForecastResults: vi.fn().mockResolvedValue([]),
  getAuroraForecastAvailableDates: vi.fn().mockResolvedValue([]),
}));
vi.mock('../api/settingsApi.js', () => ({ getDriveTimes: vi.fn().mockResolvedValue({}) }));
vi.mock('../api/astroApi.js', () => ({
  getAstroConditions: vi.fn().mockResolvedValue([]),
  getAstroAvailableDates: vi.fn().mockResolvedValue([]),
}));
vi.mock('../api/travelDayApi.js', () => ({ fetchTravelDayRanges: vi.fn().mockResolvedValue([]) }));
vi.mock('../components/BottomSheet.jsx', () => ({ default: ({ children }) => <div>{children}</div> }));
vi.mock('../components/MarkerPopupContent.jsx', () => ({ default: () => <div /> }));
vi.mock('../components/InfoTip.jsx', () => ({ default: () => null }));
vi.mock('../components/AuroraViewlineOverlay.jsx', () => ({ default: () => null }));

import MapView from '../components/MapView.jsx';

const TODAY = '2026-01-15';

const SPOT = {
  id: 1, name: 'Bamburgh-0', lat: 55.61, lng: -1.71, rid: 'North East', bortleClass: 4,
};

function heatProp() {
  return {
    enabled: true,
    hasHome: false,
    spots: [SPOT],
    areaSpots: [SPOT],
    pointsByKey: new Map([[`${TODAY}:SUNSET`, [{
      id: SPOT.id, name: SPOT.name, lat: SPOT.lat, lng: SPOT.lng, rid: SPOT.rid, r: [5],
    }]]]),
    windows: [{
      key: `${TODAY}:SUNSET`, date: TODAY, targetType: 'SUNSET', label: 'Tonight sunset', time: '16:12', bestRating: 5, conf: 1,
    }],
    areaBounds: [[54.3, -3.4], [55.7, -1.3]],
    catalogueBounds: [[54.3, -3.4], [55.7, -1.3]],
  };
}

function makeLocation() {
  return {
    id: SPOT.id,
    name: SPOT.name,
    lat: SPOT.lat,
    lon: SPOT.lng,
    regionName: SPOT.rid,
    bortleClass: SPOT.bortleClass,
    locationType: ['LANDSCAPE'],
    forecastsByDate: new Map([[TODAY, {
      sunset: { rating: 5, solarEventTime: `${TODAY}T16:12:00`, fierySkyPotential: 70, goldenHourPotential: 60 },
    }]]),
  };
}

async function renderMap(props = {}) {
  let result;
  await act(async () => {
    result = render(
      <MapView
        locations={[makeLocation()]}
        date={TODAY}
        autoEventType={null}
        heat={heatProp()}
        {...props}
      />,
    );
  });
  return result;
}

async function screenFindProbe(testId) {
  await act(async () => {
    await new Promise((resolve) => { setTimeout(resolve, 0); });
  });
  const node = document.querySelector(`[data-testid="${testId}"]`);
  expect(node).toBeTruthy();
  return node;
}

async function selectTheSpot() {
  fireEvent.click(await screenFindProbe('probe-chip'));
}

beforeEach(() => {
  localStorage.clear();
  mockIsMobile = false;
  calloutTideStripHeights.length = 0;
  tideStripCalls.length = 0;
});

afterEach(() => {
  localStorage.clear();
  vi.clearAllMocks();
});

describe('MapView — tideStripHeight reaches MapCallout (T7 follow-up, Codex P1)', () => {
  it('starts at null before the strip has reported any height', async () => {
    await renderMap();
    await selectTheSpot();
    expect(screen.getByTestId('probe-callout')).toBeInTheDocument();
    expect(calloutTideStripHeights.at(-1)).toBeNull();
  });

  it('the desktop MapTideStrip mount\'s onHeightChange updates the SAME state MapCallout reads', async () => {
    await renderMap();
    await selectTheSpot();

    fireEvent.click(await screenFindProbe('probe-tide-strip'));

    expect(calloutTideStripHeights.at(-1)).toBe(166);
  });

  it('⚠️ M3: no MapTideStrip mounts on the phone at all any more — the peek sheet\'s Tide section replaced it, and MapCallout\'s phone band reads `--psh`, never this state', async () => {
    mockIsMobile = true;
    await renderMap();
    await selectTheSpot();

    // No probe to click — the assertion IS its absence. `tideStripHeight` therefore never leaves
    // the null it started at (the first test above), which is the whole point of retiring the
    // phone mount rather than merely leaving it unreachable.
    expect(document.querySelectorAll('[data-testid="probe-tide-strip"]')).toHaveLength(0);
    expect(calloutTideStripHeights.at(-1)).toBeNull();
  });

  it('exactly ONE MapTideStrip mount exists on the desktop/tablet viewport — the phone caller is gone, not merely inert', async () => {
    await renderMap();
    expect(document.querySelectorAll('[data-testid="probe-tide-strip"]')).toHaveLength(1);
  });
});

/**
 * §6 Q8 (tide-window-plan.md, decided 2026-09-18, option 2): the phone counts footer used to be
 * `display: none` while the strip was on, which also removed it from the accessibility tree —
 * fixed by an `sr-only`-shaped CSS clip instead (pinned at the cascade level by
 * `mapPhoneChromeCascade.test.jsx`). jsdom does not resolve `index.css`'s real cascade (no
 * component test here renders a `<style>` tag full of it), so what THIS file can and must prove is
 * the React/DOM half of the fix: nothing in `MapView`'s own markup hides the footer from assistive
 * tech, or stops rendering it, once `wf-tide-strip-on` is the class in force — regardless of that
 * class, which is a pure CSS hook the real `MapTideStrip` toggles via `pane.classList.add`
 * (`MapTideStrip.jsx`), simulated here directly the same way `MapCallout.test.jsx`'s own tide-strip
 * band tests build a bare DOM sibling rather than wiring up a real served tide fact.
 */
describe('MapView — phone counts footer stays in the accessibility tree while the tide strip is on (tide-window-plan.md §6 Q8, decided)', () => {
  it('is present, carries no aria-hidden on itself or an ancestor, and its count text is still reachable', async () => {
    mockIsMobile = true;
    await renderMap();

    const pane = document.querySelector('.wf-map-tab');
    expect(pane).toBeTruthy();
    pane.classList.add('wf-tide-strip-on');

    const footer = screen.getByTestId('wf-map-counts-footer');
    expect(footer).not.toHaveAttribute('aria-hidden');
    for (let node = footer.parentElement; node; node = node.parentElement) {
      expect(node).not.toHaveAttribute('aria-hidden', 'true');
    }
    // `toHaveTextContent` reads accessibility-relevant text off real DOM nodes rather than
    // trusting a static stub — exact string (not merely the denominator) so a numerator
    // regression fails here too. This file's own scope is the React/DOM half of the fix (see the
    // doc comment above); the CSS half — that the footer paints nowhere while this class is set —
    // is `mapPhoneChromeCascade.test.jsx`'s job, since jsdom never applies `index.css` here.
    expect(footer).toHaveTextContent('1 of 1 shown');
  });
});
