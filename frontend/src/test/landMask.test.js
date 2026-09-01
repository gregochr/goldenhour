import {
  describe, it, expect, vi, beforeEach,
} from 'vitest';

/**
 * The land clip's Path2D cache (map-tab-v2-plan.md §3 P4).
 *
 * <p>`heatField.js`'s `land()` is mocked rather than driven through the real `load()`: the module
 * under test only ever reads the resolved topology through that one function, and mocking it lets
 * every case here control "topology not here yet" vs "topology arrived" directly, without a real
 * dynamic import of the vendored asset. `heatField.test.js` and `MapHeatLayer.test.jsx` own the
 * asset-loading and kernel-drawing concerns respectively; this file owns the cache.
 *
 * <p>⚠️ jsdom has no `Path2D` at all, so every test injects a recording stub via `path2DCtor`
 * rather than letting the module fall through to `window.Path2D` — see the "never touches
 * window.Path2D when injected" case below, which proves the discipline rather than assuming it.
 */
let mockLand = null;
vi.mock('../utils/heatField.js', () => ({ land: () => mockLand }));

import { createLandMask } from '../utils/landMask.js';

/** A minimal, deterministic FeatureCollection — a single rough quadrilateral near the North East. */
const FIXTURE = {
  type: 'FeatureCollection',
  features: [
    {
      type: 'Feature',
      properties: {},
      geometry: {
        type: 'Polygon',
        coordinates: [[[-2, 54], [-1, 54], [-1, 55], [-2, 55], [-2, 54]]],
      },
    },
  ],
};

/**
 * A Leaflet-map stand-in carrying only what `landMask.js` reads: `getZoom` and `project`. The
 * projection is a simple zoom-dependent scale — not real Web Mercator — chosen only so that two
 * different zooms are provably different projections, which is what the cache-key tests need.
 */
function makeMap({ zoom = 8 } = {}) {
  return {
    zoom,
    getZoom() { return this.zoom; },
    project(latlng, z) {
      const scale = 2 ** z;
      // latlng is [lat, lng] (Leaflet's own convention); the transform swaps it back to [lng, lat]
      // before calling this, so index 0 here is lat and 1 is lng — matching what `landMask.js`
      // sends.
      return { x: latlng[1] * scale, y: latlng[0] * scale };
    },
  };
}

/**
 * Stands in for the browser's `Path2D` — records every SVG path-data string it was built with,
 * so a test can assert both IDENTITY (same instance vs a new one) and CONTENT (the same string
 * rebuilt) without jsdom ever needing a real Path2D.
 */
function makeRecordingCtor() {
  const built = [];
  function StubPath2D(d) {
    this.d = d;
    built.push(d);
  }
  StubPath2D.built = built;
  return StubPath2D;
}

