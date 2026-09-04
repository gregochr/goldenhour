import { describe, it, expect } from 'vitest';
import {
  verdictWord, WORTH_IT_THRESHOLD, MAYBE_THRESHOLD,
  chipBudget,
  hottestRegion, regionLabelItems, REGION_LABEL_MAX_ZOOM,
  homeLabelItems, HOME_LABEL_MAX_ZOOM,
  ringLabelItems, RING_LABEL_MAX_ZOOM,
  chipCandidates,
  placeLabelPass,
} from '../utils/mapLabels.js';

const spot = (name, rid, rating, driveMinutes = null) => ({
  name, rid, rating, driveMinutes,
});

describe('mapLabels — verdictWord', () => {
  it('pins the thresholds exactly (README "Verdict thresholds")', () => {
    expect(WORTH_IT_THRESHOLD).toBe(3.7);
    expect(MAYBE_THRESHOLD).toBe(2.8);
  });

  it('bands a whole-star rating into Worth it / Maybe / Poor', () => {
    expect(verdictWord(5)).toBe('Worth it');
    expect(verdictWord(4)).toBe('Worth it');
    expect(verdictWord(3)).toBe('Maybe');
    expect(verdictWord(2)).toBe('Poor');
    expect(verdictWord(1)).toBe('Poor');
  });

  it('straddles the exact boundary values', () => {
    expect(verdictWord(3.7)).toBe('Worth it');
    expect(verdictWord(3.699)).toBe('Maybe');
    expect(verdictWord(2.8)).toBe('Maybe');
    expect(verdictWord(2.799)).toBe('Poor');
  });

  it('returns null for an absent or non-finite rating, never a false verdict', () => {
    expect(verdictWord(null)).toBeNull();
    expect(verdictWord(undefined)).toBeNull();
    expect(verdictWord(NaN)).toBeNull();
  });
});

describe('mapLabels — chipBudget (README "Density ramps with zoom")', () => {
  it('is exactly the README formula: round(clamp(6 + (zoom-8.6)*11, 6, 60))', () => {
    expect(chipBudget(8.6)).toBe(6);
    expect(chipBudget(9.6)).toBe(17); // 6 + 1*11 = 17
    expect(chipBudget(10.6)).toBe(28); // 6 + 2*11 = 28
  });

  it('floors at the minimum below the base zoom — never negative, never below 6', () => {
    expect(chipBudget(0)).toBe(6);
    expect(chipBudget(8)).toBe(6);
  });

  it('ceils at the maximum — the bundle\'s 60', () => {
    expect(chipBudget(13.51)).toBe(60); // 6 + 4.91*11 ≈ 60.0
    expect(chipBudget(20)).toBe(60);
  });

  it('pins the two named edges from the task brief: z8.6 and z13', () => {
    expect(chipBudget(8.6)).toBe(6);
    // z13 is below the ceiling (54, not yet 60) — the edge worth pinning here is that it is
    // MID-RAMP, not floored and not yet ceiled, which is what makes it a meaningful boundary to
    // test placement/density behaviour at rather than a degenerate min/max case.
    expect(chipBudget(13)).toBe(54);
  });

  it('rounds rather than truncates', () => {
    // 6 + (8.65 - 8.6) * 11 = 6.55 -> rounds to 7
    expect(chipBudget(8.65)).toBe(7);
  });
});

describe('mapLabels — hottestRegion', () => {
  it('picks the region with the highest MEAN rating', () => {
    const spots = [
      spot('a', 'North', 2), spot('b', 'North', 2),
      spot('c', 'South', 5),
    ];
    expect(hottestRegion(spots)).toBe('South');
  });

  it('ignores non-finite ratings when averaging — an unrated spot does not drag its region cold silently past a real one', () => {
    const spots = [
      spot('a', 'North', null), spot('b', 'North', null),
      spot('c', 'South', 1),
    ];
    // North's mean-of-finite is 0 (no finite readings at all); South's is 1. South wins.
    expect(hottestRegion(spots)).toBe('South');
  });

  it('returns null for an empty catalogue', () => {
    expect(hottestRegion([])).toBeNull();
  });
});

