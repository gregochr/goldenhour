import React from 'react';
import {
  describe, it, expect, vi, beforeEach, afterEach,
} from 'vitest';
import { act, render } from '@testing-library/react';

const setPosition = vi.fn();
vi.mock('leaflet', () => {
  const DomUtil = { setPosition: (...args) => setPosition(...args) };
  return { default: { DomUtil }, DomUtil };
});

let currentMap = null;
vi.mock('react-leaflet', () => ({ useMap: () => currentMap }));

const drawTiles = vi.fn();
const radiusFor = vi.fn(() => 120);
const load = vi.fn(() => Promise.resolve({ type: 'FeatureCollection', features: [] }));
vi.mock('../utils/heatField.js', async (importOriginal) => {
  const actual = await importOriginal();
  return {
    ...actual,
    drawTiles: (...args) => drawTiles(...args),
    radiusFor: (...args) => radiusFor(...args),
    load: (...args) => load(...args),
  };
});

import MapHeatLayer, { fadeAt, markersAreInteractive } from '../components/MapHeatLayer.jsx';

/**
 * The Leaflet host for the heat field — the pane, the throttle and the handover.
 *
 * <p><b>What this file protects.</b> Three of its rules are invisible from any screenshot and were
 * specified rather than discovered, so nothing else in the suite would catch their removal: the
 * pane's stacking order (350, between the tiles and the markers), the throttle-not-debounce split
 * across `move`/`moveend`, and the marker fade's inert band. The fourth — restoring the marker panes
 * on the way out — is what keeps the medallion view byte-identical to today, which is the claim the
 * whole side-by-side comparison rests on.
 *
 * <p>`drawTiles` and `radiusFor` are spied rather than run: their pixels are the kernel's own
 * business and `heatField.test.js` owns them. Everything else is the real module, so `clamp` (which
 * `fadeAt` is built from) is the one the app ships.
 */

const POINTS = [
  { lat: 55.6, lng: -1.7, rid: 'North East', r: [4] },
  { lat: 54.5, lng: -3.1, rid: 'The Lakes', r: [2] },
];

/**
 * A Leaflet map stubbed down to what this layer touches, with a settable zoom.
 *
 * <p>⚠️ `offsetWidth` is defined explicitly because jsdom answers 0 for every element, and the
 * layer reads it to tell "the shell has hidden my panel" from "the map is small" — Leaflet's own
 * `getSize()` cannot, since it returns a cached box. Without the stub nothing here would ever paint.
 */
function makeMap({ zoom = 9, size = { x: 800, y: 500 }, panes = null, onScreen = true } = {}) {
  const built = panes || {};
  const handlers = new Map();
  const container = document.createElement('div');
  Object.defineProperty(container, 'offsetWidth', { value: onScreen ? size.x : 0 });
  document.body.appendChild(container);
  return {
    zoom,
    handlers,
    getZoom() { return this.zoom; },
    // The settle dedupes on the VIEW, so a map that cannot answer where it is settles once and then
    // never again — which is what these tests would otherwise be asserting.
    centre: { lat: 55, lng: -2 },
    getCenter() { return this.centre; },
    getSize: () => size,
    getContainer: () => container,
    getPane: (name) => built[name] || null,
    createPane: (name) => {
      const el = document.createElement('div');
      built[name] = el;
      return el;
    },
    // Echoes its argument, so an assertion on the result can actually SEE which container point was
    // asked for. A stub returning a constant passes for `([0, 0])`, `([50, 50])` and `()` alike —
    // and the container ORIGIN is the entire rule under test.
    containerPointToLayerPoint: (p) => ({ x: -40 + p[0], y: -25 + p[1] }),
    on(events, fn) { for (const e of events.split(' ')) handlers.set(e, [...(handlers.get(e) || []), fn]); },
    off(events, fn) {
      for (const e of events.split(' ')) {
        handlers.set(e, (handlers.get(e) || []).filter((h) => h !== fn));
      }
    },
    fire(event) { for (const fn of handlers.get(event) || []) fn(); },
    panes: built,
  };
}

/** Marker panes pre-made, so the fade has something to write to from the first paint. */
function markerPanes() {
  return {
    markerPane: document.createElement('div'),
    shadowPane: document.createElement('div'),
  };
}

