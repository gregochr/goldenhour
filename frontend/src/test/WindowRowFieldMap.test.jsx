import React from 'react';
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { act, cleanup, render, screen, fireEvent } from '@testing-library/react';
import WindowRowFieldMap, {
  MAP_ASPECT_MAX, MAP_ASPECT_MAX_PHONE, MAP_ASPECT_MIN, MAP_ASPECT_MIN_PHONE,
} from '../components/WindowRowFieldMap.jsx';
import { bbox, drawGeo, land, load } from '../utils/heatField.js';
import { useIsMobile } from '../hooks/useIsMobile.js';
import { POINT_SCORE_INDEX } from '../utils/heatSpots.js';

/**
 * The open row's full-width field map.
 *
 * <h2>What this file can and cannot see</h2>
 *
 * <p>jsdom implements no layout and no canvas, so the FIELD is not asserted here — the kernel's own
 * arithmetic is pinned in {@code heatField.test.js} and what the canvas paints is browser-verified.
 * What is asserted is what the map is for besides the picture: the dials it hands the kernel, the
 * focus it passes on a selection, where the labels go, and the click geometry — which is real
 * arithmetic and the one part of this component a test can prove.
 *
 * <p>{@code drawGeo} is mocked at the kernel boundary and returns a stub PROJECTION, because the
 * labels and the click test are both functions of it. The stub is linear and deliberately trivial:
 * a real d3 Mercator would make every expected pixel a magic number nobody could check.
 */

vi.mock('../utils/heatField.js', async (importOriginal) => {
  const actual = await importOriginal();
  return {
    ...actual,
    load: vi.fn(() => Promise.resolve({ type: 'FeatureCollection', features: [] })),
    land: vi.fn(() => ({ type: 'FeatureCollection', features: [] })),
    // [lng, lat] → [x, y] with a 10× scale and no inversion, so a spot at lng 4 / lat 6 lands at
    // (40, 60) and every assertion below is checkable by hand.
    drawGeo: vi.fn(() => ([lng, lat]) => [lng * 10, lat * 10]),
  };
});

// `setup.js` stubs `matchMedia` to `matches: false` for the whole suite, so the phone branch is
// unreachable by rendering. Mocked at the hook boundary — the same "mock where the decision is made"
// rule the suite applies to API modules.
vi.mock('../hooks/useIsMobile.js', () => ({ useIsMobile: vi.fn(() => false) }));

const TODAY = '2026-08-04';
const KEY = '2026-08-04:SUNSET';

function spot(overrides = {}) {
  return {
    id: 1, name: 'Bamburgh', lat: 6, lng: 4, regionName: 'Coast', rid: 'Coast', scores: [4],
    ...overrides,
  };
}

/** Two regions two hundred pixels apart under the stub projection: Coast at (40, 60), Dales at (240, 60). */
const SPOTS = [spot(), spot({ id: 2, name: 'Ladybower', lng: 24, regionName: 'Dales', rid: 'Dales' })];
const REGIONS = ['Coast', 'Dales'];
/** One scored point, in Coast — a module constant so a re-render can keep its identity. */
const POINTS = [{ lat: 6, lng: 4, rid: 'Coast', r: [4] }];

let originalGetContext;
beforeEach(() => {
  originalGetContext = HTMLCanvasElement.prototype.getContext;
  HTMLCanvasElement.prototype.getContext = () => ({});
  // Re-asserted per test rather than restored inline after the one test that changes them.
  // `vi.clearAllMocks()` clears CALLS, not implementations, so a `land` left returning null by an
  // assertion that threw would fail every later test in the file for an unrelated reason.
  land.mockImplementation(() => ({ type: 'FeatureCollection', features: [] }));
  load.mockImplementation(() => Promise.resolve({ type: 'FeatureCollection', features: [] }));
});
afterEach(() => {
  HTMLCanvasElement.prototype.getContext = originalGetContext;
  vi.clearAllMocks();
});

/** Gives every element a measurable content box; jsdom reports 0 for all of them. */
async function withMeasuredMap(px, run) {
  const original = Object.getOwnPropertyDescriptor(Element.prototype, 'clientWidth');
  Object.defineProperty(Element.prototype, 'clientWidth', { configurable: true, get: () => px });
  try {
    // `return await`, not `return run()`: the awaited form completes before `finally` restores the
    // real descriptor, where the bare return hands the promise back and every assertion inside then
    // reads jsdom's 0. The strip's own helper records the same trap.
    return await run();
  } finally {
    if (original) Object.defineProperty(Element.prototype, 'clientWidth', original);
    else delete Element.prototype.clientWidth;
  }
}