describe('mapLabels — regionLabelItems', () => {
  const project = (s) => [s.lng, s.lat];

  it('one item per distinct region, centred at the projected centroid', () => {
    const spots = [
      { name: 'a', rid: 'North', rating: 3, lat: 10, lng: 0 },
      { name: 'b', rid: 'North', rating: 5, lat: 20, lng: 0 },
      { name: 'c', rid: 'South', rating: 1, lat: 0, lng: 100 },
    ];
    const items = regionLabelItems(spots, project, 9);
    expect(items).toHaveLength(2);
    // project(s) = [lng, lat], so centroid's [x, y] is [mean(lng), mean(lat)] = [0, mean([10,20])].
    const north = items.find((i) => i.rid === 'North');
    expect(north.x).toBe(0);
    expect(north.y).toBe(15);
  });

  it('marks the hottest region and only the hottest region', () => {
    const spots = [
      { name: 'a', rid: 'North', rating: 2, lat: 0, lng: 0 },
      { name: 'b', rid: 'South', rating: 5, lat: 0, lng: 1 },
    ];
    const items = regionLabelItems(spots, project, 9);
    expect(items.find((i) => i.rid === 'South').hot).toBe(true);
    expect(items.find((i) => i.rid === 'North').hot).toBe(false);
  });

  it('is empty at/above REGION_LABEL_MAX_ZOOM (11.2) — self-gated', () => {
    const spots = [{
      name: 'a', rid: 'North', rating: 3, lat: 0, lng: 0,
    }];
    expect(regionLabelItems(spots, project, REGION_LABEL_MAX_ZOOM)).toEqual([]);
    expect(regionLabelItems(spots, project, 12)).toEqual([]);
    expect(regionLabelItems(spots, project, REGION_LABEL_MAX_ZOOM - 0.01)).toHaveLength(1);
  });

  it('is empty for an empty pool', () => {
    expect(regionLabelItems([], project, 9)).toEqual([]);
  });
});

describe('mapLabels — homeLabelItems', () => {
  it('one item below HOME_LABEL_MAX_ZOOM (13), positioned at the home point', () => {
    const items = homeLabelItems({ x: 42, y: 7 }, 9);
    expect(items).toEqual([{ key: 'home', x: 42, y: 7 }]);
  });

  it('is empty at/above the zoom gate', () => {
    expect(homeLabelItems({ x: 0, y: 0 }, HOME_LABEL_MAX_ZOOM)).toEqual([]);
    expect(homeLabelItems({ x: 0, y: 0 }, 15)).toEqual([]);
    expect(homeLabelItems({ x: 0, y: 0 }, HOME_LABEL_MAX_ZOOM - 0.01)).toHaveLength(1);
  });
});

describe('mapLabels — ringLabelItems', () => {
  const base = {
    homePoint: { x: 100, y: 100 },
    zoom: 9,
    ringsWithRadius: [{ mi: 25, minutes: 45, r: 30 }, { mi: 50, minutes: 90, r: 60 }],
    frameHeight: 300,
  };

  it('positions x = home.x + 26, y = home.y - r', () => {
    const items = ringLabelItems({ ...base, reachMeasured: false });
    expect(items).toEqual([
      { key: 'ring:25', x: 126, y: 70, text: '25 mi' },
      { key: 'ring:50', x: 126, y: 40, text: '50 mi' },
    ]);
  });

  it('states DISTANCE by default and DURATION only under reachMeasured — reach-vocabulary honesty', () => {
    const distance = ringLabelItems({ ...base, reachMeasured: false });
    expect(distance.map((i) => i.text)).toEqual(['25 mi', '50 mi']);

    const duration = ringLabelItems({
      ...base, reachMeasured: true, formatDuration: (m) => `${m}min`,
    });
    expect(duration.map((i) => i.text)).toEqual(['45min', '90min']);
  });

  it('drops a ring label whose y falls outside the (10, frameHeight-10) keep-in', () => {
    const items = ringLabelItems({
      ...base,
      reachMeasured: false,
      ringsWithRadius: [{ mi: 25, minutes: 45, r: 95 }], // y = 100-95 = 5, <= 10
    });
    expect(items).toEqual([]);
  });

  it('is empty at/above RING_LABEL_MAX_ZOOM (10.6) — the same gate MapHeatLayer draws the rings at', () => {
    expect(ringLabelItems({ ...base, zoom: RING_LABEL_MAX_ZOOM, reachMeasured: false })).toEqual([]);
    expect(ringLabelItems({ ...base, zoom: 11, reachMeasured: false })).toEqual([]);
  });
});

