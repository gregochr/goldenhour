/**
 * The REAL mount chain — `WindowFirstMapPane` → `MapView` → `MapLabels` → `MapCallout` — with
 * NEITHER `MapLabels` NOR `MapCallout` mocked away (map-tab-v2-plan.md §3 P9).
 *
 * <h2>Why this file exists</h2>
 *
 * <p>Every other P9 test drives one link of this chain at a time with a stand-in for its
 * neighbour: `MapCallout.test.jsx` mounts the callout directly with hand-built props;
 * `MapViewChipSelect.test.jsx`/`MapViewSelectionOrdering.test.jsx` mount `MapView` with `MapLabels`
 * (and, in the latter, `MapCallout` too) replaced by a probe. That is the right level for what each
 * of those files is actually testing — but it is exactly why a REAL regression in how the real chip
 * and the real callout interact through the real `MapView` (a chip click that reached `onSelect`
 * correctly, then had the selection wiped by the SAME click bubbling into Leaflet's own background-
 * click handler) passed every one of them and only showed up live, in a browser. This file drives
 * the chain the way `WindowFirstMapPaneHeat.test.jsx` drives `WindowFirstMapPane` itself — through
 * the real component, off a mocked `useWindowFirstBriefing` context — but, unlike that file, does
 * NOT stub `MapView` (or anything beneath it that owns the click path): a click on a REAL rendered
 * chip must produce a REAL `MapCallout` in the DOM, named for the location it was clicked for.
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
  // A REAL implementation, not a no-op stub — this is the exact fix under test
  // (`MapLabels.jsx`'s `setRootRef`), so a stubbed-away no-op would make the fix invisible to this
  // file regardless of whether it is actually present in the source, which defeats the point of a
  // real-mount-chain test.
  //
  // ⚠️ The event list is copied VERBATIM from Leaflet's own source
  // (`node_modules/leaflet/dist/leaflet-src.js`'s `disableClickPropagation`) — deliberately NOT
  // `click`. Leaflet's own map-level 'click' is a SYNTHETIC event, fired only from inside
  // `Map._handleDOMEvent` after that method's own `_isClickDisabled(el)` walk finds no ancestor
  // carrying the `_leaflet_disable_click` flag — never a raw pass-through of the native DOM 'click',
  // and never something `stopPropagation()` on a native `click` listener could intercept (Leaflet's
  // own real code never attaches one). `disableClickPropagation` therefore does two DISTINCT things,
  // both required here: it stops the native `mousedown`/`touchstart`/`dblclick`/`contextmenu` from
  // bubbling (real `stopPropagation`, below), AND it sets `_leaflet_disable_click` on the element so
  // a later native 'click' — which is deliberately left alone and keeps bubbling all the way to
  // React's own delegation, since that is what a plain HTML `<button>`'s own `onClick` depends on —
  // can still be recognised and skipped by whatever plays `Map._handleDOMEvent`'s part (the
  // `react-leaflet` mock's own `useMapEvents`, below, which walks the SAME flag on its 'click'
  // entries). An earlier cut of this mock added `click` to `STOPPED_EVENTS` "for safety" and broke
  // the chip's OWN onClick as a result — the DOM has no way to stop an event for one listener
  // (Leaflet's container-level one) while letting it reach another (React's) once
  // `stopPropagation()` has been called on it; a SECOND earlier cut set the flag but never checked
  // it anywhere, which left the map's mocked background-click handler firing unconditionally and
  // wiping the very selection the chip's click had just set — the mock itself reproducing the bug
  // this file exists to catch, rather than proving the fix.
  const STOPPED_EVENTS = ['mousedown', 'touchstart', 'dblclick', 'contextmenu'];
  const DomEvent = {
    disableClickPropagation: (el) => {
      for (const type of STOPPED_EVENTS) el.addEventListener(type, (e) => e.stopPropagation());
      el._leaflet_disable_click = true;
    },
    disableScrollPropagation: () => {},
  };
  const DomUtil = { setPosition: () => {} };
  return {
    default: {
      icon, divIcon, point, DomEvent, DomUtil,
    },
    icon,
    divIcon,
    point,
    DomEvent,
    DomUtil,
  };
});
vi.mock('leaflet/dist/leaflet.css', () => ({}));
vi.mock('leaflet.markercluster/dist/MarkerCluster.css', () => ({}));

let fakeMap;
let fakeMarker;
let fakeClusterGroup;

/**
 * A Leaflet map stubbed to what the WHOLE tab-mode chain touches — `MapLabels`' own projection/
 * pane needs (`MapLabels.test.jsx`'s `makeFullMap`) plus `MapCallout`'s (`panInside`) plus
 * `MapView`'s own helper controllers (`fitBounds`/`flyTo`/`invalidateSize`/`once`/`eachLayer`). One
 * object, because `useMap()` is mocked to return the SAME instance to every consumer, exactly as
 * react-leaflet's real context does within one `MapContainer`.
 *
 * <p>⚠️ `container` is DELIBERATELY its own node, NOT RTL's own render root (`render(ui,
 * { container })`'s target) — the opposite of what an earlier cut of this file did, and reverted
 * for a proven reason. That earlier cut reused RTL's render root as `fakeMap.container` on the
 * theory that `createPane`'s `container.appendChild(el)` needed a node React itself had rendered
 * into for the portalled chip's own `onClick` to fire — mirroring `MapLabels.test.jsx`'s own
 * `makeMap()`/`makeFullMap()` (`wrap`+`container`, always two SEPARATE nodes) reads as though it
 * would break the exact thing this file exists to prove. It does not, and reusing the RTL root
 * broke something else instead: `MapLabels`' `useState(() => map.createPane(...))` initialiser
 * calls `container.appendChild` DURING THE RENDER PHASE — before React's own first commit for the
 * whole tree — and when `container` was RTL's own render root, React's reconciler (which fully
 * owns and reconciles that SPECIFIC node's children, unlike an ordinary host descendant) removed
 * the manually-appended pane on a LATER commit. Proven empirically, not hypothetically: a chip
 * that rendered and was clickable on the FIRST paint of one test in this file became silently
 * unfindable on the SECOND `it()` in the same run (a real `MutationObserver` traced the exact
 * removal to `container.removeChild(pane)`, moments after `MapLabels`' own `setFrame` triggered a
 * second render) — a regression THIS mock produced, not one the fix under test could ever cause.
 *
 * <p>The portalled chip's own `onClick` does not need `pane` to be a DOM descendant of RTL's render
 * root at all: `react-dom`'s `HostPortal` commit (`case 4` in the work-loop's `completeWork`, this
 * version's `react-dom-client.development.js`) calls `listenToAllSupportedEvents` on the PORTAL's
 * OWN target container the first time it mounts — i.e. on `pane` itself — precisely so a portal
 * needs no DOM-ancestry relationship with the app's root for React's delegated events to reach it.
 * `screen`, bound to `document.body`, likewise finds anything connected there regardless of which
 * branch it hangs off. What DOES need to be a real DOM ancestor of `pane` is `fakeMap.container`
 * itself — `useMapEvents`' mock below attaches a NATIVE `addEventListener` directly to it, and
 * native bubbling (unlike React's own tree-based portal bubbling) follows real DOM ancestry only —
 * satisfied here since `createPane` appends `pane` straight into it, regardless of where in the
 * document `container` sits.
 */