async function renderMap(props = {}) {
  const onSelectRegion = vi.fn();
  await act(async () => {
    render(
      <WindowRowFieldMap
        windowKey={KEY}
        date={TODAY}
        confidence="high"
        spots={SPOTS}
        points={POINTS}
        bestRating={4}
        regionNames={REGIONS}
        selectedRegion={null}
        todayStr={TODAY}
        onSelectRegion={onSelectRegion}
        {...props}
      />,
    );
  });
  return { onSelectRegion };
}

/** Puts a real client box on the canvas so the click test has a frame to measure against. */
function stubCanvasBox(width) {
  const canvas = screen.getByTestId('wf-row-map-canvas');
  canvas.getBoundingClientRect = () => ({
    left: 0, top: 0, width, height: width * 0.5, right: width, bottom: width * 0.5, x: 0, y: 0,
  });
  return canvas;
}

describe('WindowRowFieldMap — what it hands the kernel', () => {
  it('paints this window’s points at the row dials and at POINT_SCORE_INDEX', async () => {
    await withMeasuredMap(600, async () => {
      await renderMap();
    });
    expect(drawGeo).toHaveBeenCalled();
    const [, width, , points, index, opts] = drawGeo.mock.calls.at(-1);
    expect(width).toBe(600);
    expect(points).toHaveLength(1);
    expect(index).toBe(POINT_SCORE_INDEX);
    expect(opts.grid).toBe(6);
    expect(opts.blur).toBe(3.6);
    // max(20, 600 × 0.072) = 43.2
    expect(opts.radius).toBeCloseTo(43.2, 5);
  });

  it('holds the radius at its floor on a narrow map', async () => {
    await withMeasuredMap(100, async () => {
      await renderMap();
    });
    expect(drawGeo.mock.calls.at(-1)[5].radius).toBe(20);
  });

  it('passes no focus while every region is shown', async () => {
    // The kernel tests `opts.focus` for TRUTHINESS, so an empty string here would silently paint
    // the unfocused field — which is why `heatSpots.js` guarantees a non-blank region name.
    await withMeasuredMap(600, async () => {
      await renderMap({ selectedRegion: null });
    });
    expect(drawGeo.mock.calls.at(-1)[5].focus).toBeUndefined();
  });

  it('passes the selected region as the focus, byte-identically', async () => {
    // Nothing trims or normalises a region name in this join: the kernel answers a focus matching no
    // point by multiplying EVERY weight by 1e-4, so the whole canvas fades to transparent with
    // nothing in the console.
    // The POINT carries the leading space too, so the guard passes and the value reaching the kernel
    // is the one under test. If anything trimmed either side, the two would stop matching, the guard
    // would withhold, and this assertion would fail — which is the byte-identity claim, tested
    // through the mechanism that depends on it rather than beside it.
    await withMeasuredMap(600, async () => {
      await renderMap({
        regionNames: [' Coast', 'Dales'],
        selectedRegion: ' Coast',
        points: [{ lat: 6, lng: 4, rid: ' Coast', r: [4] }],
      });
    });
    expect(drawGeo.mock.calls.at(-1)[5].focus).toBe(' Coast');
  });

  it('withholds a focus NO POINT carries, rather than fading the whole field away', async () => {
    // The two populations disagree: the rail's names come from the briefing payload (which keeps a
    // region whose slots are all unrated) and the points come from `heatPointsFor` (which drops
    // every unscored spot). The kernel answers a focus matching nothing by multiplying EVERY weight
    // by 1e-4 — the whole canvas paints transparent, the other regions' heat goes with it, and
    // nothing appears in the console. `heatField.test.js` pins that outcome directly.
    await withMeasuredMap(600, async () => {
      await renderMap({ selectedRegion: 'Dales' }); // points carry only 'Coast'
    });
    expect(drawGeo.mock.calls.at(-1)[5].focus).toBeUndefined();
  });

  it('passes the focus once a point in that region IS scored', async () => {
    await withMeasuredMap(600, async () => {
      await renderMap({
        selectedRegion: 'Dales',
        points: [{ lat: 6, lng: 24, rid: 'Dales', r: [3] }],
      });
    });
    expect(drawGeo.mock.calls.at(-1)[5].focus).toBe('Dales');
  });

  it('repaints when the selection changes, because focus is a paint option', async () => {
    await withMeasuredMap(600, async () => {
      const { rerender } = render(
        <WindowRowFieldMap
          windowKey={KEY} date={TODAY} confidence="high" spots={SPOTS}
          points={POINTS} regionNames={REGIONS} selectedRegion={null} todayStr={TODAY}
        />,
      );
      await act(async () => {});
      const before = drawGeo.mock.calls.length;
      await act(async () => {
        rerender(
          <WindowRowFieldMap
            windowKey={KEY} date={TODAY} confidence="high" spots={SPOTS}
            points={POINTS} regionNames={REGIONS} selectedRegion="Coast" todayStr={TODAY}
          />,
        );
      });
      expect(drawGeo.mock.calls.length).toBeGreaterThan(before);
      expect(drawGeo.mock.calls.at(-1)[5].focus).toBe('Coast');
    });
  });

  it('hazes a low-confidence window more than a high-confidence one', async () => {
    let high;
    await withMeasuredMap(600, async () => {
      await renderMap({ confidence: 'high' });
      high = drawGeo.mock.calls.at(-1)[5].conf;
    });
    vi.clearAllMocks();
    let low;
    await withMeasuredMap(600, async () => {
      await renderMap({ confidence: 'low' });
      low = drawGeo.mock.calls.at(-1)[5].conf;
    });
    expect(low).toBeLessThan(high);
  });

  it('declines one pixel under the width floor and paints one pixel over it', async () => {
    // 56 is derived from this component's own aspect floor (56 × 0.36 = 20.2, and `drawGeo` refuses
    // either dimension at 20 or under). A test that only declined at 40 passed for the HEIGHT gate's
    // reason and left the width constant free to be anything.
    await withMeasuredMap(56, async () => { await renderMap(); });
    expect(drawGeo).not.toHaveBeenCalled();

    cleanup();
    drawGeo.mockClear();
    await withMeasuredMap(57, async () => { await renderMap(); });
    expect(drawGeo).toHaveBeenCalledTimes(1);
  });
});

