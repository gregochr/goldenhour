/**
 * Edge behaviour of `utils/mapLabels.js`: the hottest-region tie and mean rules, unprojectable
 * centroids, the ring-label frame margins, the chip ordering tie-breaks, the selected/pick handling
 * and the stood-down place budget. Companion to mapLabels.test.js; each case pins an answer the
 * happy-path suite never asks for.
 */
import { describe, it, expect } from 'vitest';
import {
  chipCandidates, fallbackChipSpots, hottestRegion, regionLabelItems, ringLabelItems,
  standDownRegionItems, stoodPlaceCandidates,
} from '../utils/mapLabels.js';

const spot = (name, rid, rating, over = {}) => ({
  name, rid, rating, driveMinutes: null, ...over,
});
const names = (list) => list.map((s) => s.name);

describe('hottestRegion — means and ties', () => {
  it('averages only the finite ratings of a region', () => {
    const spots = [
      spot('b1', 'B', 4), spot('b2', 'B', 4),
      spot('a1', 'A', 5), spot('a2', 'A', null),
    ];
    // A's finite mean is 5; averaging the null in as a value would make it 2.5 and hand B the win.
    expect(hottestRegion(spots)).toBe('A');
  });

  it('averages a region over its own spots only', () => {
    const spots = [
      spot('a1', 'A', 3), spot('b1', 'B', 5), spot('c1', 'C', 4),
    ];
    expect(hottestRegion(spots)).toBe('B');
  });

  it('compares each region on its own mean, not on the catalogue-wide mean', () => {
    const lowCrowd = Array.from({ length: 10 }, (_, i) => spot(`c${i}`, 'C', 1));
    const spots = [spot('a1', 'A', 3), spot('b1', 'B', 4), ...lowCrowd];
    // The catalogue-wide mean is about 1.4, below A's 3: a region compared on it would never replace A.
    expect(hottestRegion(spots)).toBe('B');
  });

  it('keeps the earlier region on an exact tie', () => {
    const spots = [spot('a1', 'A', 4), spot('b1', 'B', 4)];
    expect(hottestRegion(spots)).toBe('A');
  });

  it('keeps the incumbent against every later region it beats', () => {
    const spots = [
      spot('a1', 'A', 5), spot('b1', 'B', 2), spot('c1', 'C', 3), spot('d1', 'D', 1),
    ];
    expect(hottestRegion(spots)).toBe('A');
  });
});

describe('regionLabelItems — unprojectable centroids', () => {
  const at = (x, y) => () => [x, y];
  const one = [{
    name: 'a', rid: 'North', rating: 3,
  }];

  it('skips a region whose centroid x is not finite', () => {
    expect(regionLabelItems(one, at(NaN, 4), 9)).toEqual([]);
    expect(regionLabelItems(one, at(Infinity, 4), 9)).toEqual([]);
  });

  it('skips a region whose centroid y is not finite', () => {
    expect(regionLabelItems(one, at(3, NaN), 9)).toEqual([]);
    expect(regionLabelItems(one, at(3, -Infinity), 9)).toEqual([]);
  });

  it('keeps a region whose centroid is finite', () => {
    expect(regionLabelItems(one, at(3, 4), 9)).toEqual([{
      key: 'region:North', rid: 'North', x: 3, y: 4, hot: true,
    }]);
  });
});

describe('standDownRegionItems — unprojectable and empty regions', () => {
  const region = (points) => [{ rid: 'North', cause: 'rain', points }];
  const pt = { rid: 'North' };

  it('skips a region with no points at all', () => {
    expect(standDownRegionItems(region([]), () => [1, 2], 9)).toEqual([]);
  });

  it('skips a region whose centroid x is not finite', () => {
    expect(standDownRegionItems(region([pt]), () => [NaN, 2], 9)).toEqual([]);
  });

  it('skips a region whose centroid y is not finite', () => {
    expect(standDownRegionItems(region([pt]), () => [1, Infinity], 9)).toEqual([]);
  });

  it('keeps a projectable region', () => {
    expect(standDownRegionItems(region([pt]), () => [1, 2], 9)).toEqual([{
      key: 'region:North', rid: 'North', x: 1, y: 2, hot: false, standDown: 'rain',
    }]);
  });
});

