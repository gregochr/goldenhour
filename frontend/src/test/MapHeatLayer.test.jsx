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
// P4 RE-PIN (map-tab-v2-plan.md §3 P4): `land()` now tracks the SAME latch the mocked `load()`
// flips, so `MapHeatLayer`'s `alreadyLoaded = land() != null` guard sees a faithful reflection of
// "has the topology arrived" rather than a permanently-null stub. Reset per test so one test's
// resolved load cannot leak into the next's "topology not here yet" fixtures.
let mockLandLoaded = false;
const LAND_FIXTURE = { type: 'FeatureCollection', features: [] };
const load = vi.fn(() => Promise.resolve(LAND_FIXTURE).then((v) => {
  mockLandLoaded = true;
  return v;
}));
vi.mock('../utils/heatField.js', async (importOriginal) => {
  const actual = await importOriginal();
  return {
    ...actual,
    drawTiles: (...args) => drawTiles(...args),
    radiusFor: (...args) => radiusFor(...args),
    load: (...args) => load(...args),
    land: () => (mockLandLoaded ? LAND_FIXTURE : null),
  };
});

// P4's land clip: the component's own wiring is this file's business, but the Path2D cache build
// itself is `landMask.test.js`'s — so it is mocked here, the same way `drawTiles`'s own pixels are
// out of scope for this file. Defaults to "no topology yet" (`get` returns null), matching what
// the real module would answer before `load()` resolves.
const landMaskGet = vi.fn(() => null);
const landMaskInvalidate = vi.fn();
vi.mock('../utils/landMask.js', () => ({
  createLandMask: vi.fn(() => ({ get: landMaskGet, invalidate: landMaskInvalidate })),
}));

import MapHeatLayer, { coastAlphaAt, fadeAt } from '../components/MapHeatLayer.jsx';
import { setMode } from '../utils/scoreRamp.js';

/**
 * The Leaflet host for the heat field — the pane, the throttle and the handover.
 *
 * <p><b>What this file protects.</b> Three of its rules are invisible from any screenshot and were
 * specified rather than discovered, so nothing else in the suite would catch their removal: the
 * pane's stacking order (350, between the tiles and the chips) and the throttle-not-debounce split
 * across `move`/`moveend`.
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
    // P4's land clip reads this for `clipDx`/`clipDy`. A fixed origin is a safe default for every
    // test that does not care about it; the land-clip describe block below overrides it per test
    // to assert the translation is actually threaded through.
    getPixelBounds: () => ({ min: { x: 0, y: 0 } }),
    // P8's reach rings read this for the home marker's screen position. Deterministic and
    // distinguishable from a raw lat/lng pair (unlike an identity stub), so a test can assert
    // exactly which point the rings were centred on.
    latLngToContainerPoint: ([lat, lng]) => ({ x: (lng + 3) * 10, y: (56 - lat) * 10 }),
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

/** No extra panes: the host only needs its own pane, which `makeMap` creates on demand. */
function markerPanes() {
  return {};
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
  // P4 RE-PIN: one test's resolved `load()` must not leave `land()` answering non-null for the
  // next test's "topology not here yet" fixture.
  mockLandLoaded = false;
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

  // P4 RE-PIN (map-tab-v2-plan.md §3 P4): "never fetches the vendored coastline, which this host
  // does not draw" asserted `load` was NEVER called — true only while this host drew no
  // coastline. P4 gives it one (the land clip + the coastline stroke), so the premise is now
  // deliberately false: this host DOES fetch the topology, on mount, unconditionally. Its inverse
  // — "kicks off the land geometry load on mount" — lives in the new
  // "MapHeatLayer — the land clip" describe block below; deleted here rather than left as an
  // orphaned contradiction beside it.

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
    // P4 RE-PIN (map-tab-v2-plan.md §3 P4): the "faithful" fix (wiring the mocked `land()` to the
    // same latch the mocked `load()` flips, at the top of this file) was tried first and cannot
    // suppress this to 1× — `alreadyLoaded` is captured synchronously BEFORE the mocked promise
    // resolves, one commit too early for any `land()` mock to change the outcome on a single fresh
    // mount. That is not instability to paper over: in THIS harness the count is 2-BY-DESIGN
    // (mount paint + load-resolve repaint) and deterministic — an adversarial review's refuter
    // injected a duplicated `repaintNow()` into a scratch copy and an exact count caught it where
    // a relaxed one did not. PRODUCTION cold mounts can legitimately exceed 2 (a genuine
    // zoomend/moveend landing during the `load()` gap adds its own settle-triggered repaint), which
    // is exactly why this pins the UNIT-level invariant only and no integration path asserts an
    // exact count.
    expect(drawTiles).toHaveBeenCalledTimes(2);
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
    // P4 RE-PIN: 8500/34/240 → 7200/30/190 (map-tab-v2-plan.md §3 P4); cap 190 → 400 on 2026-10-02.
    expect(radiusFor).toHaveBeenCalledWith(currentMap, 7200, 30, 400);
  });

  it('sizes the radius in real distance, so a mile means a mile at every zoom', async () => {
    currentMap = makeMap({ panes: markerPanes() });
    radiusFor.mockReturnValue(77);
    await mount();
    expect(drawTiles.mock.calls[0][4].radius).toBe(77);
  });
});