let frames = [];
let cancelSpy;
let originalRaf;
let originalCancel;
let originalGetContext;

beforeEach(() => {
  frames = [];
  originalRaf = global.requestAnimationFrame;
  originalCancel = global.cancelAnimationFrame;
  // A manual frame queue rather than fake timers: the throttle's whole contract is "one paint per
  // frame, scheduled by the FIRST caller", and that is only assertable if the test decides when a
  // frame runs.
  global.requestAnimationFrame = (cb) => { frames.push(cb); return frames.length; };
  cancelSpy = vi.fn();
  global.cancelAnimationFrame = (id) => { cancelSpy(id); frames[id - 1] = null; };
  originalGetContext = HTMLCanvasElement.prototype.getContext;
  HTMLCanvasElement.prototype.getContext = () => ({});
  // Re-asserted here, not left to `vi.clearAllMocks()`: that clears CALLS, not implementations, so
  // one test's `mockReturnValue` would otherwise be in force for every test after it.
  radiusFor.mockReturnValue(120);
});

afterEach(() => {
  global.requestAnimationFrame = originalRaf;
  global.cancelAnimationFrame = originalCancel;
  HTMLCanvasElement.prototype.getContext = originalGetContext;
  currentMap = null;
  vi.clearAllMocks();
});

const runFrames = () => {
  const due = frames;
  frames = [];
  for (const cb of due) if (cb) cb();
};

async function mount(props = {}) {
  let result;
  await act(async () => {
    result = render(<MapHeatLayer points={POINTS} conf={1} {...props} />);
  });
  return result;
}

describe('MapHeatLayer — the pane', () => {
  it('paints into a pane stacked between the tiles and the markers, and never over a click', async () => {
    // 350 is the whole reason for a custom pane: above the tile pane (200) so the field is on the
    // basemap, below the overlay and marker panes (400/600) so azimuth lines, the viewline, every
    // marker and every popup stay on top of it. A field above the markers is a wash over the answer.
    currentMap = makeMap({ panes: markerPanes() });
    await mount();
    const pane = currentMap.panes['wf-heat'];
    expect(pane.style.zIndex).toBe('350');
    expect(pane.style.pointerEvents).toBe('none');
  });

  it('reuses a pane the map already has rather than building a second', async () => {
    // `createPane` overwrites the map's registry with a fresh div every call, so an unguarded
    // remount (StrictMode double-invokes the initialiser) would leave the portal rendering into a
    // pane the map had forgotten — an invisible field with nothing in the console.
    const existing = document.createElement('div');
    currentMap = makeMap({ panes: { ...markerPanes(), 'wf-heat': existing } });
    const createSpy = vi.spyOn(currentMap, 'createPane');
    await mount();
    expect(createSpy).not.toHaveBeenCalled();
    expect(currentMap.panes['wf-heat']).toBe(existing);
  });

  it('never fetches the vendored coastline, which this host does not draw', async () => {
    // `requiresLand: false` is not just a wait-skip: `load()` is what pulls the 9 KB topology chunk,
    // and `drawTiles` clips to nothing — the basemap carries the geography. Flip the flag and every
    // Map-tab visit fetches an asset it cannot use, and the field waits for it first.
    currentMap = makeMap({ panes: markerPanes() });
    await mount();
    expect(load).not.toHaveBeenCalled();
  });

  it('hides the canvas for the zoom animation instead of letting it detach', async () => {
    // Leaflet does NOT scale a pane's contents during a zoom animation — only elements carrying
    // `leaflet-zoom-animated` with their own `zoomanim` handler do — and `_move` suppresses
    // `zoom`/`move` for the whole 250 ms of a wheel zoom. So without this the field holds its
    // pre-zoom pixels at its pre-zoom offset while the tiles scale under it, and then snaps.
    currentMap = makeMap({ panes: markerPanes() });
    await mount();
    expect(currentMap.panes['wf-heat'].classList.contains('leaflet-zoom-hide')).toBe(true);
  });

  it('marks the canvas decorative, because every place it paints is also a named marker', async () => {
    currentMap = makeMap({ panes: markerPanes() });
    await mount();
    // Queried through the PANE, because the canvas is portalled into it and the pane is a Leaflet
    // element rather than part of the render tree.
    const canvas = currentMap.panes['wf-heat'].querySelector('[data-testid="map-heat-canvas"]');
    expect(canvas).toHaveAttribute('aria-hidden', 'true');
  });

  it('pushes the canvas back to the container origin, because a pane is dragged and it is not', async () => {
    // `drawTiles` projects through `latLngToContainerPoint`, i.e. in CONTAINER coordinates, while
    // the canvas sits inside `_mapPane`, which Leaflet translates as you drag. Without this the
    // field slides away from the tiles by exactly that translation.
    currentMap = makeMap({ panes: markerPanes() });
    await mount();
    // The stub echoes its argument, so this asserts the container ORIGIN was asked for and not
    // merely that something was.
    expect(setPosition).toHaveBeenCalledWith(expect.any(HTMLCanvasElement), { x: -40, y: -25 });
  });
});