describe('WindowRowFieldMap — the region labels', () => {
  it('places one at each region’s projected centroid', async () => {
    await withMeasuredMap(600, async () => {
      await renderMap();
    });
    const labels = screen.getAllByTestId('wf-row-map-label');
    expect(labels.map((l) => l.textContent)).toEqual(['Coast', 'Dales']);
    expect(labels[0]).toHaveStyle({ left: '40px', top: '60px' });
    expect(labels[1]).toHaveStyle({ left: '240px', top: '60px' });
  });

  it('plates only the selected one', async () => {
    await withMeasuredMap(600, async () => {
      await renderMap({ selectedRegion: 'Dales' });
    });
    const labels = screen.getAllByTestId('wf-row-map-label');
    expect(labels[0]).not.toHaveAttribute('data-hot');
    expect(labels[1]).toHaveAttribute('data-hot', 'true');
  });

  it('draws none before the map has been measured', async () => {
    await renderMap();
    expect(screen.queryAllByTestId('wf-row-map-label')).toHaveLength(0);
  });

  it('skips a region with no spots in the frame rather than placing it at NaN', async () => {
    await withMeasuredMap(600, async () => {
      await renderMap({ regionNames: ['Coast', 'Dales', 'Nowhere'] });
    });
    expect(screen.getAllByTestId('wf-row-map-label').map((l) => l.textContent))
      .toEqual(['Coast', 'Dales']);
  });
});