function makeChainMap() {
  const handlers = new Map();
  const container = document.createElement('div');
  document.body.appendChild(container);
  Object.defineProperty(container, 'offsetWidth', { value: 800, configurable: true });
  const panes = {};
  return {
    panInsideCalls: [],
    flyToCalls: [],
    zoom: 9,
    container,
    getZoom() { return this.zoom; },
    getSize: () => ({ x: 800, y: 500 }),
    getContainer: () => container,
    getCenter: () => ({ lat: 55, lng: -2 }),
    getBounds: () => ({ contains: () => true }),
    latLngToContainerPoint: ([lat, lng]) => ({ x: (lng + 3) * 100, y: (56 - lat) * 100 }),
    containerPointToLayerPoint: (p) => ({ x: -40 + p[0], y: -25 + p[1] }),
    createPane: (name) => {
      const el = document.createElement('div');
      panes[name] = el;
      container.appendChild(el);
      return el;
    },
    getPane: (name) => panes[name] || null,
    on(events, fn) { for (const e of events.split(' ')) handlers.set(e, [...(handlers.get(e) || []), fn]); },
    off(events, fn) {
      for (const e of events.split(' ')) {
        handlers.set(e, (handlers.get(e) || []).filter((h) => h !== fn));
      }
    },
    once: () => {},
    eachLayer: () => {},
    fitBounds: () => {},
    invalidateSize: () => {},
    panInside(latlng, opts) { this.panInsideCalls.push([latlng, opts]); },
    flyTo(latlng, zoom) { this.flyToCalls.push([latlng, zoom]); },
  };
}