describe('ringLabelItems — the frame margins', () => {
  const ring = (mi, r) => ({ mi, r, minutes: mi * 2 });
  const run = (rings, homeY = 100) => ringLabelItems({
    homePoint: { x: 50, y: homeY },
    zoom: 9,
    ringsWithRadius: rings,
    frameHeight: 200,
    reachMeasured: false,
  }).map((item) => item.key);

  it('drops a label exactly ten pixels from the top and keeps one at eleven', () => {
    expect(run([ring(1, 90)])).toEqual([]);
    expect(run([ring(1, 89)])).toEqual(['ring:1']);
  });

  it('drops a label exactly ten pixels from the bottom and keeps one at nine', () => {
    // frameHeight 200 -> the bottom margin starts at y = 190; with the home point at y = 200, r = 10 is on it.
    expect(run([ring(1, 10)], 200)).toEqual([]);
    expect(run([ring(1, 11)], 200)).toEqual(['ring:1']);
  });

  it('drops a label inside the bottom margin that is nearer the edge than ten pixels', () => {
    expect(run([ring(1, 5)], 200)).toEqual([]);
    expect(run([ring(1, 50)], 200)).toEqual(['ring:1']);
  });
});

describe('chipCandidates — ordering and the selected spot', () => {
  it('ranks an unrated spot below a rated one in the same region', () => {
    const shown = chipCandidates({
      spots: [spot('Unrated', 'R', null), spot('Rated', 'R', 2)],
      zoom: 9,
    });
    expect(names(shown)).toEqual(['Rated', 'Unrated']);
    const reversed = chipCandidates({
      spots: [spot('Rated', 'R', 2), spot('Unrated', 'R', null)],
      zoom: 9,
    });
    expect(names(reversed)).toEqual(['Rated', 'Unrated']);
  });

  it('prefers a tide match among equal ratings, whichever order the spots arrive in', () => {
    const plain = spot('Plain', 'R', 3, { tideTier: 'miss' });
    const match = spot('Match', 'R', 3, { tideTier: 'match' });
    expect(names(chipCandidates({ spots: [plain, match], zoom: 9 }))).toEqual(['Match', 'Plain']);
    expect(names(chipCandidates({ spots: [match, plain], zoom: 9 }))).toEqual(['Match', 'Plain']);
  });

  it('ranks a tide match above a plain spot of equal rating even when the plain one is nearer', () => {
    const near = spot('Near', 'R', 3, { tideTier: 'miss', driveMinutes: 10 });
    const far = spot('Far', 'R', 3, { tideTier: 'match', driveMinutes: 90 });
    expect(names(chipCandidates({ spots: [far, near], zoom: 9 }))).toEqual(['Far', 'Near']);
    expect(names(chipCandidates({ spots: [near, far], zoom: 9 }))).toEqual(['Far', 'Near']);
  });

  it('keeps topic-ringed spots out of the density budget', () => {
    const spots = Array.from({ length: 12 }, (_, i) => spot(`s${i + 1}`, 'R', 5 - i * 0.1, {
      topicRing: i < 3,
    }));
    const shown = chipCandidates({ spots, inViewNames: new Set(spots.map((s) => s.name)), zoom: 8 });
    // Budget at zoom 8 is 6: the three rings are free, so six plain spots follow them.
    expect(names(shown)).toEqual(['s1', 's2', 's3', 's4', 's5', 's6', 's7', 's8', 's9']);
  });

  it('does not promote a spot with no name when nothing is selected', () => {
    const spots = [
      spot('Named', 'North', 5),
      { name: null, rid: 'South', rating: 3, driveMinutes: null },
    ];
    expect(chipCandidates({ spots, zoom: 9 })).toEqual([spots[0], spots[1]]);
  });

  it('moves the selected spot to the front once, without repeating it', () => {
    const spots = [spot('A', 'R1', 5), spot('B', 'R2', 4), spot('C', 'R3', 3)];
    expect(names(chipCandidates({ spots, zoom: 9, selectedName: 'C' }))).toEqual(['C', 'A', 'B']);
  });

  it('puts the selected pick ahead of a better-ranked unselected one', () => {
    const spots = [
      spot('First', 'R1', 5, { askPick: { rank: 1 } }),
      spot('Second', 'R2', 4, { askPick: { rank: 2, selected: true } }),
      spot('Other', 'R3', 3),
    ];
    expect(names(chipCandidates({ spots, zoom: 9 }))).toEqual(['Second', 'First', 'Other']);
  });

  it('keeps the selected pick first when it is already listed first with the worse rank', () => {
    const spots = [
      spot('Second', 'R1', 5, { askPick: { rank: 2, selected: true } }),
      spot('First', 'R2', 4, { askPick: { rank: 1 } }),
    ];
    expect(names(chipCandidates({ spots, zoom: 9 }))).toEqual(['Second', 'First']);
  });

  it('orders picks of the same selection state by rank', () => {
    const spots = [
      spot('Second', 'R1', 5, { askPick: { rank: 2 } }),
      spot('First', 'R2', 4, { askPick: { rank: 1 } }),
    ];
    expect(names(chipCandidates({ spots, zoom: 9 }))).toEqual(['First', 'Second']);
  });
});

