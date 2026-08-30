import React from 'react';
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { act, cleanup, render, screen, fireEvent, within } from '@testing-library/react';
import WindowRowFieldMap, {
  MAP_ASPECT_MAX, MAP_ASPECT_MAX_PHONE, MAP_ASPECT_MIN, MAP_ASPECT_MIN_PHONE,
} from '../components/WindowRowFieldMap.jsx';
import { bbox, drawGeo, land, load } from '../utils/heatField.js';
import { useIsMobile } from '../hooks/useIsMobile.js';
import { POINT_SCORE_INDEX } from '../utils/heatSpots.js';
import { formatDriveDuration } from '../utils/briefingDisplay.js';

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

  it('⚠️ omits the FOCUSED region\'s own label, and keeps every other', async () => {
    // The design's own rule, adopted at M2. The rail cell reads pressed and the prose slot's
    // heading names that region, so the label is a third statement of one fact — and it sits in
    // exactly the part of the field the location chips are competing for. The plate treatment it
    // used to carry (`data-hot`) has nothing left to mark.
    await withMeasuredMap(600, async () => {
      await renderMap({ selectedRegion: 'Dales' });
    });
    const labels = screen.getAllByTestId('wf-row-map-label');
    expect(labels.map((l) => l.textContent)).toEqual(['Coast']);
    expect(labels[0]).not.toHaveAttribute('data-hot');
  });

  it('names every region again once the focus is cleared', async () => {
    await withMeasuredMap(600, async () => {
      await renderMap({ selectedRegion: null });
    });
    expect(screen.getAllByTestId('wf-row-map-label').map((l) => l.textContent))
      .toEqual(['Coast', 'Dales']);
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
  // ⚠️ The desktop band moved at M2 (0.36–0.62 → 0.88–1.34) and the phone band did not. The field
  // left a full-width row for the LEFT column of the popup's two-column body, so a letterbox inside
  // two-fifths of a dialog would be a postage stamp — see the component's own note. The phone body
  // is one column and its constraint never moved.
  it('bands a desktop map between 0.88 and 1.34, the popup column’s portrait shape', () => {
    expect(MAP_ASPECT_MIN).toBe(0.88);
    expect(MAP_ASPECT_MAX).toBe(1.34);
  });

  it('lets a phone map go nearly square, where the height is what is scarce', () => {
    expect(MAP_ASPECT_MIN_PHONE).toBe(0.5);
    expect(MAP_ASPECT_MAX_PHONE).toBe(0.95);
  });

  it('lifts a very wide frame to the floor rather than drawing a letterbox slot', async () => {
    // This fixture's two regions are 20° of longitude apart and share a latitude, so the raw frame
    // aspect is ~0.016 — a canvas 600px wide and 9px tall. The floor is what makes it a map.
    // (The floor moved to 0.88 at M2, so the product is 528 rather than 216; the assertion is
    // computed from the constant either way.)
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
 * the other half: it arrives through the popup's `field` object, and a dropped prop is
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

/**
 * The location chips — the layer that turns the field from areas into places (plan-matrix §6 M2.3).
 *
 * <h2>What a jsdom test can and cannot prove here</h2>
 *
 * <p>The placement is real arithmetic over measured boxes, so it IS testable — but only with the
 * measurements stubbed, because jsdom lays nothing out and reports 0 for every one of them. The
 * component's own zero-guard means an unstubbed run drops every chip, so a test that asserted their
 * absence without stubbing would pin the guard and never the placer. Every case below stubs
 * `offsetWidth`/`offsetHeight` explicitly and says what it is asserting with them.
 *
 * <p>What stays a browser claim: the plate's contrast, the divider, and whether a chip is legible
 * over a bright field.
 */

/**
 * Stubs the two box measurements the greedy pass reads, at the size a case needs.
 *
 * <p>`return await`, not `return run()` — the same trap `withMeasuredMap` records above: the bare
 * return hands the promise back and `finally` restores the real descriptors before the render
 * inside has run, so the placer reads jsdom's zeros and drops every chip.
 */
async function withChipBoxes(width, height, run) {
  const w = Object.getOwnPropertyDescriptor(HTMLElement.prototype, 'offsetWidth');
  const h = Object.getOwnPropertyDescriptor(HTMLElement.prototype, 'offsetHeight');
  Object.defineProperty(HTMLElement.prototype, 'offsetWidth', { configurable: true, get: () => width });
  Object.defineProperty(HTMLElement.prototype, 'offsetHeight', { configurable: true, get: () => height });
  try {
    return await run();
  } finally {
    if (w) Object.defineProperty(HTMLElement.prototype, 'offsetWidth', w);
    else delete HTMLElement.prototype.offsetWidth;
    if (h) Object.defineProperty(HTMLElement.prototype, 'offsetHeight', h);
    else delete HTMLElement.prototype.offsetHeight;
  }
}

/**
 * A catalogue whose region CENTROIDS sit well clear of the chips' own points.
 *
 * <p>`centroid` is the mean of a region's projected points, so with one spot per region the label
 * and the chip land on the same pixel and the placer drops the chip — correctly, and uselessly for
 * a test about placement.
 */
const CHIP_SPOTS = [
  spot({ id: 1, name: 'Bamburgh', lng: 4, lat: 6 }),
  spot({ id: 3, name: 'Craster', lng: 4, lat: 26 }),
  spot({ id: 2, name: 'Ladybower', lng: 24, lat: 6, regionName: 'Dales', rid: 'Dales' }),
  spot({ id: 4, name: 'Malham', lng: 24, lat: 26, regionName: 'Dales', rid: 'Dales' }),
];

const CHIP = (overrides = {}) => ({
  key: '1', locationId: 1, locationName: 'Bamburgh', rating: 4, ...overrides,
});

/**
 * A catalogue for the cap cases: twelve chips in a row, and two ballast spots that drag each
 * region's centroid hundreds of pixels below them.
 *
 * <p>Both halves are load-bearing. The row is spaced 30px against 6px boxes so no two chips can
 * crowd each other; the ballast keeps the region LABEL out of the row, because a label the
 * placer must avoid would drop chips for a reason this test is not about.
 *
 * <p>⚠️ <b>30px, not the 20px this fixture used through M4.</b> The placer gained a second
 * separation test at M5 — {@code MIN_TARGET_SEPARATION_PX}, WCAG 2.5.8's Spacing exception, which
 * is a distance between CENTRES rather than a clearance between rectangles — and at 20px apart
 * these chips cleared the old rule and failed the new one, so the cap test started measuring
 * crowding instead of the cap. The spacing is the fixture's way of saying "assume the placer can
 * fit all twelve"; the number that satisfies that has moved, and the assertion has not.
 *
 * <p>Module-level (not local to the chip cap describe block) because the reach-rings suite's own
 * phone fixture reuses it — home projected onto `Spot 0`'s own point is what it displaces.
 */
const capSpots = () => {
  const row = Array.from({ length: 12 }, (_, i) => spot({
    id: 100 + i, name: `Spot ${i}`, lng: 4 + i * 3, lat: 6,
  }));
  return [
    ...row,
    spot({ id: 900, name: 'Ballast A', lng: 4, lat: 200 }),
    spot({ id: 901, name: 'Ballast B', lng: 37, lat: 200, regionName: 'Dales', rid: 'Dales' }),
  ];
};
const capChips = () => Array.from({ length: 12 }, (_, i) => CHIP({
  key: String(100 + i), locationId: 100 + i, locationName: `Spot ${i}`,
}));

describe('WindowRowFieldMap — the location chips', () => {
  it('draws none at all when the caller hands over no chips', async () => {
    // The default, and what keeps every other test in this file unchanged.
    await withMeasuredMap(400, async () => { await renderMap(); });
    expect(screen.queryByTestId('wf-row-map-chips')).toBeNull();
  });

  it('places a chip at its own location’s projected point, with its rating', async () => {
    await withMeasuredMap(400, async () => {
      await withChipBoxes(50, 14, async () => {
        await renderMap({ spots: CHIP_SPOTS, chips: [CHIP()] });
      });
    });
    const chip = screen.getByTestId('wf-row-map-chip');
    // Bamburgh is at lng 4 / lat 6, which the stub projection puts at (40, 60). The chip's marker
    // sits ON that point, so its left edge is half a marker to the left of it.
    expect(chip).toHaveStyle({ left: '34.5px' });
    expect(chip).toHaveTextContent('Bamburgh');
    expect(chip).toHaveTextContent('4★');
  });

  it('⚠️ is inert WITHOUT a handler, so it cannot swallow the region click underneath it', async () => {
    // The no-handler default, which is what every caller that draws an annotation rather than a
    // control gets. `.wf-mchip` is `pointer-events: none` there (a CSS claim) and carries no
    // `title` — a tooltip on a pointer-events-none span reaches nobody. What a test can pin is that
    // the chip is not a control: a button with no visible effect is what plan §3 rule 14 bans.
    await withMeasuredMap(400, async () => {
      await withChipBoxes(50, 14, async () => {
        await renderMap({ spots: CHIP_SPOTS, chips: [CHIP({ title: 'Coast · 42 min' })] });
      });
    });
    const chip = screen.getByTestId('wf-row-map-chip');
    expect(chip.tagName).toBe('SPAN');
    // ⚠️ The chip HAS a title to withhold — pinned to the rule, not to the fixture. With a chip
    // carrying none, this assertion holds whether or not the source gates it.
    expect(chip).not.toHaveAttribute('title');
    expect(within(screen.getByTestId('wf-row-map-chips')).queryAllByRole('button')).toHaveLength(0);
  });

  it('is hidden from the accessibility tree WITHOUT a handler, as the picture’s other annotations are', async () => {
    // The ranked strip below the field names every one of these on a real control, with its region,
    // its drive and its departure — which is the condition this component sets for an aria-hidden
    // surface: never the sole path to anything.
    await withMeasuredMap(400, async () => {
      await withChipBoxes(50, 14, async () => {
        await renderMap({ spots: CHIP_SPOTS, chips: [CHIP()] });
      });
    });
    expect(screen.getByTestId('wf-row-map-chips')).toHaveAttribute('aria-hidden', 'true');
    // And carries no group role either: an `aria-hidden` layer has nothing in the tree to group,
    // and a role on it would be a promise the subtree cannot keep.
    expect(screen.getByTestId('wf-row-map-chips')).not.toHaveAttribute('role');
  });

  /**
   * The M4 arm: a chip with somewhere to go (plan-matrix §6 M4.2, D-3).
   *
   * <p>⚠️ <b>The three properties land together or not at all</b>, which is why they are asserted
   * together: the click, the {@code title}, and the exit from {@code aria-hidden}. M2 deferred all
   * three as a set — a tooltip on an inert span reaches nobody, and a control inside an
   * {@code aria-hidden} subtree cannot be found by the readers most likely to want the name.
   */
  describe('with a handler', () => {
    const renderChip = async (chips) => {
      const onOpenLocation = vi.fn();
      await withMeasuredMap(400, async () => {
        await withChipBoxes(50, 14, async () => {
          await renderMap({ spots: CHIP_SPOTS, chips, onOpenLocation });
        });
      });
      return onOpenLocation;
    };

    it('becomes a button that carries its title and leaves the hidden subtree', async () => {
      const onOpenLocation = await renderChip([CHIP({ title: 'Coast · 42 min · leave 19:04' })]);
      const chip = screen.getByTestId('wf-row-map-chip');
      expect(chip.tagName).toBe('BUTTON');
      expect(chip).toHaveAttribute('title', 'Coast · 42 min · leave 19:04');
      expect(screen.getByTestId('wf-row-map-chips')).not.toHaveAttribute('aria-hidden');
      // Asked of the ACCESSIBILITY TREE — an `aria-hidden` subtree exposes no buttons at all, so a
      // role query is the assertion that the layer left it.
      //
      // ⚠️ EXACT, where this used to be `/^Bamburgh\s*4 stars$/`. The `\s*` accepted zero
      // separators, which is what the name actually computed as until M5: name-from-contents TRIMS
      // each element's own contribution, so the space had to be a bare text node between the `<b>`
      // and the `<em>` rather than inside either. Measured three ways against
      // `dom-accessibility-api`, which is what this query uses. `Bamburgh4 stars` is what the
      // tolerant form was quietly passing.
      expect(screen.getByRole('button', { name: 'Bamburgh 4 stars' })).toBe(chip);
      expect(onOpenLocation).not.toHaveBeenCalled();
    });

    it('⚠️ names the layer, so eight bare place-buttons say where they are', async () => {
      // The canvas and the region labels stay `aria-hidden` — they ARE the picture — so the spatial
      // meaning that justifies these chips existing never reaches a screen reader. Without a group
      // name what arrives is six or eight "<place> N stars" buttons in rating order, directly above
      // a strip naming the same places again with region, drive and departure, and nothing saying
      // which list is which. The group is absent on the inert arm, where there is nothing to group.
      await renderChip([CHIP()]);
      const layer = screen.getByTestId('wf-row-map-chips');
      expect(layer).toHaveAttribute('role', 'group');
      expect(screen.getByRole('group', { name: 'Places on the field map' })).toBe(layer);
    });

    it('hands the WHOLE chip back, so the caller can open a sheet without a second join', async () => {
      const chip = CHIP({ regionName: 'Coast', title: 'Coast · 42 min' });
      const onOpenLocation = await renderChip([chip]);
      fireEvent.click(screen.getByTestId('wf-row-map-chip'));
      // The anchored chip — the caller's own object plus the projected point it was placed at. The
      // three fields that matter are the three `sheetSpotOf` reads, and the ID is the one that
      // matters most: it is the key both of the sheet's indexes join on, and dropping it is the
      // defect an adversarial review caught in P8 (a renamed location, correctly timed and rated as
      // unscored).
      expect(onOpenLocation).toHaveBeenCalledWith(expect.objectContaining({
        locationId: chip.locationId,
        locationName: chip.locationName,
        regionName: 'Coast',
      }));
    });

    it('speaks the rating rather than the glyph, and says nothing where there is none', async () => {
      // NVDA at its default symbol level does not speak U+2605, so a named control would announce a
      // bare integer after the place name. An unrated chip gets no rating text at all — absence is
      // not zero, and the ranked strip omits its badge for the same reason.
      await renderChip([CHIP({ rating: null })]);
      const chip = screen.getByTestId('wf-row-map-chip');
      expect(chip).toHaveAccessibleName('Bamburgh');
      expect(chip).not.toHaveTextContent('★');
    });
  });

  it('omits the rating for a spot the payload has not rated, rather than inventing one', async () => {
    await withMeasuredMap(400, async () => {
      await withChipBoxes(50, 14, async () => {
        await renderMap({ spots: CHIP_SPOTS, chips: [CHIP({ rating: null })] });
      });
    });
    expect(screen.getByTestId('wf-row-map-chip')).not.toHaveTextContent('★');
  });

  it('⚠️ flips to the left of its point when the right side would clip', async () => {
    // Ladybower projects to x = 240 on a 400px frame; a 200px chip drawn rightward would end at
    // 434.5 and run off the plate.
    await withMeasuredMap(400, async () => {
      await withChipBoxes(200, 14, async () => {
        await renderMap({
          spots: CHIP_SPOTS,
          chips: [CHIP({ key: '2', locationId: 2, locationName: 'Ladybower' })],
        });
      });
    });
    const chip = screen.getByTestId('wf-row-map-chip');
    expect(chip).toHaveAttribute('data-flip', 'true');
    expect(chip).toHaveStyle({ left: '45.5px' });
  });

  it('⚠️ drops a chip that fits on neither side rather than overlapping', async () => {
    // Wider than the frame: neither placement is inside it. An unreadable name is worse than a
    // missing one, and the ranked strip lists every one of them anyway.
    await withMeasuredMap(400, async () => {
      await withChipBoxes(600, 14, async () => {
        await renderMap({ spots: CHIP_SPOTS, chips: [CHIP()] });
      });
    });
    expect(screen.queryAllByTestId('wf-row-map-chip')).toHaveLength(0);
  });

  it('⚠️ caps how many it draws, keeping the ones the caller ranked first', async () => {
    // Eight on a desktop frame. The caller hands them over in the order they deserve the space, so
    // the cap is a prefix rather than a selection — the map cannot promote a spot the list ranked
    // ninth.
    await withMeasuredMap(600, async () => {
      await withChipBoxes(6, 6, async () => {
        await renderMap({ spots: capSpots(), chips: capChips() });
      });
    });
    const drawn = screen.getAllByTestId('wf-row-map-chip').map((n) => n.dataset.location);
    expect(drawn).toEqual(['Spot 0', 'Spot 1', 'Spot 2', 'Spot 3', 'Spot 4', 'Spot 5', 'Spot 6', 'Spot 7']);
  });

  it('⚠️ drops a chip whose CENTRE would sit under 24px from another, even with no overlap', async () => {
    // WCAG 2.2 SC 2.5.8's Spacing exception, and the case a rectangle-clearance rule cannot see: two
    // 6px boxes 10px apart do not touch — the old `BOX_GAP` of 3 passes them — while their centres
    // are 10px apart and a 24px circle on each overlaps the other's. The chips are 16px tall in the
    // browser and M4 rested their 2.5.8 case on the Equivalent exception instead, which M5 measured
    // breaking under a region focus (two of six chips named places with no card in the dialog).
    //
    // Twelve candidates at 10px spacing, so the cap is nowhere near binding: what is measured is the
    // separation rule alone. Every survivor must clear 24px from every other survivor.
    const tight = Array.from({ length: 12 }, (_, i) => spot({
      id: 200 + i, name: `Tight ${i}`, lng: 4 + i, lat: 6,
    }));
    const tightChips = Array.from({ length: 12 }, (_, i) => CHIP({
      key: String(200 + i), locationId: 200 + i, locationName: `Tight ${i}`,
    }));
    await withMeasuredMap(600, async () => {
      await withChipBoxes(6, 6, async () => {
        await renderMap({
          spots: [...tight, spot({ id: 902, name: 'Ballast', lng: 4, lat: 200 })],
          chips: tightChips,
        });
      });
    });

    const drawn = screen.getAllByTestId('wf-row-map-chip');
    expect(drawn.length).toBeGreaterThan(0);
    expect(drawn.length).toBeLessThan(12);
    const centres = drawn.map((n) => ({
      x: parseFloat(n.style.left) + 3,
      y: parseFloat(n.style.top) + 3,
    }));
    for (let i = 0; i < centres.length; i += 1) {
      for (let j = i + 1; j < centres.length; j += 1) {
        const d = Math.hypot(centres[i].x - centres[j].x, centres[i].y - centres[j].y);
        expect(d).toBeGreaterThanOrEqual(24);
      }
    }
  });

  /**
   * ⚠️ The separation rule is between TARGETS, and `placed` holds three populations.
   *
   * <p>Found by an adversarial review of M5's first cut, which ran the 24px test against every
   * entry: the hint corner and the region labels are `aria-hidden`, `pointer-events: none`
   * decorations, so SC 2.5.8 says nothing about them and measuring a control's clearance against
   * them spends map room for a rule that does not apply.
   *
   * <p>Driven through a REGION LABEL rather than the hint corner, because `withChipBoxes` stubs
   * `offsetWidth`/`offsetHeight` on the prototype and so sizes the label too — which makes the
   * geometry exact and independent of the frame's height. Two spots in one region put the centroid
   * midway between them; the chip sits on one of them.
   */
  it('⚠️ measures the 24px separation against CHIPS only, never a region label', async () => {
    // Stub projection is `[lng*10, lat*10]`. Spots at lng 4 and 6 → x 40 and 60, so the Coast
    // centroid is x 50 and the label box (6 wide, centred) spans 47–53. The chip on the lng-4 spot
    // is offset by `CHIP_OFFSET` and 6 wide → 34.5–40.5, centre 37.5. Centre-to-centre is 12.5px —
    // well inside 24 — while the boxes are 6.5px apart, clear of `BOX_GAP`. So the legibility test
    // passes and only the separation test can drop this chip.
    const near = [
      spot({ id: 300, name: 'Near', lng: 4, lat: 6 }),
      spot({ id: 301, name: 'Pair', lng: 6, lat: 6 }),
      spot({ id: 302, name: 'Ballast', lng: 24, lat: 26, regionName: 'Dales', rid: 'Dales' }),
    ];
    await withMeasuredMap(400, async () => {
      await withChipBoxes(6, 6, async () => {
        await renderMap({
          spots: near,
          chips: [CHIP({ key: '300', locationId: 300, locationName: 'Near' })],
        });
      });
    });
    expect(screen.getAllByTestId('wf-row-map-chip').map((n) => n.dataset.location)).toEqual(['Near']);
  });

  /**
   * The band edge, which "a 10px-granular fixture drops them and a 30px one keeps them" cannot see.
   *
   * <p>Both cases are the SAME fixture shape with one number changed, so nothing but the distance
   * can explain the difference — and they sit either side of 24 by a single pixel, which is what
   * makes the constant itself the thing under test rather than the rule's existence.
   */
  it.each([
    [2.3, 1, 'drops the second chip at 23px between centres'],
    [2.5, 2, 'keeps both at 25px'],
  ])('%s — %s', async (latGap, expected) => {
    // ⚠️ Separated VERTICALLY, and the first attempt at this test separated them horizontally and
    // proved nothing. The placer tries a flipped variant when the first does not fit
    // (`chip.x + CHIP_OFFSET - width`), which moves a chip 11px sideways — so on a horizontal pair
    // at 23px the flip lands 28px away and the chip is correctly kept. Stacked vertically the flip
    // buys only 5px of x, and `hypot(5, 23)` is 23.5, still inside the rule; at 25px the first
    // variant already clears it. One number changes between the two cases and nothing else.
    //
    // A chip's box is `y = chip.y - height/2`, so its centre y IS the anchor's: the gap between two
    // chip centres is exactly `10 × latGap` under the stub projection. The ballast keeps each
    // region's LABEL out of the column (see `capSpots`), and the two chipped spots are in different
    // regions for the same reason.
    const pair = [
      spot({ id: 400, name: 'Upper', lng: 4, lat: 6 }),
      spot({ id: 401, name: 'Lower', lng: 4, lat: 6 + latGap, regionName: 'Dales', rid: 'Dales' }),
      spot({ id: 402, name: 'Ballast A', lng: 30, lat: 6 }),
      spot({ id: 403, name: 'Ballast B', lng: 30, lat: 26, regionName: 'Dales', rid: 'Dales' }),
    ];
    await withMeasuredMap(400, async () => {
      await withChipBoxes(6, 6, async () => {
        await renderMap({
          spots: pair,
          chips: [
            CHIP({ key: '400', locationId: 400, locationName: 'Upper' }),
            CHIP({ key: '401', locationId: 401, locationName: 'Lower' }),
          ],
        });
      });
    });
    expect(screen.getAllByTestId('wf-row-map-chip')).toHaveLength(expected);
  });

  it('⚠️ caps at six on a phone, where the frame is narrower', async () => {
    useIsMobile.mockReturnValue(true);
    try {
      await withMeasuredMap(600, async () => {
        await withChipBoxes(6, 6, async () => {
          await renderMap({ spots: capSpots(), chips: capChips() });
        });
      });
      expect(screen.getAllByTestId('wf-row-map-chip')).toHaveLength(6);
    } finally {
      // ⚠️ Restored explicitly. `afterEach`'s `vi.clearAllMocks()` clears CALLS, not
      // implementations (this file's own `beforeEach` note says so), and nothing sets
      // `restoreMocks` — so without this every test after it silently runs the phone branch.
      useIsMobile.mockReturnValue(false);
    }
  });

  it('⚠️ joins the catalogue id-first, so a renamed location still lands on its point', async () => {
    // The arm's join policy (plan §3 rule 11). A name-only join would silently drop a chip whose
    // location has been renamed between the two payloads.
    await withMeasuredMap(400, async () => {
      await withChipBoxes(50, 14, async () => {
        await renderMap({
          spots: CHIP_SPOTS,
          chips: [CHIP({ locationId: 1, locationName: 'Bamburgh Castle (renamed)' })],
        });
      });
    });
    expect(screen.getByTestId('wf-row-map-chip')).toHaveStyle({ left: '34.5px' });
  });

  it('draws no chip for a location the catalogue has never heard of', async () => {
    await withMeasuredMap(400, async () => {
      await withChipBoxes(50, 14, async () => {
        await renderMap({
          spots: CHIP_SPOTS,
          chips: [CHIP({ locationId: 999, locationName: 'Nowhere' })],
        });
      });
    });
    expect(screen.queryAllByTestId('wf-row-map-chip')).toHaveLength(0);
  });
});

/**
 * Reach rings + home marker (field-geography plan §3).
 *
 * <h2>⚠️ The suite's default ×10 stub cannot exercise ring geometry</h2>
 *
 * <p>Under {@code ([lng, lat]) => [lng * 10, lat * 10]}, {@code kmPerPx = 10 / 111.2 ≈ 0.09 px/km},
 * so r₄₀ ≈ 3.6px and r₈₀ ≈ 7.2px — both under the 18px skip floor, and no ring ever renders. Every
 * positive ring test below overrides the projection scale with {@code drawGeo.mockImplementationOnce}
 * and works the arithmetic through the real {@code kmPerPx}, never against a shrunk frame — the mock
 * discards the frame entirely, so "shrink the frame until r < 18" would prove nothing.
 *
 * <p>Home points are chosen inside {@code SPOTS}' own small lat/lng range (never real UK
 * coordinates), for the reason {@code WindowSheetDialog.test.jsx}'s own fixture records: a real
 * postcode projects far outside any plausible frame under a linear stub, and {@code placeWithNudges}
 * only nudges VERTICALLY, so a wrong x drops the anchor on every rung.
 */
describe('WindowRowFieldMap — reach rings + home marker', () => {
  it('draws both rings at their real distance and commits the home marker’s own position', async () => {
    // S = 100: r₄₀ = 40 × 100/111.2 ≈ 35.97px, r₈₀ ≈ 71.94px — neither exceeds 1.15 × 600 = 690, so
    // both are drawn on a 600px frame.
    drawGeo.mockImplementationOnce(() => ([lng, lat]) => [lng * 100, lat * 100]);
    await withMeasuredMap(600, async () => {
      await withChipBoxes(40, 14, async () => {
        await renderMap({ homePoint: [3, 3] });
      });
    });
    const rings = screen.getAllByTestId('wf-row-map-ring');
    expect(rings).toHaveLength(2);
    const byKm = new Map(rings.map((r) => [r.dataset.km, r]));
    expect(Number(byKm.get('40').getAttribute('r'))).toBeCloseTo(35.97, 1);
    expect(Number(byKm.get('80').getAttribute('r'))).toBeCloseTo(71.94, 1);
    rings.forEach((ring) => {
      expect(Number(ring.getAttribute('cx'))).toBeCloseTo(300, 5);
      expect(Number(ring.getAttribute('cy'))).toBeCloseTo(300, 5);
    });
    // Home is anchored at the same (300, 300); its box is measured 40×14, so the first (dy=0)
    // candidate is `{x: 300 - 20, y: 300 - 7}` and nothing placed ahead of it collides.
    expect(screen.getByTestId('wf-row-map-home')).toHaveStyle({ left: '280px', top: '293px' });
  });

  it('labels each ring with the reach lens’s own duration string, never authored text', async () => {
    drawGeo.mockImplementationOnce(() => ([lng, lat]) => [lng * 100, lat * 100]);
    await withMeasuredMap(600, async () => {
      await withChipBoxes(40, 14, async () => {
        await renderMap({ homePoint: [3, 3] });
      });
    });
    const labels = screen.getAllByTestId('wf-row-map-ring-label');
    // Imported, never a literal — the strings can never drift from `reachLens.js`'s own tiers.
    expect(labels.map((l) => l.textContent)).toEqual([formatDriveDuration(45), formatDriveDuration(90)]);
  });

  it('skips the 40 km ring under the 18px floor while the 80 km ring, in the SAME frame, clears it', async () => {
    // S = 30: r₄₀ ≈ 10.79 (skipped), r₈₀ ≈ 21.58 (drawn).
    drawGeo.mockImplementationOnce(() => ([lng, lat]) => [lng * 30, lat * 30]);
    await withMeasuredMap(600, async () => {
      await withChipBoxes(40, 14, async () => {
        await renderMap({ homePoint: [3, 3] });
      });
    });
    expect(screen.getAllByTestId('wf-row-map-ring').map((r) => r.dataset.km)).toEqual(['80']);
  });

  it.each([
    [50.04, true, 'r₄₀ = 18.00px exactly — the rule is strict "<", so 18.00 is drawn'],
    [49.9, false, 'r₄₀ ≈ 17.95px — just under the floor, skipped'],
  ])('S=%s: the 40 km ring is %s (%s)', async (scale, drawn) => {
    drawGeo.mockImplementationOnce(() => ([lng, lat]) => [lng * scale, lat * scale]);
    await withMeasuredMap(600, async () => {
      await withChipBoxes(40, 14, async () => {
        // homePoint's LATITUDE is 0, not 3 — `kmPerPx` measures `project([lng, lat+1]) -
        // project([lng, lat])`, and only at lat 0 does that subtraction land on the boundary
        // EXACTLY under floating point (`1×S − 0×S`); at lat 3 the same subtraction (`4×S − 3×S`)
        // loses a bit and the S=50.04 case reads 17.999999999999996, failing the boundary this
        // test exists to pin.
        await renderMap({ homePoint: [3, 0] });
      });
    });
    const kms = screen.queryAllByTestId('wf-row-map-ring').map((r) => r.dataset.km);
    expect(kms.includes('40')).toBe(drawn);
  });

  it('skips the 80 km ring once it grows past 1.15× the frame, while the 40 km ring — same scale — stays', async () => {
    // width 60 (above the 56px paint floor), height ≈ 53 (aspect floors at 0.88 for these spots):
    // 1.15 × max(60, 53) = 69. r₈₀ ≈ 71.94 (skipped), r₄₀ ≈ 35.97 (drawn).
    drawGeo.mockImplementationOnce(() => ([lng, lat]) => [lng * 100, lat * 100]);
    await withMeasuredMap(60, async () => {
      await withChipBoxes(20, 10, async () => {
        await renderMap({ homePoint: [3, 3] });
      });
    });
    expect(screen.getAllByTestId('wf-row-map-ring').map((r) => r.dataset.km)).toEqual(['40']);
  });

  it('draws no rings element at all when both skip — the default ×10 stub, unmodified', async () => {
    await withMeasuredMap(600, async () => {
      await withChipBoxes(40, 14, async () => {
        await renderMap({ homePoint: [3, 3] });
      });
    });
    expect(screen.queryByTestId('wf-row-map-rings')).toBeNull();
    // Only the RINGS skip — the home marker itself is a separate gate and still draws.
    expect(screen.getByTestId('wf-row-map-home')).toBeInTheDocument();
  });

  it('rings and home join the SHARED box list: a chip anchored on a ring label’s own box is bumped', async () => {
    drawGeo.mockImplementationOnce(() => ([lng, lat]) => [lng * 100, lat * 100]);
    // The 40 km ring's label commits at (280, 257.03)–(320, 271.03) (40×14, no obstacle ahead of
    // it). This spot's own projected point, (300, 264), sits squarely inside that box, so neither
    // the chip's unflipped nor its flipped candidate clears it.
    const onTheRing = [spot({
      id: 50, name: 'On The Ring', lng: 3, lat: 2.64, regionName: 'Lakes', rid: 'Lakes',
    })];
    await withMeasuredMap(600, async () => {
      await withChipBoxes(40, 14, async () => {
        await renderMap({
          spots: onTheRing,
          regionNames: ['Lakes'],
          homePoint: [3, 3],
          chips: [{
            key: '50', locationId: 50, locationName: 'On The Ring', rating: 4,
          }],
        });
      });
    });
    expect(screen.queryByTestId('wf-row-map-chip')).toBeNull();
  });

  it('⚠️ the home marker outranks a region label at the same point — the behaviour change, pinned', async () => {
    // Field-geography plan §3.3 step 4/§5.5: droppable only in the presence of home geography.
    // A single-spot region whose only location IS the home point puts both anchors on the same
    // pixel — home is placed first and the region label's identical candidate box collides with it.
    drawGeo.mockImplementationOnce(() => ([lng, lat]) => [lng * 100, lat * 100]);
    const coincident = [spot({
      id: 9, name: 'Coincident', lng: 3, lat: 3, regionName: 'Lakes', rid: 'Lakes',
    })];
    await withMeasuredMap(600, async () => {
      await withChipBoxes(40, 14, async () => {
        await renderMap({ spots: coincident, regionNames: ['Lakes'], homePoint: [3, 3] });
      });
    });
    expect(screen.getByTestId('wf-row-map-home')).toBeInTheDocument();
    expect(screen.queryAllByTestId('wf-row-map-label')).toHaveLength(0);
  });

  it('draws nothing home-shaped under an away origin, even with a homePoint supplied', async () => {
    await withMeasuredMap(600, async () => {
      await withChipBoxes(40, 14, async () => {
        await renderMap({
          origin: { name: 'Dales', baseName: 'Bakewell' },
          homePoint: [3, 3],
        });
      });
    });
    expect(screen.queryByTestId('wf-row-map-home')).toBeNull();
    expect(screen.queryByTestId('wf-row-map-rings')).toBeNull();
    expect(screen.queryAllByTestId('wf-row-map-ring-label')).toHaveLength(0);
  });

  it('with no homePoint draws nothing home-shaped, and region labels keep their never-dropped behaviour', async () => {
    await withMeasuredMap(600, async () => {
      await renderMap();
    });
    expect(screen.queryByTestId('wf-row-map-home')).toBeNull();
    expect(screen.queryByTestId('wf-row-map-rings')).toBeNull();
    // The field-unchanged promise (§3.5): the SAME two labels, at the SAME centroids, as the
    // component's original behaviour with no home geography involved at all.
    expect(screen.getAllByTestId('wf-row-map-label').map((l) => l.textContent))
      .toEqual(['Coast', 'Dales']);
  });

  it('⚠️ with no homePoint, two COINCIDENT region labels both survive — proving `fits` never ran', async () => {
    // The test above uses Coast/Dales, whose centroids sit 200px apart and would never collide
    // either way — it cannot tell "never tested for collision" apart from "nothing here would
    // have collided anyway". This fixture collides on purpose (see the sibling "home marker
    // outranks a region label" test, which drops one of an identical pair once `hasHomeGeo` is
    // true): with no homePoint, `fits()` does not run at all, so BOTH survive at the exact same
    // pixel, which is what makes "never dropped" a claim about the code path rather than the data.
    const coincident = [
      spot({
        id: 20, name: 'A', lng: 4, lat: 6, regionName: 'North', rid: 'North',
      }),
      spot({
        id: 21, name: 'B', lng: 4, lat: 6, regionName: 'South', rid: 'South',
      }),
    ];
    await withMeasuredMap(600, async () => {
      await renderMap({ spots: coincident, regionNames: ['North', 'South'] });
    });
    expect(screen.getAllByTestId('wf-row-map-label').map((l) => l.textContent))
      .toEqual(['North', 'South']);
  });

  it('the hint corner still wins against a ring label anchored into it', async () => {
    // Frame 60×53 (height rounds from the 0.88 aspect floor), hint box {0, 29, 118, 24} (selectable
    // — the default two-region fixture). A 40×30 ring label's box-top is only "safe" (inside the
    // frame at every nudge rung) within y ∈ [1, 22] — and the hint's own collision band, inflated by
    // its 2px pad, is (-3, 55): it swallows that ENTIRE safe range, so every one of the seven nudge
    // rungs is rejected either by the frame edge or by the hint, regardless of the ring's own anchor.
    // Anchored here (dy=0 lands at y=10, comfortably "safe") so the drop is provably the hint's
    // doing, not the frame's.
    drawGeo.mockImplementationOnce(() => ([lng, lat]) => [lng * 100, lat * 100]);
    await withMeasuredMap(60, async () => {
      await withChipBoxes(30, 30, async () => {
        await renderMap({ homePoint: [0.2, 0.6097] });
      });
    });
    expect(screen.getByTestId('wf-row-map-hint')).toBeInTheDocument();
    expect(screen.queryByTestId('wf-row-map-ring-label')).toBeNull();
  });

  it('orders the layers: canvas → .wf-mgeo (rings SVG first within it) → .wf-mlab → .wf-mchips', async () => {
    drawGeo.mockImplementationOnce(() => ([lng, lat]) => [lng * 100, lat * 100]);
    await withMeasuredMap(600, async () => {
      await withChipBoxes(40, 14, async () => {
        await renderMap({
          homePoint: [3, 3],
          chips: [{
            key: '1', locationId: 1, locationName: 'Bamburgh', rating: 4,
          }],
        });
      });
    });
    const mapbox = screen.getByTestId('wf-row-map-canvas').parentElement;
    const children = Array.from(mapbox.children);
    expect(children[0].tagName).toBe('CANVAS');
    expect(children[1]).toHaveClass('wf-mgeo');
    expect(children[2]).toHaveClass('wf-mlab');
    expect(children[3]).toHaveClass('wf-mchips');
    // Rings paint over the field but under every label — first child within `.wf-mgeo`, not the
    // map box's first child (which would put them under the paint instead).
    expect(children[1].firstChild.tagName.toLowerCase()).toBe('svg');
  });

  it('caps at six on a phone even with home geography also claiming space', async () => {
    // At this stub scale both rings skip (the "both skipped" test above pins that threshold), so
    // what this fixture actually exercises is the HOME MARKER — placed in the same priority slot
    // the rings share — outranking a chip that would otherwise have taken the cap's last place.
    // Home projects onto `Spot 0`'s own point exactly, so its box collides with both of that chip's
    // candidates (unflipped and flipped) and `Spot 6` fills the cap in its place.
    useIsMobile.mockReturnValue(true);
    try {
      await withMeasuredMap(600, async () => {
        await withChipBoxes(6, 6, async () => {
          await renderMap({ spots: capSpots(), chips: capChips(), homePoint: [4, 6] });
        });
      });
      const drawn = screen.getAllByTestId('wf-row-map-chip').map((n) => n.dataset.location);
      expect(drawn).toHaveLength(6);
      expect(drawn).not.toContain('Spot 0');
      expect(drawn).toContain('Spot 6');
    } finally {
      useIsMobile.mockReturnValue(false);
    }
  });

  it('carries no click handler of its own — the pointer pass-through is a browser-verified CSS claim', async () => {
    drawGeo.mockImplementationOnce(() => ([lng, lat]) => [lng * 100, lat * 100]);
    await withMeasuredMap(600, async () => {
      await withChipBoxes(40, 14, async () => {
        await renderMap({ homePoint: [3, 3] });
      });
    });
    const geo = document.querySelector('.wf-mgeo');
    expect(geo).not.toHaveAttribute('onclick');
    // Nothing is listening on the layer itself; the canvas underneath is what `handleClick` tests
    // below confirm still receives the click. `pointer-events: none` is a CSS claim jsdom cannot
    // hit-test — see the PR's own browser-verification pass.
    expect(() => fireEvent.click(geo)).not.toThrow();
  });

  it('still selects a region by centroid click with rings and the home marker also on the field', async () => {
    const { onSelectRegion } = await withMeasuredMap(600, async () => {
      const handles = await withChipBoxes(
        40, 14, async () => renderMap({ homePoint: [3, 3] }),
      );
      fireEvent.click(stubCanvasBox(600), { clientX: 60, clientY: 60 });
      return handles;
    });
    expect(onSelectRegion).toHaveBeenCalledWith('Coast');
  });
});