describe('MapHeatLayer — the paint', () => {
  it('hands the kernel §4.5’s dials, the window’s confidence and the map itself', async () => {
    currentMap = makeMap({ panes: markerPanes() });
    await mount({ conf: 0.5 });
    expect(drawTiles).toHaveBeenCalledTimes(1);
    const [canvas, map, points, win, opts] = drawTiles.mock.calls[0];
    expect(canvas).toBeInstanceOf(HTMLCanvasElement);
    expect(map).toBe(currentMap);
    expect(points).toBe(POINTS);
    // The kernel reads `p.r[win]` and `heatPointsFor` builds a ONE-element `r`, so any other index
    // reads past the end — which P0's non-finite rule then paints as a full-strength 1★ field.
    expect(win).toBe(0);
    expect(opts.grid).toBe(6);
    expect(opts.blur).toBe(4);
    expect(opts.conf).toBe(0.5);
    expect(radiusFor).toHaveBeenCalledWith(currentMap, 8500, 34, 240);
  });

  it('sizes the radius in real distance, so a mile means a mile at every zoom', async () => {
    currentMap = makeMap({ panes: markerPanes() });
    radiusFor.mockReturnValue(77);
    await mount();
    expect(drawTiles.mock.calls[0][4].radius).toBe(77);
  });
});