describe('fallbackChipSpots', () => {
  it('answers nothing when nothing is suppressed, without reading the rest of its arguments', () => {
    expect(fallbackChipSpots({ suppressedNames: new Set() })).toEqual([]);
  });

  it('keeps only the suppressed names', () => {
    const spots = [spot('A', 'R1', 5), spot('B', 'R2', 4)];
    expect(names(fallbackChipSpots({ suppressedNames: new Set(['B']), spots, zoom: 9 }))).toEqual(['B']);
  });
});

describe('stoodPlaceCandidates — selection and budget', () => {
  const place = (key, name) => ({
    key, name, lat: 55, lng: -1.5,
  });
  const ALL = { contains: () => true };

  it('lists the selected place once and the rest after it, within a roomy budget', () => {
    const result = stoodPlaceCandidates({
      stoodPlaces: [place('#1', 'A'), place('#2', 'B'), place('#3', 'C')],
      zoom: 12,
      bounds: ALL,
      budgetLeft: 5,
      selectedName: 'B',
    });
    expect(names(result)).toEqual(['B', 'A', 'C']);
  });

  it('lists every place when none is selected', () => {
    const result = stoodPlaceCandidates({
      stoodPlaces: [place('#1', 'A'), place('#2', 'B')],
      zoom: 12,
      bounds: ALL,
      budgetLeft: 5,
    });
    expect(names(result)).toEqual(['A', 'B']);
  });

  it('lists a place with no name when nothing is selected', () => {
    const result = stoodPlaceCandidates({
      stoodPlaces: [{ key: '#1', name: null, lat: 55, lng: -1.5 }],
      zoom: 12,
      bounds: ALL,
      budgetLeft: 5,
      selectedName: null,
    });
    expect(result.map((p) => p.key)).toEqual(['#1']);
  });

  it('selects nothing when no name is asked for, even for a place with no name', () => {
    const result = stoodPlaceCandidates({
      stoodPlaces: [{ key: '#1', name: null, lat: 55, lng: -1.5 }],
      zoom: 5,
      bounds: ALL,
      budgetLeft: 5,
      selectedName: null,
    });
    expect(result).toEqual([]);
  });
});