vi.mock('react-leaflet', () => ({
  MapContainer: ({ children }) => <div>{children}</div>,
  TileLayer: () => null,
  Marker: React.forwardRef(function MockMarker({ children }, ref) {
    React.useImperativeHandle(ref, () => fakeMarker);
    return <div>{children}</div>;
  }),
  Popup: ({ children }) => <div>{children}</div>,
  Polyline: () => null,
  // ⚠️ REAL native `addEventListener`s on `fakeMap.container` — not a no-op stub — because this is
  // the exact mechanism the regression this file exists to catch lived in: a plain HTML chip button
  // is a genuine DOM descendant of `fakeMap.container` (via `MapLabels`' own `createPane`, which
  // appends the pane element to the container below), so a REAL click on it genuinely bubbles here,
  // exactly as it bubbles into Leaflet's own container listener in the browser. A no-op mock (this
  // file's first cut) let a since-fixed regression pass silently, because nothing ever exercised
  // the bubble path the bug lived on. Re-subscribes every render (no dependency array) — the same
  // "always the freshest closure" behaviour react-leaflet's real `useMapEvents` gives its caller,
  // and MapView's own handlers here are fresh inline closures every render.
  //
  // ⚠️ The 'click' entry ALSO has to reproduce `Map._isClickDisabled`/`_handleDOMEvent` (leaflet-
  // src.js), not just re-dispatch the raw DOM event. `disableClickPropagation` (the `leaflet` mock
  // above) never stops the native 'click' from bubbling at all — Leaflet's own map only fires its
  // synthetic `click` for listeners AFTER walking from `e.target` up to its container checking for
  // `_leaflet_disable_click`, and bailing out if any element on that path carries it. A bare
  // `addEventListener('click', handler)`, with no such walk, would still fire the background-click
  // handler on every click reaching the container — reintroducing exactly the bug this file exists
  // to catch, but as a mock artefact rather than a real one. Every OTHER event type here (mousedown
  // included — the close-ordering snapshot in `MapViewSelectionOrdering.test.jsx` fires it directly
  // and needs no such gate) is dispatched as-is, since `disableClickPropagation` genuinely stops
  // THOSE from bubbling this far via real `stopPropagation()`.
  useMapEvents: (handlers) => {
    React.useEffect(() => {
      const target = fakeMap.container;
      const entries = Object.entries(handlers || {}).map(([eventName, handler]) => {
        if (eventName !== 'click') return [eventName, handler];
        const gated = (e) => {
          let el = e.target;
          while (el && el !== target) {
            if (el._leaflet_disable_click) return;
            el = el.parentNode;
          }
          handler(e);
        };
        return [eventName, gated];
      });
      for (const [eventName, handler] of entries) target.addEventListener(eventName, handler);
      return () => {
        for (const [eventName, handler] of entries) target.removeEventListener(eventName, handler);
      };
    });
    return fakeMap;
  },
  useMap: () => fakeMap,
}));