describe('WindowRowFieldMap — the click', () => {
  it('selects the region whose centroid the pointer landed nearest', async () => {
    const { onSelectRegion } = await withMeasuredMap(600, async () => {
      const handles = await renderMap();
      // Coast sits at (40, 60); 100px away, well inside 26% of 600 = 156.
      fireEvent.click(stubCanvasBox(600), { clientX: 60, clientY: 60 });
      return handles;
    });
    expect(onSelectRegion).toHaveBeenCalledWith('Coast');
  });

  it('clears when the pointer lands beyond 26% of the frame width from every centroid', async () => {
    // Without the threshold every pixel on a map of northern England has a nearest region, so
    // "clicking empty space clears" would be unreachable.
    const { onSelectRegion } = await withMeasuredMap(600, async () => {
      const handles = await renderMap();
      // (400, 400): 424 from Coast, 372 from Dales — both beyond 156.
      fireEvent.click(stubCanvasBox(600), { clientX: 400, clientY: 400 });
      return handles;
    });
    expect(onSelectRegion).toHaveBeenCalledWith(null);
  });

  it('selects one pixel inside the threshold and clears one pixel outside it', async () => {
    // The boundary, from both sides. 26% of 600 = 156, and the test is `<`, so 156 exactly clears.
    // Measured VERTICALLY from Coast at (40, 60): moving along x would put the pointer nearer Dales
    // at (240, 60) long before it reached the threshold, so the second click would select rather
    // than clear and the test would pass for the wrong reason.
    const { onSelectRegion } = await withMeasuredMap(600, async () => {
      const handles = await renderMap();
      const canvas = stubCanvasBox(600);
      fireEvent.click(canvas, { clientX: 40, clientY: 60 + 155 });
      fireEvent.click(canvas, { clientX: 40, clientY: 60 + 156 });
      return handles;
    });
    expect(onSelectRegion.mock.calls).toEqual([['Coast'], [null]]);
  });

  it('clears when the region already selected is clicked again', async () => {
    const { onSelectRegion } = await withMeasuredMap(600, async () => {
      const handles = await renderMap({ selectedRegion: 'Coast' });
      fireEvent.click(stubCanvasBox(600), { clientX: 60, clientY: 60 });
      return handles;
    });
    expect(onSelectRegion).toHaveBeenCalledWith(null);
  });

  it('does nothing on a one-region window, where there is nothing to choose', async () => {
    const { onSelectRegion } = await withMeasuredMap(600, async () => {
      const handles = await renderMap({ regionNames: ['Coast'] });
      fireEvent.click(stubCanvasBox(600), { clientX: 60, clientY: 60 });
      return handles;
    });
    expect(onSelectRegion).not.toHaveBeenCalled();
  });

  it('does nothing before the map has been measured, rather than guessing a geometry', async () => {
    const { onSelectRegion } = await renderMap();
    const canvas = screen.getByTestId('wf-row-map-canvas');
    canvas.getBoundingClientRect = () => ({ left: 0, top: 0, width: 600 });
    fireEvent.click(canvas, { clientX: 60, clientY: 60 });
    expect(onSelectRegion).not.toHaveBeenCalled();
  });
});

describe('WindowRowFieldMap — a window nobody rated', () => {
  /**
   * The heat strip's defect, one level down: with no points the kernel paints nothing, so the map
   * is bare coastline — which is also what a window whose field happened to paint nothing looks
   * like — while the card above carries a verdict word the weather thresholds produced either way.
   * Quieter here than on the strip because the region labels still sit on the plate, which makes
   * it read as "these places, nothing doing" rather than as "nobody looked".
   */
  it('hatches the plate when the payload says nothing here is rated', async () => {
    await withMeasuredMap(600, async () => {
      await renderMap({ bestRating: null });
    });
    expect(drawGeo.mock.calls.at(-1)[5].hatch).toBe(true);
    expect(screen.getByTestId('wf-row-map-unscored')).toHaveTextContent('Not scored');
  });

  it('leaves a rated window alone', async () => {
    // The pair is the assertion: hatching unconditionally is the same defect with the opposite
    // sign, and a single-case test cannot tell the two apart.
    await withMeasuredMap(600, async () => {
      await renderMap();
    });
    expect(drawGeo.mock.calls.at(-1)[5].hatch).toBe(false);
    expect(screen.queryByTestId('wf-row-map-unscored')).toBeNull();
  });

  it('leaves a RATED window alone even when its field has no points to paint', async () => {
    // ⚠️ The production defect. An empty point set is a fact about the join behind the picture,
    // not about the forecast: the rail directly below this map would still be printing the
    // window's `best N★` while the plate said nobody had looked.
    await withMeasuredMap(600, async () => {
      await renderMap({ points: [] });
    });
    expect(drawGeo.mock.calls.at(-1)[5].hatch).toBe(false);
    expect(screen.queryByTestId('wf-row-map-unscored')).toBeNull();
  });

  it('keeps the chip out of the accessible tree, with the picture it annotates', async () => {
    // It decodes a hatch, and the hatch does not exist for a screen reader. The accessible answer
    // is the rail below, which withholds `best N★` when nothing there is rated rather than
    // printing a figure — less, not something false.
    await withMeasuredMap(600, async () => {
      await renderMap({ bestRating: null });
    });
    expect(screen.getByTestId('wf-row-map-unscored')).toHaveAttribute('aria-hidden', 'true');
  });

  it('sits beside the selection hint rather than replacing it', async () => {
    // Two different statements — one about what the picture shows, one about what it does — and
    // an unrated window is still selectable.
    await withMeasuredMap(600, async () => {
      await renderMap({ bestRating: null });
    });
    expect(screen.getByTestId('wf-row-map-hint')).toHaveTextContent('Select a region');
    expect(screen.getByTestId('wf-row-map-unscored')).toBeInTheDocument();
  });
});