describe('MapHeatLayer — the "not rated yet" hatch (thin-sample plan F4)', () => {
  const UNRATED = [{ lat: 54.9, lng: -2.1, rid: 'North East', r: [0] }];

  it('hands the kernel the unrated places, beside the rated points', async () => {
    currentMap = makeMap({ panes: markerPanes() });
    await mount({ unrated: UNRATED });
    const opts = drawTiles.mock.calls.at(-1)[4];
    expect(opts.unrated).toBe(UNRATED);
    expect(drawTiles.mock.calls.at(-1)[2]).toBe(POINTS);
    // The radius is the field's own, unchanged.
    expect(radiusFor).toHaveBeenCalledWith(currentMap, 7200, 30, 400);
  });

  it('the hatch takes the field\'s own opacity, scaled by dim', async () => {
    currentMap = makeMap({ panes: markerPanes() });
    await mount({ unrated: UNRATED, dim: 1 });
    const full = drawTiles.mock.calls.at(-1)[4].opacity;
    await mount({ unrated: UNRATED, dim: 0.5 });
    // One options object reaches both the colour and `paintUnknown`, so one `opacity` is the factor
    // for both: the zoom handover's `fade.heat` and Ask's dim are inside it.
    expect(drawTiles.mock.calls.at(-1)[4].opacity).toBeCloseTo(full * 0.5, 5);
    expect(full).toBeGreaterThan(0);
  });

  it.each([[null], [[]], [undefined]])('leaves the options object as it was for %j', async (unrated) => {
    currentMap = makeMap({ panes: markerPanes() });
    await mount({ unrated });
    expect('unrated' in drawTiles.mock.calls.at(-1)[4]).toBe(false);
  });
});