describe('landMask — createLandMask', () => {
  beforeEach(() => {
    mockLand = null;
  });

  it('returns null before the topology has resolved', () => {
    const mask = createLandMask(makeMap(), { path2DCtor: makeRecordingCtor() });
    expect(mask.get()).toBeNull();
  });

  it('returns null with no Path2D constructor available, even once the topology has loaded', () => {
    mockLand = FIXTURE;
    // jsdom itself carries no `window.Path2D`, so an explicit `undefined` here reproduces exactly
    // what an old browser would hand the default parameter — declining gracefully, never throwing.
    const mask = createLandMask(makeMap(), { path2DCtor: undefined });
    expect(mask.get()).toBeNull();
  });

  it('builds a Path2D once the topology is available', () => {
    mockLand = FIXTURE;
    const ctor = makeRecordingCtor();
    const mask = createLandMask(makeMap({ zoom: 8 }), { path2DCtor: ctor });
    const path = mask.get();
    expect(path).toBeInstanceOf(ctor);
    expect(ctor.built).toHaveLength(1);
    expect(typeof ctor.built[0]).toBe('string');
    expect(ctor.built[0].length).toBeGreaterThan(0);
  });

  it('never touches window.Path2D when a constructor is injected', () => {
    mockLand = FIXTURE;
    const ctor = makeRecordingCtor();
    const originalWindowPath2D = window.Path2D;
    // A poisoned global: if the module ever fell through to `window.Path2D` despite an explicit
    // injection, constructing it would throw and fail this test loudly rather than silently.
    window.Path2D = function PoisonedPath2D() {
      throw new Error('landMask must not construct window.Path2D when a ctor is injected');
    };
    try {
      const mask = createLandMask(makeMap({ zoom: 8 }), { path2DCtor: ctor });
      expect(() => mask.get()).not.toThrow();
      expect(mask.get()).toBeInstanceOf(ctor);
    } finally {
      window.Path2D = originalWindowPath2D;
    }
  });

  it('defaults to window.Path2D when no constructor is supplied at all', () => {
    mockLand = FIXTURE;
    const ctor = makeRecordingCtor();
    const originalWindowPath2D = window.Path2D;
    window.Path2D = ctor;
    try {
      const mask = createLandMask(makeMap({ zoom: 8 }));
      expect(mask.get()).toBeInstanceOf(ctor);
    } finally {
      window.Path2D = originalWindowPath2D;
    }
  });

  it('returns the SAME Path2D identity for repeated calls at the same zoom', () => {
    mockLand = FIXTURE;
    const ctor = makeRecordingCtor();
    const mask = createLandMask(makeMap({ zoom: 8 }), { path2DCtor: ctor });
    const first = mask.get();
    const second = mask.get();
    expect(second).toBe(first);
    expect(ctor.built).toHaveLength(1);
  });

  it('rebuilds when the zoom changes', () => {
    mockLand = FIXTURE;
    const ctor = makeRecordingCtor();
    const map = makeMap({ zoom: 8 });
    const mask = createLandMask(map, { path2DCtor: ctor });
    const first = mask.get();
    map.zoom = 9;
    const second = mask.get();
    expect(second).not.toBe(first);
    expect(ctor.built).toHaveLength(2);
    // The two zooms genuinely project differently (the fixture map's own scale), so the rebuild is
    // not merely a new instance of the same content.
    expect(ctor.built[0]).not.toBe(ctor.built[1]);
  });

  it('goes back to the earlier zoom\'s content after a rebuild — the cache key is the zoom, not a monotonic counter', () => {
    mockLand = FIXTURE;
    const ctor = makeRecordingCtor();
    const map = makeMap({ zoom: 8 });
    const mask = createLandMask(map, { path2DCtor: ctor });
    const atEight = mask.get();
    map.zoom = 9;
    mask.get();
    map.zoom = 8;
    const backToEight = mask.get();
    expect(backToEight).not.toBe(atEight); // a fresh instance — nothing is memoised across a miss
    expect(ctor.built[2]).toBe(ctor.built[0]); // but the same zoom re-derives the same geometry
  });

  it('invalidate() forces a rebuild even at the SAME zoom', () => {
    mockLand = FIXTURE;
    const ctor = makeRecordingCtor();
    const map = makeMap({ zoom: 8 });
    const mask = createLandMask(map, { path2DCtor: ctor });
    const first = mask.get();
    mask.invalidate();
    const second = mask.get();
    expect(second).not.toBe(first);
    expect(ctor.built).toHaveLength(2);
    // Same zoom, same geometry — the rebuilt path's own content is identical even though the
    // Path2D instance is a new one.
    expect(ctor.built[1]).toBe(ctor.built[0]);
  });

  it('invalidate() before the topology has resolved is a harmless no-op', () => {
    const mask = createLandMask(makeMap(), { path2DCtor: makeRecordingCtor() });
    expect(() => mask.invalidate()).not.toThrow();
    expect(mask.get()).toBeNull();
  });
});
