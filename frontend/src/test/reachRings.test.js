import { describe, it, expect, vi } from 'vitest';
import {
  MI_TO_KM, RING_TIERS, RING_MIN_PX, pxPerKmAtHome,
} from '../utils/reachRings.js';

/**
 * The extracted tiers (map-tab-v2-plan.md §3 P8, decision D-4) — pinned here so a future edit to
 * either host's own file cannot silently change what "25 mi" or "50 mi" means without this test
 * catching it. `WindowRowFieldMap.test.jsx`'s existing ring assertions are the proof the
 * extraction changed nothing there; this file is the proof of what the shared values actually are.
 */
describe('reachRings — the shared 25/50 mi tiers (map-tab-v2-plan.md §3 P8, D-4)', () => {
  it('is exactly 1.609344 km per mile — the SI/international definition', () => {
    expect(MI_TO_KM).toBeCloseTo(1.609344, 10);
  });

  it('carries exactly two tiers: 25 mi / 45 min, then 50 mi / 90 min', () => {
    expect(RING_TIERS).toHaveLength(2);
    expect(RING_TIERS[0].mi).toBe(25);
    expect(RING_TIERS[0].minutes).toBe(45);
    expect(RING_TIERS[1].mi).toBe(50);
    expect(RING_TIERS[1].minutes).toBe(90);
  });

  it('derives km from mi at the definition site — not the bundle\'s own 36/72 km (declined, D-4)', () => {
    expect(RING_TIERS[0].km).toBeCloseTo(25 * 1.609344, 10);
    expect(RING_TIERS[1].km).toBeCloseTo(50 * 1.609344, 10);
    // Visually indistinguishable from the earlier 40/80 km, but not equal to them or to the
    // bundle's 36/72 km — the point of pinning the exact figure.
    expect(RING_TIERS[0].km).toBeCloseTo(40.2336, 4);
    expect(RING_TIERS[1].km).toBeCloseTo(80.4672, 4);
  });

  it('RING_MIN_PX is 18 — a ring this small on screen is illegible, not a dot', () => {
    expect(RING_MIN_PX).toBe(18);
  });
});

/**
 * A faithful (if compact) real spherical-Mercator projection — Leaflet's own EPSG:3857 formula —
 * so a test can prove a real behavioural property (panning does not change a HOME-centred ring's
 * size) rather than a property that would also hold for an unrealistic linear stub. `zoom` and
 * `center` behave exactly as a real Leaflet map's would: the same lat/lng projects to a DIFFERENT
 * container point depending on `center` (a pan), while the delta between two points at the SAME
 * longitude and a fixed latitude gap is a function of latitude and zoom alone.
 */
function realisticMercatorMap({ zoom, center, size = { x: 800, y: 500 } }) {
  const R = 6378137;
  const project = (lat, lng) => {
    const d = Math.PI / 180;
    const max = 1 - 1e-15;
    const sin = Math.max(-max, Math.min(max, Math.sin(lat * d)));
    return { x: R * lng * d, y: (R * Math.log((1 + sin) / (1 - sin))) / 2 };
  };
  const worldPx = 256 * 2 ** zoom;
  const scale = worldPx / (2 * Math.PI * R);
  const toPixel = (lat, lng) => {
    const p = project(lat, lng);
    return { x: p.x * scale + worldPx / 2, y: worldPx / 2 - p.y * scale };
  };
  const centrePx = toPixel(center.lat, center.lng);
  const originX = centrePx.x - size.x / 2;
  const originY = centrePx.y - size.y / 2;
  return {
    getZoom: () => zoom,
    getCenter: () => center,
    latLngToContainerPoint: ([lat, lng]) => {
      const p = toPixel(lat, lng);
      return { x: p.x - originX, y: p.y - originY };
    },
  };
}

describe('reachRings — pxPerKmAtHome (map-tab-v2-plan.md §3 P8 review)', () => {
  const HOME = { lat: 54.9, lon: -1.4 };

  it('calls latLngToContainerPoint at home and home+1° latitude, on the SAME longitude', () => {
    const calls = [];
    const map = {
      latLngToContainerPoint: (latlng) => {
        calls.push(latlng);
        return { x: 0, y: latlng[0] * -10 };
      },
    };
    pxPerKmAtHome(map, HOME);
    expect(calls).toEqual([[HOME.lat, HOME.lon], [HOME.lat + 1, HOME.lon]]);
  });

  it('is |Δy| / 111.2 — the same "measure a real 1° delta" idiom as heatField.kmPerPx', () => {
    const map = { latLngToContainerPoint: ([lat]) => ({ x: 0, y: (60 - lat) * 50 }) };
    // Δy for a 1° step at this map's own scale is 50; 50/111.2 is the expected px/km.
    expect(pxPerKmAtHome(map, HOME)).toBeCloseTo(50 / 111.2, 10);
  });

  it('never calls getCenter() — the mechanism of the fix, not just its output', () => {
    const getCenter = vi.fn(() => ({ lat: 0, lng: 0 }));
    const map = {
      getCenter,
      latLngToContainerPoint: ([lat]) => ({ x: 0, y: lat * -10 }),
    };
    pxPerKmAtHome(map, HOME);
    expect(getCenter).not.toHaveBeenCalled();
  });

  it('is INVARIANT under a changed map centre at fixed zoom, against a REAL Web Mercator projection', () => {
    const centredOnHome = realisticMercatorMap({ zoom: 9, center: { lat: HOME.lat, lng: HOME.lon } });
    const pannedNorth = realisticMercatorMap({ zoom: 9, center: { lat: 58.5, lng: -4.5 } });
    expect(pxPerKmAtHome(pannedNorth, HOME)).toBeCloseTo(pxPerKmAtHome(centredOnHome, HOME), 9);
  });

  it('DOES vary with latitude, at fixed zoom — proving the stub is not degenerately linear', () => {
    const atNorthernHome = pxPerKmAtHome(
      realisticMercatorMap({ zoom: 9, center: { lat: 60, lng: -1.4 } }),
      { lat: 60, lon: -1.4 },
    );
    const atEquatorialHome = pxPerKmAtHome(
      realisticMercatorMap({ zoom: 9, center: { lat: 0, lng: -1.4 } }),
      { lat: 0, lon: -1.4 },
    );
    // Web Mercator's ground resolution shrinks (more px per km) at higher latitudes — this is
    // exactly the property that makes measuring at the VIEWPORT centre wrong for a ring fixed at
    // a different latitude: the scale genuinely is not constant across the map.
    expect(atNorthernHome).toBeGreaterThan(atEquatorialHome);
  });
});