describe('MapHeatLayer — throttle, never debounce', () => {
  it('coalesces a burst of move events into ONE paint, on the next frame', async () => {
    currentMap = makeMap({ panes: markerPanes() });
    await mount();
    drawTiles.mockClear();

    await act(async () => {
      currentMap.fire('move');
      currentMap.fire('move');
      currentMap.fire('zoom');
    });
    // Nothing yet — the first event scheduled a frame and the other two were dropped.
    expect(drawTiles).not.toHaveBeenCalled();
    await act(async () => { runFrames(); });
    expect(drawTiles).toHaveBeenCalledTimes(1);
  });

  it('is a throttle and not a debounce — the SECOND event schedules no new frame', async () => {
    // ⚠️ This is the assertion, and counting paints is not it. A debounce
    // (`cancelAnimationFrame(rafRef.current)` before rescheduling) passes every paint-count test in
    // this file, because the harness drains whatever is queued and a cancelled slot still yields to
    // the replacement. What separates them is what the second event DOES: a throttle drops it, a
    // debounce cancels the owed frame and books another. So count the frames, not the paints.
    //
    // It matters because a debounce pushes the frame back on every event of a drag, and a drag that
    // never stops then paints nothing at all — the stale-overlay bug `map-tab.js:96-100` records.
    currentMap = makeMap({ panes: markerPanes() });
    await mount();
    drawTiles.mockClear();
    frames = [];

    await act(async () => {
      currentMap.fire('move');
      currentMap.fire('move');
      currentMap.fire('move');
    });
    expect(frames.filter(Boolean)).toHaveLength(1);
    expect(cancelSpy).not.toHaveBeenCalled();

    // And the frame the first event booked fires on time, mid-drag.
    await act(async () => { runFrames(); });
    expect(drawTiles).toHaveBeenCalledTimes(1);
  });

  it('paints in the calling tick on moveend, and cancels the frame the last move owed', async () => {
    // The settle has to be un-throttled or the field lags a frame behind the tiles at the exact
    // moment the reader stops to look. Cancelling the owed frame is what stops the same picture
    // being painted twice at the end of every gesture.
    currentMap = makeMap({ panes: markerPanes() });
    await mount();
    drawTiles.mockClear();

    await act(async () => {
      currentMap.fire('move');
      currentMap.centre = { lat: 56, lng: -2 };
      currentMap.fire('moveend');
    });
    expect(drawTiles).toHaveBeenCalledTimes(1);
    await act(async () => { runFrames(); });
    expect(drawTiles).toHaveBeenCalledTimes(1);
  });

  it('binds every event §4.5 names, and only paints once per settle', async () => {
    // Six events, and a suite that fires three of them proves nothing about the other three:
    // dropping `viewreset` detaches the field after a projection reset, dropping `resize` removes
    // the pane's only Leaflet-native size signal, dropping `zoomend` leaves it soft after a zoom.
    currentMap = makeMap({ panes: markerPanes() });
    await mount();

    for (const event of ['viewreset', 'resize']) {
      drawTiles.mockClear();
      await act(async () => { currentMap.fire(event); });
      expect(drawTiles, `${event} should schedule a throttled paint`).not.toHaveBeenCalled();
      await act(async () => { runFrames(); });
      expect(drawTiles, `${event} should paint on the next frame`).toHaveBeenCalledTimes(1);
    }

    drawTiles.mockClear();
    currentMap.zoom = 12;
    await act(async () => { currentMap.fire('zoomend'); });
    expect(drawTiles).toHaveBeenCalledTimes(1);
  });

  it('settles ONCE when Leaflet fires zoomend and moveend together, as it does on every zoom', async () => {
    // ⚠️ `Map._moveEnd` fires `zoomend` then `moveend` synchronously, and §4.5 binds the settle to
    // both — so without the same-tick latch the end of every zoom gesture paints the same picture
    // twice, which is exactly what the cancelled frame inside `repaintNow` exists to prevent one
    // frame later.
    currentMap = makeMap({ panes: markerPanes() });
    await mount();
    drawTiles.mockClear();
    currentMap.zoom = 10;
    await act(async () => {
      currentMap.fire('zoomend');
      currentMap.fire('moveend');
    });
    expect(drawTiles).toHaveBeenCalledTimes(1);
  });

  it('settles AGAIN when the view has genuinely moved, in the same tick', async () => {
    // ⚠️ The other half, and the reason the dedupe keys on the view rather than on the tick. A
    // same-tick latch was tried and measured in a browser: a map driven through several zooms
    // inside one task kept the marker fade it computed for the first of them.
    currentMap = makeMap({ panes: markerPanes() });
    await mount();
    drawTiles.mockClear();
    await act(async () => {
      currentMap.zoom = 11;
      currentMap.fire('zoomend');
      currentMap.zoom = 13;
      currentMap.fire('zoomend');
    });
    expect(drawTiles).toHaveBeenCalledTimes(2);
    expect(currentMap.panes.markerPane.style.opacity).toBe('1');
  });

  it('keeps ONE Leaflet subscription across a window change', async () => {
    // `repaint`/`repaintNow` are stable by construction (`useCallback(…, [])` reading a ref), and
    // that is what stops six handlers being re-bound whenever the selected window or its confidence
    // moves. Make them depend on the paint callback and this goes to two.
    currentMap = makeMap({ panes: markerPanes() });
    const onSpy = vi.spyOn(currentMap, 'on');
    const { rerender } = await mount();
    const bindings = onSpy.mock.calls.length;
    await act(async () => {
      rerender(<MapHeatLayer points={[POINTS[0]]} conf={0.5} />);
    });
    expect(onSpy.mock.calls.length).toBe(bindings);
  });

  it('stops listening when it unmounts', async () => {
    currentMap = makeMap({ panes: markerPanes() });
    const { unmount } = await mount();
    await act(async () => { unmount(); });
    drawTiles.mockClear();
    currentMap.centre = { lat: 57, lng: -1 };
    await act(async () => { currentMap.fire('moveend'); });
    expect(drawTiles).not.toHaveBeenCalled();
  });
});