describe('MapHeatLayer — the heat bloom (map-tab-v2-plan.md §3 P2)', () => {
  // MODE is scoreRamp module state, not a per-test fixture — a test that switches it and forgets
  // to undo it leaks into every case that runs after it, in this file or another.
  afterEach(() => {
    setMode('verdict');
  });

  it('adds the bare bloom flag in temperature mode — the kernel defaults already ARE the README\'s Map tab field row (190/2.4/3.0)', async () => {
    setMode('temp');
    currentMap = makeMap({ panes: markerPanes() });
    await mount();
    expect(drawTiles.mock.calls.at(-1)[4].bloom).toBe(1);
    // No dial override at this call site — the kernel's own defaults carry the numbers. Key
    // absence, not a falsy value, matching the verdict-mode test one block below.
    expect('bloomFrom' in drawTiles.mock.calls.at(-1)[4]).toBe(false);
    expect('bloomA' in drawTiles.mock.calls.at(-1)[4]).toBe(false);
    expect('bloomBlur' in drawTiles.mock.calls.at(-1)[4]).toBe(false);
  });

  it('carries NO bloom key at all in verdict mode — absence, not a falsy value', async () => {
    // The verdict ramp has no luminance inversion, so a bloom over it would be a false signal
    // (plan D-1). `bloom: 0` would still be a key `field()` would read; the options object here
    // must be identical to today's pre-P2 shape.
    setMode('verdict');
    currentMap = makeMap({ panes: markerPanes() });
    await mount();
    expect('bloom' in drawTiles.mock.calls.at(-1)[4]).toBe(false);
  });

  // The injected ramp and bloom (aurora-heat-plan.md §3 A2). Aurora is NOT a colourMode value.
  describe('an injected ramp and bloom', () => {
    const RAMP = () => [1, 2, 3];
    const BLOOM = { rgb: [120, 255, 170], from: 3.4, a: 150 };

    it('hands the ramp to the kernel, and carries no ramp key at all without one', async () => {
      currentMap = makeMap({ panes: markerPanes() });
      await mount({ ramp: RAMP, bloom: BLOOM });
      expect(drawTiles.mock.calls.at(-1)[4].ramp).toBe(RAMP);
      await mount();
      expect('ramp' in drawTiles.mock.calls.at(-1)[4]).toBe(false);
    });

    it('asks for the bloom with its own colour, gate and strength', async () => {
      setMode('verdict');
      currentMap = makeMap({ panes: markerPanes() });
      await mount({ ramp: RAMP, bloom: BLOOM });
      const opts = drawTiles.mock.calls.at(-1)[4];
      expect(opts.bloom).toBe(1);
      expect(opts.bloomFrom).toBe(3.4);
      expect(opts.bloomA).toBe(150);
      expect(opts.bloomRGB).toEqual([120, 255, 170]);
    });

    it('lets the aurora bloom win over the temperature arm — no half-mixed dials', async () => {
      // In temperature mode the bare flag would be set with NO dials; the injected bloom must carry
      // all four keys, so a swapped order of the two arms is caught here.
      setMode('temp');
      currentMap = makeMap({ panes: markerPanes() });
      await mount({ ramp: RAMP, bloom: BLOOM });
      const opts = drawTiles.mock.calls.at(-1)[4];
      expect(opts.bloomRGB).toEqual([120, 255, 170]);
      expect(opts.bloomFrom).toBe(3.4);
      expect(opts.bloomA).toBe(150);
    });

    it('with no bloom prop in verdict mode there is still no bloom, whatever ramp is injected', async () => {
      setMode('verdict');
      currentMap = makeMap({ panes: markerPanes() });
      await mount({ ramp: RAMP });
      const opts = drawTiles.mock.calls.at(-1)[4];
      expect('bloom' in opts).toBe(false);
      expect('bloomRGB' in opts).toBe(false);
    });

    it('a ramp injected without a bloom adds the ramp and no bloom key of any kind (verdict mode)', async () => {
      setMode('verdict');
      currentMap = makeMap({ panes: markerPanes() });
      await mount({ ramp: RAMP, bloom: null });
      const opts = drawTiles.mock.calls.at(-1)[4];
      expect(opts.ramp).toBe(RAMP);
      for (const key of ['bloom', 'bloomFrom', 'bloomA', 'bloomRGB']) expect(key in opts).toBe(false);
    });

    it('in temperature mode with no injected bloom the bare flag carries none of the aurora dials', async () => {
      setMode('temp');
      currentMap = makeMap({ panes: markerPanes() });
      await mount({ ramp: null, bloom: null });
      const opts = drawTiles.mock.calls.at(-1)[4];
      expect(opts.bloom).toBe(1);
      for (const key of ['bloomFrom', 'bloomA', 'bloomRGB', 'ramp']) expect(key in opts).toBe(false);
    });

    it('repaints when ONLY the ramp changes', async () => {
      currentMap = makeMap({ panes: markerPanes() });
      const { rerender } = await mount({ ramp: RAMP, bloom: BLOOM });
      const before = drawTiles.mock.calls.length;
      const OTHER = () => [9, 9, 9];
      await act(async () => {
        rerender(<MapHeatLayer points={POINTS} conf={1} ramp={OTHER} bloom={BLOOM} />);
      });
      expect(drawTiles.mock.calls.length).toBeGreaterThan(before);
      expect(drawTiles.mock.calls.at(-1)[4].ramp).toBe(OTHER);
    });

    it('repaints when ONLY the bloom changes', async () => {
      currentMap = makeMap({ panes: markerPanes() });
      const { rerender } = await mount({ ramp: RAMP, bloom: BLOOM });
      const before = drawTiles.mock.calls.length;
      await act(async () => {
        rerender(<MapHeatLayer points={POINTS} conf={1} ramp={RAMP} bloom={{ ...BLOOM, a: 99 }} />);
      });
      expect(drawTiles.mock.calls.length).toBeGreaterThan(before);
      expect(drawTiles.mock.calls.at(-1)[4].bloomA).toBe(99);
    });

    it('drops the ramp and bloom again when they go back to null (Aurora to Sunset and back)', async () => {
      currentMap = makeMap({ panes: markerPanes() });
      const { rerender } = await mount({ ramp: RAMP, bloom: BLOOM });
      const after = (props) => act(async () => {
        rerender(<MapHeatLayer points={POINTS} conf={1} {...props} />);
      });
      await after({ ramp: null, bloom: null });
      expect('ramp' in drawTiles.mock.calls.at(-1)[4]).toBe(false);
      expect('bloomRGB' in drawTiles.mock.calls.at(-1)[4]).toBe(false);
      await after({ ramp: RAMP, bloom: BLOOM });
      expect(drawTiles.mock.calls.at(-1)[4].ramp).toBe(RAMP);
      expect(drawTiles.mock.calls.at(-1)[4].bloomFrom).toBe(3.4);
    });
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
    // The FIELD's own opacity is what tracks the last zoom now (the markers are simply off at
    // both) — 13 is past `fadeAt`'s band, so the field is at its 0.35 floor. A repaint that kept
    // zoom 11's frame would show 1 − 0.88×0.375 = 0.67 here.
    expect(drawTiles.mock.calls.at(-1)[4].opacity).toBeCloseTo(0.92 * 0.35, 5);
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
    // P4 RE-PIN (map-tab-v2-plan.md §3 P4): the band was re-tuned 10.6→12.2/0.17 to
    // 10.4→12.0/0.12 in the same commit as the land clip and the radius re-tune (the old band was
    // part of why the field swam offshore). Values below are computed fresh from the formula at
    // the NEW band's own edges and midpoint (11.2, not the old band's 11.4), not copied from any
    // implementation output.
    //
    // ⚠️ Pure-number pin on `fadeAt` itself, NOT a statement about what paints. Its `markers` half
    // is the Legend indicator's progress fraction now and nothing renders at it; the field's own
    // `heat` half is the one the canvas still reads. (It used to read "above it the markers are
    // whole" — the sentence this change falsified.) Below the band the field is whole;
    // and the field settles to a 35% wash rather than vanishing — the regional answer is still
    // true at street level.
    expect(fadeAt(9)).toEqual({ markers: 0, heat: 1 });
    expect(fadeAt(10.4)).toEqual({ markers: 0, heat: 1 });
    expect(fadeAt(12.0).markers).toBe(1);
    expect(fadeAt(12.0).heat).toBeCloseTo(0.35, 5);
    expect(fadeAt(19).markers).toBe(1);
    const mid = fadeAt(11.2);
    expect(mid.markers).toBeCloseTo(0.5, 5);
    expect(mid.heat).toBeCloseTo(1 - 0.65 * 0.5, 5);
  });

  it('multiplies the field’s own 0.92 by the zoom fade rather than replacing it', async () => {
    currentMap = makeMap({ zoom: 9, panes: markerPanes() });
    await mount();
    expect(drawTiles.mock.calls[0][4].opacity).toBeCloseTo(0.92, 5);

    currentMap = makeMap({ zoom: 13, panes: markerPanes() });
    drawTiles.mockClear();
    await mount();
    // P4 RE-PIN: floor 0.17 → 0.12 (map-tab-v2-plan.md §3 P4); 0.12 → 0.35 on 2026-10-02.
    // 2026-09-03: base opacity corrected 0.9 → 0.92 (drift repair, map-tab-v2-plan.md §4).
    expect(drawTiles.mock.calls[0][4].opacity).toBeCloseTo(0.92 * 0.35, 5);
  });

  it('cross-fades rather than stepping — a fractional zoom gives a fractional FIELD opacity', async () => {
    // Endpoint-only assertions cannot tell a ramp from a step: a `heat < 1 ? FLOOR : 1` in the
    // paint passes every 0-and-1 test in this file.
    //
    // P4 RE-PIN (map-tab-v2-plan.md §3 P4): zoom 11.4 is no longer the band's exact midpoint under
    // the new 10.4→12.0 band (that's 11.2 — see "computes the two opacities" above), so the
    // expected value here is recomputed from the formula at this same zoom rather than copied
    // from the old band's coincidentally-round 0.585: t = (11.4−10.4)/1.6 = 0.625,
    // heat = 1 − 0.88×0.625 = 0.45 (floor 0.35 since 2026-10-02: 1 − 0.65×0.625 = 0.59375).
    //
    // `fadeAt`'s own `markers` component is still a ramp and is still pinned as one, on pure
    // numbers, in "computes the two opacities".
    currentMap = makeMap({ zoom: 11.4, panes: markerPanes() });
    await mount();
    // 2026-09-03: base opacity corrected 0.9 → 0.92 (drift repair, map-tab-v2-plan.md §4).
    expect(drawTiles.mock.calls[0][4].opacity).toBeCloseTo(0.92 * 0.59375, 5);
  });

  it('paints the field twice on a mount (mount paint + load-resolve repaint)', async () => {
    currentMap = makeMap({ zoom: 9, panes: markerPanes() });
    await mount();
    // P4 RE-PIN (map-tab-v2-plan.md §3 P4): the count is 2-BY-DESIGN in this harness (mount paint +
    // load-resolve repaint), deterministic, and worth pinning exactly — an adversarial review's
    // refuter injected a duplicated `repaintNow()` into a scratch copy and only an exact count
    // caught it (a relaxed `toHaveBeenCalled()` did not). See the fuller note on "hands the kernel
    // §4.5's dials" above for why `alreadyLoaded` cannot suppress this to 1× here. PRODUCTION cold
    // mounts can legitimately exceed 2 (a genuine zoomend/moveend landing during the `load()` gap
    // adds its own repaint) — this pins the UNIT-level invariant only, which is why no integration
    // path asserts an exact count.
    expect(drawTiles).toHaveBeenCalledTimes(2);
  });

  it('paints on an EMPTY window too — an empty point set is not "nothing is scored"', async () => {
    currentMap = makeMap({ zoom: 9, panes: markerPanes() });
    await mount({ points: [] });
    // The count pins the 2-BY-DESIGN mount+load-resolve pair (production can exceed 2 on a zoomend landing mid-
    // `load()`, so only the unit-level invariant is asserted).
    expect(drawTiles).toHaveBeenCalledTimes(2);
  });

});