describe('mapLabels — chipCandidates', () => {
  it('sorts best score first, then nearest', () => {
    const spots = [
      spot('low-far', 'A', 2, 100),
      spot('high', 'A', 5, 50),
      spot('low-near', 'A', 2, 10),
    ];
    const result = chipCandidates({ spots, zoom: 13 });
    expect(result.map((s) => s.name)).toEqual(['high', 'low-near', 'low-far']);
  });

  it('sorts a missing rating and a missing drive time LAST, never first', () => {
    const spots = [
      spot('unrated', 'A', null),
      spot('rated', 'A', 1),
    ];
    expect(chipCandidates({ spots, zoom: 13 }).map((s) => s.name)).toEqual(['rated', 'unrated']);
  });

  it('among equal stars, tide alignment is the tiebreaker — the aligned one survives a budget of one (bundle rev 2)', () => {
    // Two SAME-region spots, both out of view: the only slot either can reach is the region's own
    // single guaranteed "best" pick, so whichever the sort puts first is the ONLY one that survives
    // — a real budget-of-one, not merely a position within a longer list. Without the tiebreaker
    // the closer, unaligned spot (drive 5) would win on the sort's OLD final key.
    const notAligned = { ...spot('not-aligned', 'A', 4, 5), onTheLight: false };
    const aligned = { ...spot('aligned', 'A', 4, 50), onTheLight: true };
    const result = chipCandidates({
      spots: [notAligned, aligned], zoom: 13, inViewNames: new Set(),
    });
    expect(result.map((s) => s.name)).toEqual(['aligned']);
  });

  it('tide alignment never overrides a HIGHER star rating — score is still the first sort key', () => {
    const higherNotAligned = { ...spot('higher', 'A', 5, 5), onTheLight: false };
    const lowerAligned = { ...spot('lower', 'A', 4, 5), onTheLight: true };
    const result = chipCandidates({ spots: [lowerAligned, higherNotAligned], zoom: 13 });
    expect(result.map((s) => s.name)).toEqual(['higher', 'lower']);
  });

  it('drive time still breaks a tie when neither spot is tide-aligned', () => {
    const spots = [spot('far', 'A', 3, 100), spot('near', 'A', 3, 10)];
    expect(chipCandidates({ spots, zoom: 13 }).map((s) => s.name)).toEqual(['near', 'far']);
  });

  it('always includes the best-in-region candidate, even when it is out of view', () => {
    const spots = [
      spot('best-of-region', 'Remote', 5, 5),
    ];
    const result = chipCandidates({ spots, zoom: 9, inViewNames: new Set() });
    expect(result.map((s) => s.name)).toEqual(['best-of-region']);
  });

  it('caps the in-view slice at chipBudget(zoom), on top of (not instead of) best-per-region', () => {
    // One region whose best is out of view (always included), plus a second region with more
    // in-view spots than the budget allows at this zoom.
    const outOfView = spot('region-best', 'Quiet', 4, 1);
    const inViewSpots = Array.from({ length: 10 }, (_, i) => spot(`v${i}`, 'Busy', 3, i));
    const spots = [outOfView, ...inViewSpots];
    const inViewNames = new Set(inViewSpots.map((s) => s.name));
    const result = chipCandidates({
      spots, zoom: 8.6, inViewNames,
    }); // budget = 6
    expect(result).toHaveLength(1 /* best-per-region */ + 6 /* budget */);
    expect(result.map((s) => s.name)).toContain('region-best');
  });

  it('never lists the same spot twice — the best-per-region pick and the in-view slice can overlap', () => {
    const spots = [spot('only', 'A', 5, 1)];
    const result = chipCandidates({ spots, zoom: 13, inViewNames: new Set(['only']) });
    expect(result).toHaveLength(1);
  });

  it('the selected location always appears, moved to the front, even if it lost every other test', () => {
    const selected = spot('selected', 'Faraway', 1, 999);
    const winners = Array.from({ length: 20 }, (_, i) => spot(`w${i}`, 'Busy', 5, i));
    const spots = [selected, ...winners];
    const result = chipCandidates({
      spots, zoom: 13, inViewNames: new Set(winners.map((s) => s.name)), selectedName: 'selected',
    });
    expect(result[0].name).toBe('selected');
    // And it is not ALSO present a second time further down the list.
    expect(result.filter((s) => s.name === 'selected')).toHaveLength(1);
  });

  it('a selected name absent from `spots` (filtered out) is simply not added — no phantom chip', () => {
    const spots = [spot('a', 'A', 3)];
    const result = chipCandidates({ spots, zoom: 13, selectedName: 'nowhere' });
    expect(result.map((s) => s.name)).toEqual(['a']);
  });

  it('with no inViewNames given, every spot counts as in view (the safe "offer more" default)', () => {
    const spots = Array.from({ length: 3 }, (_, i) => spot(`s${i}`, 'A', 5 - i, i));
    const result = chipCandidates({ spots, zoom: 13 });
    expect(result).toHaveLength(3);
  });

});