describe('MapHeatLayer — what it declines to do', () => {
  it('paints nothing at all while the shell has the pane hidden', async () => {
    // The pane is never unmounted — the shell hides it with `display: none` — and Leaflet's
    // `getSize()` returns a CACHED box, so a hidden map cheerfully reports 800×500. Without the
    // container check every briefing poll ran a full `drawTiles` into a canvas nobody could see.
    currentMap = makeMap({ panes: markerPanes(), onScreen: false });
    await mount();
    expect(drawTiles).not.toHaveBeenCalled();
    expect(currentMap.panes.markerPane.style.opacity).toBe('');
  });

  it('renders no canvas at all when the browser will not give a 2d context', async () => {
    // Terminal for this host: `measureAndPaint` declines from then on, so leaving the element in
    // place would put an empty canvas under a toolbar whose legend still explains a ramp.
    HTMLCanvasElement.prototype.getContext = () => null;
    currentMap = makeMap({ panes: markerPanes() });
    await mount();
    expect(currentMap.panes['wf-heat'].querySelector('[data-testid="map-heat-canvas"]')).toBeNull();
    expect(drawTiles).not.toHaveBeenCalled();
  });
});

describe('MapHeatLayer — the zoom handover (D8)', () => {
  it('computes the two opacities in opposite directions across one band', () => {
    // Below the band the field is whole and the markers are gone; above it the markers are whole and
    // the field settles to a 17% wash rather than vanishing — the regional answer is still true at
    // street level.
    expect(fadeAt(9)).toEqual({ markers: 0, heat: 1 });
    expect(fadeAt(10.6)).toEqual({ markers: 0, heat: 1 });
    expect(fadeAt(12.2).markers).toBe(1);
    expect(fadeAt(12.2).heat).toBeCloseTo(0.17, 5);
    expect(fadeAt(19).markers).toBe(1);
    const mid = fadeAt(11.4);
    expect(mid.markers).toBeCloseTo(0.5, 5);
    expect(mid.heat).toBeCloseTo(0.585, 5);
  });

  it('multiplies the field’s own 0.9 by the zoom fade rather than replacing it', async () => {
    currentMap = makeMap({ zoom: 9, panes: markerPanes() });
    await mount();
    expect(drawTiles.mock.calls[0][4].opacity).toBeCloseTo(0.9, 5);

    currentMap = makeMap({ zoom: 13, panes: markerPanes() });
    drawTiles.mockClear();
    await mount();
    expect(drawTiles.mock.calls[0][4].opacity).toBeCloseTo(0.9 * 0.17, 5);
  });

  it('hides the marker panes wholesale at the zoom the tab opens at', async () => {
    // Without this, today's fully opaque cluster medallions paint over the field at every zoom and
    // recreate exactly the overload the feature removes.
    currentMap = makeMap({ zoom: 9, panes: markerPanes() });
    await mount();
    expect(currentMap.panes.markerPane.style.opacity).toBe('0');
    expect(currentMap.panes.shadowPane.style.opacity).toBe('0');
  });

  it('hands the markers back in full once the question has become WHICH', async () => {
    currentMap = makeMap({ zoom: 13, panes: markerPanes() });
    await mount();
    expect(currentMap.panes.markerPane.style.opacity).toBe('1');
    expect(currentMap.panes.markerPane.style.pointerEvents).toBe('');
    expect(currentMap.panes.markerPane.style.visibility).toBe('');
  });

  it('marks the threshold on the value, at 0.19 / 0.20 / 0.21', () => {
    // Exported precisely so the cut can be tested ON it: no zoom maps to exactly 0.2 in floating
    // point (10.92 gives 0.19999999999999907), so a fixture either side proves only that the
    // threshold is somewhere in a gap. `>=` versus `>` is the mutation this catches.
    expect(markersAreInteractive(0.19)).toBe(false);
    expect(markersAreInteractive(0.2)).toBe(true);
    expect(markersAreInteractive(0.21)).toBe(true);
  });

  it('leaves no invisible click targets below 20% opacity, and does it with BOTH rules', async () => {
    // ⚠️ `pointer-events: none` on the pane is necessary and NOT sufficient: Leaflet's own
    // stylesheet carries `.leaflet-marker-icon.leaflet-interactive { pointer-events: auto }`, and a
    // child re-enabling beats an ancestor disabling. The class is what reaches the children.
    //
    // ⚠️ And it is deliberately a CLASS rather than `visibility: hidden`, which was the first cut:
    // hiding takes every marker out of the accessibility tree and the tab order at the zoom this
    // tab opens at, and the markers are the only route to a location's popup. Restore `visibility`
    // here and this test still passes — which is why the accessibility half is asserted below.
    //
    // t = 0.2 sits at zoom 10.92; 10.9 is below it and 11.0 is above.
    currentMap = makeMap({ zoom: 10.9, panes: markerPanes() });
    await mount();
    expect(currentMap.panes.markerPane.style.pointerEvents).toBe('none');
    expect(currentMap.panes.markerPane.classList.contains('wf-markers-inert')).toBe(true);

    currentMap = makeMap({ zoom: 11, panes: markerPanes() });
    await mount();
    expect(currentMap.panes.markerPane.style.pointerEvents).toBe('');
    expect(currentMap.panes.markerPane.classList.contains('wf-markers-inert')).toBe(false);
  });

  it('never hides the markers from the accessibility tree, at any zoom', async () => {
    // The rule the class exists to keep. `visibility: hidden` (or `display: none`) would close the
    // only route a keyboard or screen-reader user has to a location on this tab, and would drop
    // focus to `<body>` whenever a focused marker faded. Opacity hides the picture and nothing else.
    currentMap = makeMap({ zoom: 4, panes: markerPanes() });
    await mount();
    expect(currentMap.panes.markerPane.style.visibility).toBe('');
    expect(currentMap.panes.markerPane.style.display).toBe('');
  });

  it('cross-fades rather than stepping — a fractional zoom gives a fractional opacity', async () => {
    // Endpoint-only assertions cannot tell a ramp from a step: `String(Math.round(opacity))` in the
    // fade, or a `heat < 1 ? FLOOR : 1` in the paint, passes every 0-and-1 test in this file.
    currentMap = makeMap({ zoom: 11.4, panes: markerPanes() });
    await mount();
    expect(Number(currentMap.panes.markerPane.style.opacity)).toBeCloseTo(0.5, 5);
    expect(drawTiles.mock.calls[0][4].opacity).toBeCloseTo(0.9 * 0.585, 5);
  });

  it('pins the markers at full strength while a location is OPEN', async () => {
    // A popup lives in `popupPane` and the azimuth lines in `overlayPane`; neither fades, and
    // neither should. Fading the marker they are anchored to leaves a popup with its leader tip on
    // bare ground — reachable without trying, because the tab's own handoff fits a region at
    // `maxZoom: 12` and then opens that location's popup.
    currentMap = makeMap({ zoom: 9, panes: markerPanes() });
    await mount({ markersLocked: true });
    expect(currentMap.panes.markerPane.style.opacity).toBe('');
    expect(currentMap.panes.markerPane.classList.contains('wf-markers-inert')).toBe(false);
    // The field itself is unaffected — only the handover is suspended.
    expect(drawTiles).toHaveBeenCalledTimes(1);
  });

  it('does NOT fade the markers when the window has no field to hand over to', async () => {
    // An Awaiting window — a T+3 the nightly policy did not evaluate — paints an empty canvas.
    // Fading the markers out under it would leave a blank map at the zoom the tab opens at, with
    // nothing on screen saying why.
    currentMap = makeMap({ zoom: 9, panes: markerPanes() });
    await mount({ points: [] });
    // The positive control: `''` is also jsdom's default, so without proving the paint RAN this
    // assertion passes for a layer that never did anything at all.
    expect(drawTiles).toHaveBeenCalledTimes(1);
    expect(currentMap.panes.markerPane.style.opacity).toBe('');
    expect(currentMap.panes.markerPane.classList.contains('wf-markers-inert')).toBe(false);
  });

  it('hands the marker panes back exactly as it found them when it unmounts', async () => {
    // This is what makes the medallion view byte-identical to today (D8). Restoring to '' rather
    // than to '1'/'auto'/'visible' matters: an inline declaration would keep overriding the
    // stylesheet on a map this layer no longer owns.
    currentMap = makeMap({ zoom: 9, panes: markerPanes() });
    const { unmount } = await mount();
    expect(currentMap.panes.markerPane.style.opacity).toBe('0');
    expect(currentMap.panes.markerPane.classList.contains('wf-markers-inert')).toBe(true);
    await act(async () => { unmount(); });
    expect(currentMap.panes.markerPane.style.opacity).toBe('');
    expect(currentMap.panes.markerPane.style.pointerEvents).toBe('');
    expect(currentMap.panes.markerPane.classList.contains('wf-markers-inert')).toBe(false);
  });
});