/**
 * A canvas 2d context richer than the file's default `getContext = () => ({})` stub — enough for
 * the real `fit()` (called directly by `MapHeatLayer` for the coastline stroke, unlike every other
 * draw in this file, which goes through the mocked `drawTiles`) to run without throwing.
 *
 * <p>Without this, any test whose `landMask.get()` resolves to a real (truthy) path at a zoom
 * where the stroke's own alpha is above zero hits `ctx.setTransform is not a function` INSIDE the
 * paint callback — and because that callback can run inside this file's `load().then()` chain, the
 * exception lands in the load effect's own `.catch()` and is silently logged as a "land geometry
 * failed to load", not surfaced as a test failure. Installing this stub is what turns that into an
 * honest assertion failure instead of a masked crash.
 */
function makeCanvasCtxStub() {
  return {
    setTransform: vi.fn(),
    save: vi.fn(),
    restore: vi.fn(),
    translate: vi.fn(),
    stroke: vi.fn(),
    clearRect: vi.fn(),
  };
}

/**
 * The land clip, the coastline stroke and the co-tuned dials (map-tab-v2-plan.md §3 P4) — the fix
 * for the founding "heat sits in the sea" complaint.
 *
 * <p>`landMask.js` is mocked here (see the top of the file): its own Path2D build is
 * `landMask.test.js`'s business, and this file's job is the WIRING — that `MapHeatLayer` asks the
 * mask for the right zoom-gated options and reacts correctly to the mask having (or not having)
 * a path yet.
 */