describe('mapLabels — placeLabelPass (the whole greedy priority pass)', () => {
  it('places every item when nothing collides', () => {
    const items = [
      { key: 'a', x: 50, y: 50, w: 10, h: 10 },
      { key: 'b', x: 200, y: 200, w: 10, h: 10 },
    ];
    const placed = placeLabelPass(items, 300, 300);
    expect(placed.size).toBe(2);
    expect(placed.has('a')).toBe(true);
    expect(placed.has('b')).toBe(true);
  });

  it('an earlier item in the array wins the space; a later, colliding one is DROPPED, never stacked', () => {
    // 100×100 boxes at the SAME anchor, comfortably inside the frame (not edge-rejected on their
    // own) but far larger than the ladder's own reach (MAP_NUDGES maxes at ±38px, its dx fallback
    // at ±(50+9)=59px here) — so no nudge can ever separate two same-anchor copies of this size,
    // and the outcome is decided purely by priority order.
    const items = [
      { key: 'first', x: 150, y: 150, w: 100, h: 100 },
      { key: 'second', x: 150, y: 150, w: 100, h: 100 },
    ];
    const placed = placeLabelPass(items, 300, 300);
    expect(placed.has('first')).toBe(true);
    expect(placed.has('second')).toBe(false);
    expect(placed.size).toBe(1);
  });

  it('pre-seeded obstacles block a candidate exactly like an already-placed label would', () => {
    const obstacles = [{
      x: 0, y: 0, w: 300, h: 300,
    }]; // covers the whole frame
    const items = [{ key: 'a', x: 150, y: 150, w: 10, h: 10 }];
    const placed = placeLabelPass(items, 300, 300, obstacles);
    expect(placed.size).toBe(0);
  });

  it('priority order is the array order — home, then rings, then regions, then chips', () => {
    // Two candidates, named after two different label KINDS, that can only fit one at a time
    // (see the box-size note on the test above); the FIRST in priority order must win regardless
    // of which kind it represents — and reversing the array must reverse the winner, proving it
    // is the ORDER deciding, not anything about "ring" vs "chip" as a category.
    const bigBoxAt = (key) => ({
      key, x: 150, y: 150, w: 250, h: 250,
    });
    const ringFirst = [bigBoxAt('ring:25'), bigBoxAt('chip:somewhere')];
    const placedRingFirst = placeLabelPass(ringFirst, 300, 300);
    expect(placedRingFirst.has('ring:25')).toBe(true);
    expect(placedRingFirst.has('chip:somewhere')).toBe(false);

    const chipFirst = [...ringFirst].reverse();
    const placedChipFirst = placeLabelPass(chipFirst, 300, 300);
    expect(placedChipFirst.has('chip:somewhere')).toBe(true);
    expect(placedChipFirst.has('ring:25')).toBe(false);
  });

  it('does not mutate the obstacles array handed in', () => {
    const obstacles = [{
      x: 0, y: 0, w: 10, h: 10,
    }];
    const before = JSON.parse(JSON.stringify(obstacles));
    placeLabelPass([{
      key: 'a', x: 200, y: 200, w: 10, h: 10,
    }], 300, 300, obstacles);
    expect(obstacles).toEqual(before);
  });
});