vi.mock('react-leaflet-cluster', () => ({
  default: React.forwardRef(function MockClusterGroup({ children }, ref) {
    React.useImperativeHandle(ref, () => fakeClusterGroup);
    return <div>{children}</div>;
  }),
}));

// Heavy/irrelevant-to-this-file children, stubbed exactly as the other MapView suites stub them —
// none of these are what this file is about, and the real ones (`MapHeatLayer` in particular) pull
// in `d3-geo` and canvas work no jsdom run needs for a wiring proof.
vi.mock('../components/MapHeatLayer.jsx', () => ({ default: () => <div data-testid="map-heat-layer" /> }));
vi.mock('../components/MarkerPopupContent.jsx', () => ({ default: () => <div /> }));
vi.mock('../components/BottomSheet.jsx', () => ({ default: ({ children }) => <div>{children}</div> }));
vi.mock('../components/InfoTip.jsx', () => ({ default: () => null }));
vi.mock('../components/AuroraViewlineOverlay.jsx', () => ({ default: () => null }));
vi.mock('../context/AuthContext.jsx', () => ({ useAuth: () => ({ role: 'PRO_USER' }) }));
vi.mock('../hooks/useIsMobile.js', () => ({ useIsMobile: () => false }));
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

let briefingValue = null;
vi.mock('../context/WindowFirstBriefingContext.jsx', () => ({
  useWindowFirstBriefing: () => briefingValue,
}));

import WindowFirstMapPane from '../components/WindowFirstMapPane.jsx';

const TODAY = '2026-08-11';
const SPOT_ID = 1;
const SPOT_NAME = 'Bamburgh Beach';

const HEAT_SPOTS = [{
  id: SPOT_ID, name: SPOT_NAME, lat: 55.61, lng: -1.71, regionName: 'North East', rid: 'North East', bortleClass: 4, scores: [5],
}];

const HEAT_STRIP_CARDS = [{
  key: `${TODAY}:SUNSET`, date: TODAY, targetType: 'SUNSET', label: 'Tonight sunset', time: '19:53', bestRating: 5, confidence: 'high', badges: [],
}];

const REACH = new Map([[SPOT_ID, { driveMinutes: 42, distanceMiles: 10 }]]);

function briefingContext(overrides = {}) {
  return {
    heatSpots: HEAT_SPOTS,
    heatPointSets: new Map([[`${TODAY}:SUNSET`, []]]),
    heatStripCards: HEAT_STRIP_CARDS,
    reachById: REACH,
    effectiveReachById: REACH,
    homePlace: 'Newcastle upon Tyne',
    todayStr: TODAY,
    origin: null,
    scoreRows: [],
    scoresLoaded: true,
    briefing: { days: [] },
    ...overrides,
  };
}

function makeLocation() {
  return {
    id: SPOT_ID,
    name: SPOT_NAME,
    lat: 55.61,
    lon: -1.71,
    regionName: 'North East',
    bortleClass: 4,
    locationType: ['SEASCAPE'],
    forecastsByDate: new Map([[TODAY, {
      sunset: { rating: 5, solarEventTime: `${TODAY}T19:53:00`, fierySkyPotential: 70, goldenHourPotential: 60 },
    }]]),
  };
}