describe('MapHeatLayer — the land clip (map-tab-v2-plan.md §3 P4)', () => {
  beforeEach(() => {
    landMaskGet.mockReset();
    landMaskGet.mockReturnValue(null);
    landMaskInvalidate.mockReset();
    // See `makeCanvasCtxStub`'s own doc comment — some cases here give `landMaskGet` a real path
    // at a zoom where the coastline stroke would also fire.
    HTMLCanvasElement.prototype.getContext = () => makeCanvasCtxStub();
  });

  it('re-tunes the field to 7200m / 30–400px (cap 190 → 400, 2026-10-02)', async () => {
    currentMap = makeMap({ zoom: 9, panes: markerPanes() });
    await mount();
    expect(radiusFor).toHaveBeenCalledWith(currentMap, 7200, 30, 400);
  });

  it('keeps the field at the 0.35 floor from zoom 12 up, and whole below the band (2026-10-02)', () => {
    expect(fadeAt(10).heat).toBe(1);
    expect(fadeAt(12).heat).toBeCloseTo(0.35, 5);
    expect(fadeAt(14).heat).toBeCloseTo(0.35, 5);
  });

  it('re-tunes the field/marker handover band to 10.4→12.0 (floor since raised to 0.35)', () => {
    // Pinned as pure numbers on `fadeAt` itself, the same idiom as the pre-existing D8 suite
    // (which still pins the OLD 10.6/12.2/0.17 band — see this phase's own report on that).
    expect(fadeAt(10.4)).toEqual({ markers: 0, heat: 1 });
    expect(fadeAt(12.0).markers).toBe(1);
    expect(fadeAt(12.0).heat).toBeCloseTo(0.35, 5);
    // The midpoint of the NEW band (11.2, not the old band's 11.4) is where the two curves cross.
    const mid = fadeAt(11.2);
    expect(mid.markers).toBeCloseTo(0.5, 5);
    expect(mid.heat).toBeCloseTo(1 - 0.65 * 0.5, 5);
  });

  it('passes the clip keyed to the map’s own pixel origin, below zoom 11.5', async () => {
    const stubPath = { __stub: 'land' };
    landMaskGet.mockReturnValue(stubPath);
    currentMap = makeMap({ zoom: 10, panes: markerPanes() });
    currentMap.getPixelBounds = () => ({ min: { x: 120, y: 80 } });
    await mount();
    const opts = drawTiles.mock.calls.at(-1)[4];
    expect(opts.clipPath).toBe(stubPath);
    expect(opts.clipSoft).toBe(4);
    expect(opts.clipDx).toBe(-120);
    expect(opts.clipDy).toBe(-80);
    // The ~4km seaward dilation (map-tab-v2-plan.md §3 P4) — without it a 1:50m clip erased 7 of
    // 51 coastal locations, including a 5★.
    expect(radiusFor).toHaveBeenCalledWith(currentMap, 4200, 3, 120);
  });

  it('carries none of the five clip keys at or above zoom 11.5 — absence, not a falsy value', async () => {
    currentMap = makeMap({ zoom: 11.5, panes: markerPanes() });
    await mount();
    const opts = drawTiles.mock.calls.at(-1)[4];
    expect('clipPath' in opts).toBe(false);
    expect('clipSoft' in opts).toBe(false);
    expect('clipGrow' in opts).toBe(false);
    expect('clipDx' in opts).toBe(false);
    expect('clipDy' in opts).toBe(false);
  });

  it('still passes clipPath (null) and the other four keys below the threshold before the topology resolves', async () => {
    // landMaskGet's default (null) stands for "load() has not resolved yet" — the graceful
    // fallback the kernel reads as "no clip", never a permanent blank.
    currentMap = makeMap({ zoom: 9, panes: markerPanes() });
    await mount();
    const opts = drawTiles.mock.calls.at(-1)[4];
    expect(opts.clipPath).toBeNull();
    expect(opts.clipSoft).toBe(4);
    expect('clipGrow' in opts).toBe(true);
  });

  it('kicks off the land geometry load on mount', async () => {
    currentMap = makeMap({ panes: markerPanes() });
    await mount();
    expect(load).toHaveBeenCalledTimes(1);
  });

  it('invalidates the mask and repaints once the topology resolves, without user interaction', async () => {
    let resolveLoad;
    load.mockImplementationOnce(() => new Promise((res) => { resolveLoad = res; }));
    currentMap = makeMap({ zoom: 9, panes: markerPanes() });
    await mount();
    expect(drawTiles.mock.calls.at(-1)[4].clipPath).toBeNull();
    const stubPath = { __stub: 'resolved' };
    landMaskGet.mockReturnValue(stubPath);
    drawTiles.mockClear();
    await act(async () => {
      resolveLoad({ type: 'FeatureCollection', features: [] });
      await Promise.resolve();
      await Promise.resolve();
    });
    expect(landMaskInvalidate).toHaveBeenCalled();
    expect(drawTiles).toHaveBeenCalledTimes(1);
    expect(drawTiles.mock.calls.at(-1)[4].clipPath).toBe(stubPath);
  });

  it('logs and stays unclipped when the land geometry fails to load', async () => {
    const consoleSpy = vi.spyOn(console, 'error').mockImplementation(() => {});
    load.mockImplementationOnce(() => Promise.reject(new Error('no chunk')));
    currentMap = makeMap({ zoom: 9, panes: markerPanes() });
    await mount();
    await act(async () => {
      await Promise.resolve();
      await Promise.resolve();
    });
    expect(consoleSpy).toHaveBeenCalled();
    expect(drawTiles.mock.calls.at(-1)[4].clipPath).toBeNull();
    consoleSpy.mockRestore();
  });

  it('invalidates the mask on zoomend and on a container resize', async () => {
    currentMap = makeMap({ zoom: 9, panes: markerPanes() });
    await mount();
    landMaskInvalidate.mockClear();
    await act(async () => { currentMap.fire('resize'); });
    expect(landMaskInvalidate).toHaveBeenCalled();
    landMaskInvalidate.mockClear();
    currentMap.zoom = 10;
    await act(async () => { currentMap.fire('zoomend'); });
    expect(landMaskInvalidate).toHaveBeenCalled();
  });
});

/**
 * The coastline stroke — drawn from the SAME Path2D the clip uses, straight onto the field's own
 * canvas. This block keeps its own named `ctxStub` (rather than the anonymous one
 * `makeCanvasCtxStub` hands the land-clip block above) because every test here reads it back —
 * `ctxStub.stroke`, `.lineWidth`, `.strokeStyle` — to assert what was actually drawn. Restored by
 * the file's own top-level `afterEach`, like every other override in this file.
 */