describe('mapLabels — density integration: the mid-zoom "hole" the bundle recorded (README §6)', () => {
  // "An earlier build stepped straight from 'one name per region' to 'all of them', which left a
  // hole in the middle: 13 named spots in view at county scale and only 2 labelled." This suite
  // reproduces that fixture shape and asserts the continuous ramp does NOT collapse to ~2.
  const REGIONS = ['North', 'South'];
  function fixtureCatalogue() {
    // 13 spots spread widely enough that the placer's collision test never blocks one purely on
    // geometry — the density BUDGET is what this suite is isolating, not collision luck.
    return Array.from({ length: 13 }, (_, i) => ({
      name: `spot-${i}`,
      rid: REGIONS[i % 2],
      rating: 5 - (i % 5),
      driveMinutes: i,
      lat: i * 2,
      lng: i * 2,
    }));
  }

  it('at COUNTY zoom (~9.6), the budget offers well more than 2 of 13 in-view spots', () => {
    const spots = fixtureCatalogue();
    const inViewNames = new Set(spots.map((s) => s.name));
    const zoom = 9.6;
    const candidates = chipCandidates({
      spots, zoom, inViewNames,
    });
    // The bundle's own measured target: "the local set at county scale" — comfortably more than
    // the 2-of-13 the step function produced. chipBudget(9.6) = 17, well above 13, so every spot
    // is offered; the regression this guards is the BUDGET collapsing, not the placer's collision
    // arbitration (which is `placeLabelPass`'s job and tested on its own above).
    expect(candidates.length).toBeGreaterThan(2);
    expect(candidates).toHaveLength(13);
  });

  it('at the REGIONAL GLANCE (z8.6, the budget floor), still offers one candidate per region — never zero', () => {
    const spots = fixtureCatalogue();
    const candidates = chipCandidates({
      spots, zoom: 8.6, inViewNames: new Set(),
    });
    // budget(8.6) = 6, and inViewNames is empty, so only the best-per-region guarantee fires —
    // proving that guarantee alone still names a destination in every region even with nothing
    // "in view" yet (e.g. before the first `moveend`).
    expect(candidates.length).toBe(REGIONS.length);
    expect(new Set(candidates.map((s) => s.rid))).toEqual(new Set(REGIONS));
  });

  it('the budget itself ramps continuously across the county-scale range — no step from ~1 to "all"', () => {
    // Sample the formula at four points between the regional glance and county scale; each step
    // must be a small, gradual climb (never a jump from a tiny number straight to a huge one).
    const samples = [8.6, 9.2, 9.8, 10.4].map(chipBudget);
    for (let i = 1; i < samples.length; i += 1) {
      expect(samples[i]).toBeGreaterThan(samples[i - 1]);
      // Each 0.6-zoom step adds 0.6*11=6.6, rounding to 6 or 7 — nowhere near a "jump to 60".
      expect(samples[i] - samples[i - 1]).toBeLessThanOrEqual(7);
    }
  });

  // LIVE-MEASURED (map-tab-v2-plan.md §3 P8 adversarial review, orchestrator's browser pass): at
  // real DOM sizes on the real map, county zoom 10.5 rendered 8 chips + 4 region names — no hole.
  // At the regional glance (z8.6) the same run measured 4 regions + 7 chips (11 names), best-first,
  // and the z11.2 region gate was crossed cleanly both ways. That live run is the authoritative
  // proof for a real browser's font metrics and real geography; the test below reproduces the
  // SHAPE of the same claim through the real placement pipeline (selection AND collision, not
  // selection alone as the tests above isolate) so a regression is caught in the unit suite too,
  // without needing a browser.
  it('through the REAL placement pass (selection + collision) at county z10.5, final count stays well above 2', () => {
    const spots = fixtureCatalogue();
    const inViewNames = new Set(spots.map((s) => s.name));
    const zoom = 10.5;
    // Spread wide enough (matching the fixture's own "collision never decides this" comment,
    // scaled up) that the placer's own arbitration is not the thing being measured here — the
    // BUDGET collapsing is.
    const project = (s) => [s.lng * 50, s.lat * 50];
    const chips = chipCandidates({ spots, zoom, inViewNames }).map((s) => {
      const [x, y] = project(s);
      return {
        key: `chip:${s.name}`, x, y, w: 60, h: 14,
      };
    });
    const regions = regionLabelItems(spots, project, zoom).map((r) => ({ ...r, w: 70, h: 12 }));
    // Regions first — the README's own priority order (home, rings, regions, THEN chips).
    const placed = placeLabelPass([...regions, ...chips], 1200, 900);
    expect(placed.size).toBeGreaterThan(2);
  });
});