async function renderPane(props = {}) {
  briefingValue = briefingContext();
  // RTL's OWN render root — its OWN node, separate from `fakeMap.container` (see `makeChainMap`'s
  // class doc for why the two must never be the same node). `fakeMap` has to exist before the
  // first render commits `MapLabels`' own `useState(() => map.createPane(...))` initialiser, which
  // runs during render, before any effect — so it is built ahead of the `render` call, not after.
  const rtlRoot = document.createElement('div');
  document.body.appendChild(rtlRoot);
  fakeMap = makeChainMap();
  let result;
  await act(async () => {
    result = render(
      <WindowFirstMapPane
        locations={[makeLocation()]}
        dates={[TODAY]}
        selectedDate={TODAY}
        onSelectDate={vi.fn()}
        autoEventType="SUNSET"
        {...props}
      />,
      { container: rtlRoot },
    );
  });
  return result;
}

/** `MapLabels` renders nothing until the browser has laid out a real box for each candidate
 * (jsdom's own `offsetWidth`/`offsetHeight` default to 0) — the same stub `MapLabels.test.jsx`/
 * `MapCallout.test.jsx` each apply for the identical reason, combined here since this file drives
 * both real components at once. */
function withMeasuredLayout(width, height) {
  const w = Object.getOwnPropertyDescriptor(HTMLElement.prototype, 'offsetWidth');
  const h = Object.getOwnPropertyDescriptor(HTMLElement.prototype, 'offsetHeight');
  Object.defineProperty(HTMLElement.prototype, 'offsetWidth', { configurable: true, get: () => width });
  Object.defineProperty(HTMLElement.prototype, 'offsetHeight', { configurable: true, get: () => height });
  return () => {
    if (w) Object.defineProperty(HTMLElement.prototype, 'offsetWidth', w);
    else delete HTMLElement.prototype.offsetWidth;
    if (h) Object.defineProperty(HTMLElement.prototype, 'offsetHeight', h);
    else delete HTMLElement.prototype.offsetHeight;
  };
}

let restoreLayout;
beforeEach(() => {
  // `fakeMap` is built inside `renderPane` itself, once RTL's own render container exists — see
  // that function's own note on why the ordering matters.
  fakeMap = null;
  fakeMarker = { openPopup: vi.fn() };
  fakeClusterGroup = { zoomToShowLayer: vi.fn() };
  restoreLayout = withMeasuredLayout(90, 22);
  localStorage.clear();
});

afterEach(() => {
  restoreLayout();
  briefingValue = null;
  localStorage.clear();
  vi.clearAllMocks();
});

describe('the real chain — WindowFirstMapPane → MapView → MapLabels → MapCallout', () => {
  it('renders a real, clickable location chip for the seeded location', async () => {
    await renderPane();
    const chip = await screen.findByRole('button', { name: new RegExp(SPOT_NAME) });
    expect(chip).toBeInTheDocument();
  });

  it('a chip click produces a REAL MapCallout in the DOM, named for the clicked location', async () => {
    await renderPane();
    const chip = await screen.findByRole('button', { name: new RegExp(SPOT_NAME) });

    await act(async () => { fireEvent.click(chip); });

    const callout = await screen.findByTestId('map-callout');
    expect(callout).toHaveTextContent(SPOT_NAME);
    // The regression this file exists to catch: the selection surviving the SAME click, not just
    // existing for one render before something else (`MapBackgroundClickController`, most recently)
    // silently clears it back out.
    expect(screen.getByTestId('map-callout')).toBeInTheDocument();
  });

  it('the selection ring appears alongside the callout', async () => {
    await renderPane();
    const chip = await screen.findByRole('button', { name: new RegExp(SPOT_NAME) });
    await act(async () => { fireEvent.click(chip); });

    expect(await screen.findByTestId('map-selection-ring')).toBeInTheDocument();
  });

  it('never mounts a Leaflet Popup for the click — the callout is the tab\'s only selection surface', async () => {
    await renderPane();
    const chip = await screen.findByRole('button', { name: new RegExp(SPOT_NAME) });
    await act(async () => { fireEvent.click(chip); });

    await screen.findByTestId('map-callout');
    expect(fakeMarker.openPopup).not.toHaveBeenCalled();
  });
});