describe('WindowRowFieldMap — the accessibility contract', () => {
  it('hides the canvas and its labels, because the rail names every one of them', async () => {
    await withMeasuredMap(600, async () => {
      await renderMap();
    });
    expect(screen.getByTestId('wf-row-map-canvas')).toHaveAttribute('aria-hidden', 'true');
    expect(screen.getAllByTestId('wf-row-map-label')[0].closest('[aria-hidden="true"]'))
      .toBeInTheDocument();
  });

  it('exposes no control of its own, so the map is never the sole route to a region', async () => {
    await withMeasuredMap(600, async () => {
      await renderMap();
    });
    expect(screen.queryAllByRole('button')).toHaveLength(0);
  });

  it('offers the hint only where a selection is possible', async () => {
    await withMeasuredMap(600, async () => {
      await renderMap({ regionNames: ['Coast'] });
    });
    expect(screen.queryByTestId('wf-row-map-hint')).toBeNull();
  });

  it('changes the hint once a region is selected', async () => {
    await withMeasuredMap(600, async () => {
      await renderMap({ selectedRegion: 'Coast' });
    });
    expect(screen.getByTestId('wf-row-map-hint')).toHaveTextContent('Select it again to clear');
  });
});

describe('WindowRowFieldMap — when the geometry cannot be fetched', () => {
  it('withdraws entirely rather than leaving an empty frame on the row', async () => {
    // An unpainted map implies a field with nothing in it, which is a different and false claim
    // from "the picture is unavailable" — the strip's own call.
    load.mockImplementation(() => Promise.reject(new Error('chunk')));
    land.mockImplementation(() => null);
    await renderMap();
    expect(screen.queryByTestId('wf-row-map')).toBeNull();
  });
});

describe('WindowRowFieldMap — the aspect clamps, which are this component’s not the kernel’s', () => {
  it('bands a desktop map between 0.36 and 0.62', () => {
    expect(MAP_ASPECT_MIN).toBe(0.36);
    expect(MAP_ASPECT_MAX).toBe(0.62);
  });

  it('lets a phone map go nearly square, where the height is what is scarce', () => {
    expect(MAP_ASPECT_MIN_PHONE).toBe(0.5);
    expect(MAP_ASPECT_MAX_PHONE).toBe(0.95);
  });

  it('lifts a very wide frame to the floor rather than drawing a letterbox slot', async () => {
    // This fixture's two regions are 20° of longitude apart and share a latitude, so the raw frame
    // aspect is ~0.016 — a canvas 600px wide and 9px tall. The floor is what makes it a map.
    await withMeasuredMap(600, async () => {
      await renderMap();
    });
    expect(drawGeo.mock.calls.at(-1)[2]).toBe(Math.round(600 * MAP_ASPECT_MIN));
  });

  it('uses the PHONE band on a phone, where height is what is scarce', async () => {
    // Asserting the constants against themselves proves nothing — the ternary can be deleted
    // wholesale and a literal-vs-literal test stays green. This renders the branch.
    useIsMobile.mockReturnValue(true);
    await withMeasuredMap(390, async () => { await renderMap(); });
    // Same very-wide fixture frame as the desktop floor test, so only the CLAMP differs.
    expect(drawGeo.mock.calls.at(-1)[2]).toBe(Math.round(390 * MAP_ASPECT_MIN_PHONE));
    useIsMobile.mockReturnValue(false);
  });

  it('holds a phone frame at its own taller ceiling', async () => {
    useIsMobile.mockReturnValue(true);
    const tall = [
      spot({ lat: 4, lng: 6 }),
      spot({ id: 2, lat: 24, lng: 6, regionName: 'Dales', rid: 'Dales' }),
    ];
    await withMeasuredMap(390, async () => { await renderMap({ spots: tall }); });
    expect(drawGeo.mock.calls.at(-1)[2]).toBe(Math.round(390 * MAP_ASPECT_MAX_PHONE));
    useIsMobile.mockReturnValue(false);
  });

  it('holds a very tall frame at the ceiling rather than pushing the rail off screen', async () => {
    // The mirror case: two regions 20° of LATITUDE apart give a raw aspect above 4, which would
    // draw a 2,500px canvas above the rail on a 600px row.
    const tall = [
      spot({ lat: 4, lng: 6 }),
      spot({ id: 2, lat: 24, lng: 6, regionName: 'Dales', rid: 'Dales' }),
    ];
    await withMeasuredMap(600, async () => {
      await renderMap({ spots: tall });
    });
    expect(drawGeo.mock.calls.at(-1)[2]).toBe(Math.round(600 * MAP_ASPECT_MAX));
  });
});