describe('MapHeatLayer — the coastline stroke (map-tab-v2-plan.md §3 P4)', () => {
  let ctxStub;

  beforeEach(() => {
    landMaskGet.mockReset();
    landMaskGet.mockReturnValue(null);
    landMaskInvalidate.mockReset();
    ctxStub = makeCanvasCtxStub();
    HTMLCanvasElement.prototype.getContext = () => ctxStub;
  });

  it('pins the alpha formula at the fade band’s ends — 0.5 at z≤9.4, 0 at z≥11', () => {
    expect(coastAlphaAt(9.4)).toBeCloseTo(0.5, 5);
    expect(coastAlphaAt(4)).toBeCloseTo(0.5, 5);
    expect(coastAlphaAt(11)).toBe(0);
    expect(coastAlphaAt(15)).toBe(0);
    expect(coastAlphaAt(10.2)).toBeCloseTo(0.25, 5);
  });

  it('strokes the SAME Path2D the clip uses, below zoom 11', async () => {
    const stubPath = { __stub: 'land' };
    landMaskGet.mockReturnValue(stubPath);
    currentMap = makeMap({ zoom: 9.4, panes: markerPanes() });
    await mount();
    expect(ctxStub.stroke).toHaveBeenCalledWith(stubPath);
    expect(ctxStub.lineWidth).toBe(0.8);
    expect(ctxStub.strokeStyle).toMatch(/^rgba\(242,231,211,/);
  });

  it('fades the stroke to nothing by zoom 11, even though the clip is still active', async () => {
    const stubPath = { __stub: 'land' };
    landMaskGet.mockReturnValue(stubPath);
    currentMap = makeMap({ zoom: 11, panes: markerPanes() });
    await mount();
    expect(ctxStub.stroke).not.toHaveBeenCalled();
    // The clip itself is still on at zoom 11 (< 11.5) — only the stroke's OWN, tighter fade has
    // reached zero.
    expect(drawTiles.mock.calls.at(-1)[4].clipSoft).toBe(4);
  });

  it('still strokes the coast with an empty point set', async () => {
    const stubPath = { __stub: 'land' };
    landMaskGet.mockReturnValue(stubPath);
    currentMap = makeMap({ zoom: 9, panes: markerPanes() });
    await mount({ points: [] });
    expect(ctxStub.stroke).toHaveBeenCalledWith(stubPath);
  });

  it('draws no stroke while the topology has not resolved, even below zoom 11', async () => {
    currentMap = makeMap({ zoom: 9, panes: markerPanes() });
    await mount();
    expect(ctxStub.stroke).not.toHaveBeenCalled();
  });
});

/**
 * Reach rings (map-tab-v2-plan.md §3 P8) — dashed circles at the shared 25/50 mi tiers around
 * home. `radiusFor` is spied (see the file header), so its mocked return value stands in for a
 * real projection; these tests assert it was ASKED for the right metres, not that the arithmetic
 * inside it is correct (that is `heatField.test.js`'s job).
 */
describe('MapHeatLayer — reach rings (map-tab-v2-plan.md §3 P8)', () => {
  const HOME = { lat: 54.9, lon: -1.4 };

  function makeRingCtxStub() {
    return {
      setTransform: vi.fn(),
      save: vi.fn(),
      restore: vi.fn(),
      translate: vi.fn(),
      stroke: vi.fn(),
      clearRect: vi.fn(),
      beginPath: vi.fn(),
      arc: vi.fn(),
      setLineDash: vi.fn(),
    };
  }

  /**
   * A `latLngToContainerPoint` giving exactly 1 px per km at HOME's own latitude — chosen so a
   * ring's radius in px is simply its km figure (40.2336 / 80.4672), comfortably clear of both
   * skip rules (`RING_MIN_PX` 18, the off-frame ceiling on an 800×500 default map) without the
   * test needing to reproduce real Web Mercator arithmetic. `center` is accepted but IGNORED — the
   * whole point of the fix under test is that ring radius no longer depends on it.
   */
  function ringFriendlyProjection() {
    return ([lat, lng]) => ({ x: (lng + 3) * 10, y: (56 - lat) * 111.2 });
  }

  let ctxStub;

  beforeEach(() => {
    ctxStub = makeRingCtxStub();
    HTMLCanvasElement.prototype.getContext = () => ctxStub;
    radiusFor.mockImplementation((_map, metres) => metres / 100);
    // Deterministic regardless of what an earlier describe block's OWN tests last left this at —
    // every test in this block that wants a coastline stroke sets it explicitly.
    landMaskGet.mockReset();
    landMaskGet.mockReturnValue(null);
  });

  it('draws nothing at all without `rings`, without `homeCoords`, or above the zoom gate', async () => {
    currentMap = makeMap({ zoom: 9, panes: markerPanes() });
    currentMap.latLngToContainerPoint = ringFriendlyProjection();
    await mount({ homeCoords: HOME, rings: false });
    expect(ctxStub.arc).not.toHaveBeenCalled();

    currentMap = makeMap({ zoom: 9, panes: markerPanes() });
    currentMap.latLngToContainerPoint = ringFriendlyProjection();
    await mount({ rings: true });
    expect(ctxStub.arc).not.toHaveBeenCalled();

    currentMap = makeMap({ zoom: 10.6, panes: markerPanes() });
    currentMap.latLngToContainerPoint = ringFriendlyProjection();
    await mount({ homeCoords: HOME, rings: true });
    expect(ctxStub.arc).not.toHaveBeenCalled();
  });

  it('draws exactly the two shared tiers, below the gate, home-gated and toggle-gated, sized at 1 px/km', async () => {
    currentMap = makeMap({ zoom: 9, panes: markerPanes() });
    currentMap.latLngToContainerPoint = ringFriendlyProjection();
    await mount({ homeCoords: HOME, rings: true });
    // P4 RE-PIN's own "2-BY-DESIGN" count (mount paint + load-resolve repaint, see the file
    // header's note on "hands the kernel §4.5's dials") means the WHOLE paint callback — rings
    // included — runs twice in this harness: two rings × two paints.
    expect(ctxStub.beginPath).toHaveBeenCalledTimes(4);
    expect(ctxStub.arc).toHaveBeenCalledTimes(4);
    // Radii are `ring.km * pxPerKmAtHome`, never `radiusFor` — this harness's projection makes
    // pxPerKmAtHome exactly 1, so the radius IS the km figure from `utils/reachRings.js`.
    const radii = ctxStub.arc.mock.calls.map((call) => call[2]);
    expect(radii).toEqual(expect.arrayContaining([
      expect.closeTo(25 * 1.609344, 5),
      expect.closeTo(50 * 1.609344, 5),
    ]));
  });

  it('centres both rings on the home point, via latLngToContainerPoint — not a raw lat/lng', async () => {
    currentMap = makeMap({ zoom: 9, panes: markerPanes() });
    currentMap.latLngToContainerPoint = ringFriendlyProjection();
    await mount({ homeCoords: HOME, rings: true });
    const expectedPoint = currentMap.latLngToContainerPoint([HOME.lat, HOME.lon]);
    for (const call of ctxStub.arc.mock.calls) {
      expect(call[0]).toBe(expectedPoint.x);
      expect(call[1]).toBe(expectedPoint.y);
      expect(call[3]).toBe(0);
      expect(call[4]).toBe(Math.PI * 2);
    }
  });

  it('strokes dashed, at the shared colour and width — never the coastline\'s own style', async () => {
    currentMap = makeMap({ zoom: 9, panes: markerPanes() });
    currentMap.latLngToContainerPoint = ringFriendlyProjection();
    await mount({ homeCoords: HOME, rings: true });
    expect(ctxStub.setLineDash).toHaveBeenCalledWith([3, 4]);
    expect(ctxStub.lineWidth).toBe(1);
    expect(ctxStub.strokeStyle).toBe('rgba(201,162,75,.42)');
  });

  it('never touches `arc` when `homeCoords` is only half-present', async () => {
    currentMap = makeMap({ zoom: 9, panes: markerPanes() });
    currentMap.latLngToContainerPoint = ringFriendlyProjection();
    await mount({ homeCoords: { lat: 54.9, lon: null }, rings: true });
    expect(ctxStub.arc).not.toHaveBeenCalled();
  });

  it('is INVARIANT under a changed map centre at fixed zoom — the bug the fix removes', async () => {
    // Two maps, identical zoom and home, but a `getCenter()` that answers a wildly different
    // point — the OLD implementation (`radiusFor`, which reads `map.getCenter()`) would have sized
    // the ring off whichever one this returned; the fix reads `homeCoords` instead and must never
    // even look at `getCenter()` for the ring radius. `mockLandLoaded` is reset before EACH mount
    // so this harness's own "2-BY-DESIGN" double-paint quirk (mount paint + load-resolve repaint,
    // see the sibling test above) fires identically both times — otherwise the second mount would
    // see `land()` already resolved from the first and paint once instead of twice, which is a
    // fact about this test's plumbing, not about ring geometry, and would make an unrelated call-
    // count difference look like an invariance failure.
    mockLandLoaded = false;
    currentMap = makeMap({ zoom: 9, panes: markerPanes() });
    currentMap.latLngToContainerPoint = ringFriendlyProjection();
    currentMap.centre = { lat: 54.9, lng: -1.4 }; // centred ON home
    await mount({ homeCoords: HOME, rings: true });
    const radiiAtHomeCentre = ctxStub.arc.mock.calls.map((call) => call[2]);

    mockLandLoaded = false;
    currentMap = makeMap({ zoom: 9, panes: markerPanes() });
    currentMap.latLngToContainerPoint = ringFriendlyProjection();
    currentMap.centre = { lat: 58.5, lng: -4.5 }; // panned far north — a real Web Mercator ring
    // measured at the OLD reference point would be a materially different size here
    ctxStub = makeRingCtxStub();
    HTMLCanvasElement.prototype.getContext = () => ctxStub;
    await mount({ homeCoords: HOME, rings: true });
    const radiiPannedAway = ctxStub.arc.mock.calls.map((call) => call[2]);

    expect(radiiPannedAway).toEqual(radiiAtHomeCentre);
  });

  it('skips a ring below the shared RING_MIN_PX floor, the same rule WindowRowFieldMap applies', async () => {
    currentMap = makeMap({ zoom: 9, panes: markerPanes() });
    // 0.1 px/km at HOME's latitude: 25mi -> ~4.0px, 50mi -> ~8.0px — both under the 18px floor.
    currentMap.latLngToContainerPoint = ([lat, lng]) => ({ x: (lng + 3) * 10, y: (56 - lat) * 11.12 });
    await mount({ homeCoords: HOME, rings: true });
    expect(ctxStub.arc).not.toHaveBeenCalled();
  });

  it('skips a ring past the off-frame ceiling (1.15× the frame\'s larger side)', async () => {
    // A tiny 100×80 map with the SAME 1 px/km projection: 25mi (~40px) still fits inside
    // 100*1.15=115, but 50mi (~80px) sits right at the edge, so scale the projection up instead of
    // relying on a coincidence — 10 px/km makes 25mi ~402px (off-frame) and 50mi ~805px (off-frame)
    // against a 100×80 canvas, proving BOTH tiers can be suppressed by the ceiling, not merely one.
    currentMap = makeMap({ zoom: 9, size: { x: 100, y: 80 }, panes: markerPanes() });
    currentMap.latLngToContainerPoint = ([lat, lng]) => ({ x: (lng + 3) * 10, y: (56 - lat) * 1112 });
    await mount({ homeCoords: HOME, rings: true });
    expect(ctxStub.arc).not.toHaveBeenCalled();
  });

  it('draws the coastline stroke BEFORE the ring arcs, in the same paint pass', async () => {
    // Both active in one pass: a real coast path (so the stroke fires) at a zoom below both the
    // coastline's own fade (11) and the ring gate (10.6).
    const stubPath = { __stub: 'land' };
    landMaskGet.mockReturnValue(stubPath);
    currentMap = makeMap({ zoom: 9, panes: markerPanes() });
    currentMap.latLngToContainerPoint = ringFriendlyProjection();
    await mount({ homeCoords: HOME, rings: true });
    // `ctx.stroke(landPath)` (the coast) takes an argument; `ctx.stroke()` (an arc's own path,
    // built by the preceding `beginPath`/`arc` pair) takes none — the two are told apart by that.
    // Each PAINT strokes coast-then-ring-then-ring (one coast call, two ring calls), and this
    // harness's own "2-BY-DESIGN" quirk (mount paint + load-resolve repaint, see the sibling test
    // above) runs that whole sequence twice — so the calls are checked three at a time, per paint,
    // rather than by a single global before/after split that a second paint's own coast call would
    // trivially violate (it lands after the FIRST paint's ring calls, which is correct ordering
    // WITHIN a paint but looks like a reordering across two flattened paints).
    const strokeCalls = ctxStub.stroke.mock.calls;
    expect(strokeCalls.length).toBe(6); // 2 paints × (1 coast + 2 rings)
    for (let i = 0; i < strokeCalls.length; i += 3) {
      expect(strokeCalls[i]).toEqual([stubPath]);
      expect(strokeCalls[i + 1]).toEqual([]);
      expect(strokeCalls[i + 2]).toEqual([]);
    }
  });
});

describe('MapHeatLayer — mount paint', () => {
  beforeEach(() => {
    landMaskGet.mockReset();
    landMaskGet.mockReturnValue(null);
    landMaskInvalidate.mockReset();
    HTMLCanvasElement.prototype.getContext = () => makeCanvasCtxStub();
  });

  it('paints the field on mount, with no switch to withhold it', async () => {
    currentMap = makeMap({ zoom: 9, panes: markerPanes() });
    await mount();
    expect(drawTiles).toHaveBeenCalled();
  });
});

/**
 * `dim` — Ask PhotoCast's picks step the field's FILL back (F3, plan §1 #14 / §2.7).
 *
 * <p>The field, the coastline stroke and the reach rings are painted on ONE canvas, which is why the
 * dim is a factor inside the draw and not an opacity on the pane: a pane opacity would dim the
 * coastline too, and a test of "the heat looks dimmer" could not tell the two apart. These tests read
 * what the draw was handed and what the stroke was painted with.
 */
describe('MapHeatLayer — dim (Ask PhotoCast’s picks)', () => {
  let ctxStub;

  beforeEach(() => {
    landMaskGet.mockReset();
    landMaskGet.mockReturnValue({ __stub: 'land' });
    landMaskInvalidate.mockReset();
    ctxStub = makeCanvasCtxStub();
    HTMLCanvasElement.prototype.getContext = () => ctxStub;
  });

  it('leaves the fill exactly as it was by default (dim 1)', async () => {
    currentMap = makeMap({ zoom: 9, panes: markerPanes() });
    await mount();
    expect(drawTiles.mock.calls.at(-1)[4].opacity).toBeCloseTo(0.92, 5);
  });

  it('multiplies the fill by dim — and multiplies the zoom fade rather than replacing it', async () => {
    currentMap = makeMap({ zoom: 9, panes: markerPanes() });
    await mount({ dim: 0.36 });
    expect(drawTiles.mock.calls.at(-1)[4].opacity).toBeCloseTo(0.92 * 0.36, 5);

    drawTiles.mockClear();
    currentMap = makeMap({ zoom: 13, panes: markerPanes() });
    await mount({ dim: 0.36 });
    expect(drawTiles.mock.calls.at(-1)[4].opacity).toBeCloseTo(0.92 * 0.35 * 0.36, 5);
  });

  it('⚠️ leaves the coastline stroke exactly as strong as without it', async () => {
    currentMap = makeMap({ zoom: 9, panes: markerPanes() });
    await mount();
    const undimmed = ctxStub.strokeStyle;
    expect(undimmed).toMatch(/^rgba\(242,231,211,/);

    ctxStub.stroke.mockClear();
    document.body.innerHTML = '';
    currentMap = makeMap({ zoom: 9, panes: markerPanes() });
    await mount({ dim: 0.36 });

    expect(ctxStub.stroke).toHaveBeenCalled();
    expect(ctxStub.strokeStyle).toBe(undimmed);
  });

  it('repaints when dim changes — the field is a picture and nothing else would redraw it', async () => {
    currentMap = makeMap({ zoom: 9, panes: markerPanes() });
    const view = await mount({ dim: 1 });
    drawTiles.mockClear();

    await act(async () => { view.rerender(<MapHeatLayer points={POINTS} conf={1} dim={0.36} />); });
    await act(async () => { runFrames(); });

    expect(drawTiles).toHaveBeenCalled();
    expect(drawTiles.mock.calls.at(-1)[4].opacity).toBeCloseTo(0.92 * 0.36, 5);
  });

  it('draws the field at full strength again once the dim is lifted', async () => {
    currentMap = makeMap({ zoom: 9, panes: markerPanes() });
    const view = await mount({ dim: 0.36 });

    await act(async () => { view.rerender(<MapHeatLayer points={POINTS} conf={1} dim={1} />); });
    await act(async () => { runFrames(); });

    expect(drawTiles.mock.calls.at(-1)[4].opacity).toBeCloseTo(0.92, 5);
  });
});

describe('MapHeatLayer — the stand-down hatch (big-seas plan F3)', () => {
  const STOOD = [{ lat: 54.9, lng: -2.1, rid: 'North East' }];
  beforeEach(() => {
    landMaskGet.mockReset();
    landMaskGet.mockReturnValue(null);
    HTMLCanvasElement.prototype.getContext = () => makeCanvasCtxStub();
  });

  it('hands the kernel the stood places and the 6500 m, 22-150 px disc radius', async () => {
    currentMap = makeMap({ panes: markerPanes() });
    await mount({ stood: STOOD });
    const opts = drawTiles.mock.calls.at(-1)[4];
    expect(opts.stood).toBe(STOOD);
    expect(opts.standRadius).toBe(120); // `radiusFor` is stubbed to 120
    expect(radiusFor).toHaveBeenCalledWith(currentMap, 6500, 22, 150);
    // The field's own radius is unchanged.
    expect(radiusFor).toHaveBeenCalledWith(currentMap, 7200, 30, 400);
  });

  it('carries the field\'s opacity, so the fade and Ask\'s dim reach the hatch', async () => {
    currentMap = makeMap({ panes: markerPanes() });
    await mount({ stood: STOOD, dim: 1 });
    const full = drawTiles.mock.calls.at(-1)[4].opacity;
    await mount({ stood: STOOD, dim: 0.5 });
    expect(drawTiles.mock.calls.at(-1)[4].opacity).toBeCloseTo(full * 0.5, 5);
  });

  it.each([[null], [[]], [undefined]])('leaves the options object as it was for %j', async (stood) => {
    currentMap = makeMap({ panes: markerPanes() });
    await mount({ stood });
    expect('stood' in drawTiles.mock.calls.at(-1)[4]).toBe(false);
    expect('standRadius' in drawTiles.mock.calls.at(-1)[4]).toBe(false);
  });
});