/**
 * The open row's map under an away origin (plan §4.8, P7).
 *
 * <p><b>What breaks if these fail.</b> The strip's six thumbnails re-frame to the origin's region;
 * this map sits inside the row one of them opens. Left on the home planning area it draws a
 * different frame from the thumbnail directly above it, for the same window — the "half-applied
 * origin" the phase's own tests call worse than none. `origin` reaching this component at all is
 * the other half: it arrives through `WindowRowRegionLayer`'s field object, and a dropped prop is
 * silent.
 */
describe('WindowRowFieldMap — the origin re-frames it', () => {
  const ORIGIN = { name: 'Dales', baseName: 'Bakewell' };

  it('fits the planning area at home', async () => {
    await withMeasuredMap(240, async () => { await renderMap(); });
    expect(drawGeo.mock.calls[0][5].fit).toEqual(bbox(SPOTS));
  });

  it('⚠️ fits the ORIGIN\'s own region when away, and it is a different frame', async () => {
    await withMeasuredMap(240, async () => { await renderMap({ origin: ORIGIN }); });
    const { fit } = drawGeo.mock.calls[0][5];
    expect(fit).toEqual(bbox([SPOTS[1]]));
    expect(fit).not.toEqual(bbox(SPOTS));
  });

  it('⚠️ leaves the POINT SET whole — framing must never become a filter', async () => {
    await withMeasuredMap(240, async () => { await renderMap({ origin: ORIGIN }); });
    // The one scored point is in Coast, which the Dales scope frames away from. It must still be
    // in the blend: an origin narrows what is in shot, not what the field is made of.
    expect(drawGeo.mock.calls[0][3]).toBe(POINTS);
  });

  it('re-frames on an origin CHANGE, not only on a first render with one set', async () => {
    // Catches `origin` being dropped from the framing memo's dependency list, which no
    // render-once test can see.
    await withMeasuredMap(240, async () => {
      const { rerender } = await act(async () => render(
        <WindowRowFieldMap
          windowKey={KEY}
          date={TODAY}
          confidence="high"
          spots={SPOTS}
          points={POINTS}
          regionNames={REGIONS}
          selectedRegion={null}
          todayStr={TODAY}
          onSelectRegion={vi.fn()}
        />,
      ));
      expect(drawGeo.mock.calls[0][5].fit).toEqual(bbox(SPOTS));

      await act(async () => rerender(
        <WindowRowFieldMap
          windowKey={KEY}
          date={TODAY}
          confidence="high"
          spots={SPOTS}
          points={POINTS}
          regionNames={REGIONS}
          selectedRegion={null}
          todayStr={TODAY}
          onSelectRegion={vi.fn()}
          origin={ORIGIN}
        />,
      ));

      const last = drawGeo.mock.calls[drawGeo.mock.calls.length - 1];
      expect(last[5].fit).toEqual(bbox([SPOTS[1]]));
    });
  });
});
