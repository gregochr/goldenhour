/**
 * Tests for `utils/mapCallout.js` — the Map tab selection callout's pure logic (map-tab-v2-plan.md
 * §3 P9, `docs/design/map-tab-v2/README.md` §7, `docs/design/map-tab-v2/map-tab-v2.js`'s
 * `calBand`/`anchorCal`).
 *
 * Covers: the band's ≥50%-frame-width bar rule and its 90px floor; `anchorCallout`'s below/flip/
 * clamp/tail maths at the exact paddings the design bundle authored; the facts row's
 * `reachMeasured` discipline (drive/miles/leave-by/dark-sky, each independently gated); the
 * midnight-crossing leave-by rule's both branches; the tide-topic type-map filter; and the region-
 * gloss index join.
 */
import { describe, it, expect } from 'vitest';
import {
  anchorCallout, buildRegionGlossIndex, calloutBand, calloutFacts, calloutLeaveBy,
  CALLOUT_GAP, CALLOUT_MARGIN, CALLOUT_MIN_BAND, filterCalloutTopics, isCoastalTidalLocation,
  regionGlossFor,
} from '../utils/mapCallout.js';

describe('calloutBand', () => {
  it('defaults to the whole frame, minus an 8px margin on each edge, with no bars', () => {
    const band = calloutBand({ frameWidth: 400, frameHeight: 600, bars: [] });
    expect(band).toEqual({ top: 8, bot: 592 });
  });

  it('ignores a bar narrower than half the frame width, regardless of position', () => {
    const bars = [{ top: 0, bottom: 40, width: 199, height: 40 }];
    const band = calloutBand({ frameWidth: 400, frameHeight: 600, bars });
    expect(band).toEqual({ top: 8, bot: 592 });
  });

  it('counts a bar exactly at half the frame width — the ">=" boundary', () => {
    const bars = [{ top: 0, bottom: 40, width: 200, height: 40 }];
    const band = calloutBand({ frameWidth: 400, frameHeight: 600, bars });
    expect(band.top).toBe(48); // bottom (40) + 8px pad
  });

  it('a wide bar in the TOP half raises the ceiling to its bottom edge + 8px', () => {
    const bars = [{ top: 0, bottom: 50, width: 300, height: 50 }];
    const band = calloutBand({ frameWidth: 400, frameHeight: 600, bars });
    expect(band).toEqual({ top: 58, bot: 592 });
  });

  it('a wide bar in the BOTTOM half lowers the floor to its top edge - 8px', () => {
    const bars = [{ top: 560, bottom: 600, width: 300, height: 40 }];
    const band = calloutBand({ frameWidth: 400, frameHeight: 600, bars });
    expect(band).toEqual({ top: 8, bot: 552 });
  });

  it('combines a top bar and a bottom bar into one narrower band', () => {
    const bars = [
      { top: 0, bottom: 50, width: 300, height: 50 },
      { top: 560, bottom: 600, width: 300, height: 40 },
    ];
    const band = calloutBand({ frameWidth: 400, frameHeight: 600, bars });
    expect(band).toEqual({ top: 58, bot: 552 });
  });

  it('a bar exactly on the frame midline (bottom === frameHeight/2) counts as TOP', () => {
    // `bar.bottom < frameHeight * 0.5` — exactly at the midline falls to the ELSE (bottom) branch.
    const bars = [{ top: 250, bottom: 300, width: 300, height: 50 }];
    const band = calloutBand({ frameWidth: 400, frameHeight: 600, bars });
    expect(band).toEqual({ top: 8, bot: 242 });
  });

  it('never returns a band narrower than the 90px floor, however aggressive the bars', () => {
    const bars = [
      { top: 0, bottom: 280, width: 300, height: 280 },
      { top: 290, bottom: 600, width: 300, height: 310 },
    ];
    const band = calloutBand({ frameWidth: 400, frameHeight: 600, bars });
    expect(band.bot - band.top).toBe(CALLOUT_MIN_BAND);
    expect(band.top).toBe(288);
    expect(band.bot).toBe(288 + CALLOUT_MIN_BAND);
  });

  it('ignores a bar with zero width or zero height', () => {
    const bars = [
      { top: 0, bottom: 50, width: 0, height: 0 },
      { top: 0, bottom: 50, width: 300, height: 0 },
    ];
    const band = calloutBand({ frameWidth: 400, frameHeight: 600, bars });
    expect(band).toEqual({ top: 8, bot: 592 });
  });

  it('tolerates a missing/undefined bars array', () => {
    expect(calloutBand({ frameWidth: 400, frameHeight: 600, bars: undefined }))
      .toEqual({ top: 8, bot: 592 });
  });
});

describe('anchorCallout', () => {
  const band = { top: 8, bot: 592 };

  it('prefers BELOW the marker, at the bundle\'s own 22px gap', () => {
    const box = anchorCallout({
      point: { x: 200, y: 100 }, cardWidth: 286, cardHeight: 250, frameWidth: 400, band,
    });
    expect(box.below).toBe(true);
    expect(box.top).toBe(100 + CALLOUT_GAP);
  });

  it('flips ABOVE when below would overflow the band', () => {
    const box = anchorCallout({
      point: { x: 200, y: 500 }, cardWidth: 286, cardHeight: 250, frameWidth: 400, band,
    });
    // below: 500 + 22 + 250 = 772 > 592 (band.bot) — must flip
    expect(box.below).toBe(false);
    expect(box.top).toBe(500 - CALLOUT_GAP - 250);
  });

  it('flips BACK to below when even the above position would clear band.top', () => {
    // A marker near the very top of the band, with a card tall enough that "above" would go
    // negative relative to the band — the bundle's own `if (!below && top < band.top)` rescue.
    const box = anchorCallout({
      point: { x: 200, y: 20 }, cardWidth: 286, cardHeight: 250, frameWidth: 400, band,
    });
    // below: 20 + 22 + 250 = 292 <= 592 — fits below in the first place, so this case exercises
    // the rescue via a narrower band that would otherwise reject BOTH positions.
    expect(box.below).toBe(true);
  });

  it('the flip-back rescue fires when the band itself is too narrow for "above"', () => {
    const narrowBand = { top: 100, bot: 200 };
    const box = anchorCallout({
      point: { x: 200, y: 120 }, cardWidth: 286, cardHeight: 250, frameWidth: 400, band: narrowBand,
    });
    // below: 120 + 22 + 250 = 392 > 200 (overflow) → try above: 120 - 22 - 250 = -152 < band.top
    // (100) → rescue forces below=true, top = 120 + 22 = 142 → then clamped to
    // [band.top, max(band.top, band.bot - cardHeight)] = [100, max(100, -50)] = [100, 100], so the
    // final clamp pins it to the band's own top edge.
    expect(box.below).toBe(true);
    expect(box.top).toBe(100);
  });

  it('clamps the top into the band when even the rescued "below" position overflows', () => {
    const tinyBand = { top: 100, bot: 100 + CALLOUT_MIN_BAND };
    const box = anchorCallout({
      point: { x: 200, y: 100 }, cardWidth: 286, cardHeight: 250, frameWidth: 400, band: tinyBand,
    });
    expect(box.top).toBeGreaterThanOrEqual(tinyBand.top);
    expect(box.top).toBeLessThanOrEqual(Math.max(tinyBand.top, tinyBand.bot - 250));
  });

  it('clamps horizontally to the 8px margin on the left edge', () => {
    const box = anchorCallout({
      point: { x: 5, y: 100 }, cardWidth: 286, cardHeight: 250, frameWidth: 400, band,
    });
    expect(box.left).toBe(CALLOUT_MARGIN);
  });

  it('clamps horizontally to the 8px margin on the right edge', () => {
    const box = anchorCallout({
      point: { x: 395, y: 100 }, cardWidth: 286, cardHeight: 250, frameWidth: 400, band,
    });
    expect(box.left).toBe(400 - 286 - CALLOUT_MARGIN);
  });

  it('centres the card under the point when there is room on both sides', () => {
    const box = anchorCallout({
      point: { x: 200, y: 100 }, cardWidth: 286, cardHeight: 250, frameWidth: 400, band,
    });
    expect(box.left).toBe(200 - 286 / 2);
  });

  it('clamps the tail to stay within the card, at least 13px from either edge', () => {
    // Point far to the left of the (clamped) card — the tail must not run off the card's own edge.
    const box = anchorCallout({
      point: { x: 5, y: 100 }, cardWidth: 286, cardHeight: 250, frameWidth: 400, band,
    });
    expect(box.tailLeft).toBe(13);
  });

  it('clamps the tail on the right side symmetrically', () => {
    const box = anchorCallout({
      point: { x: 395, y: 100 }, cardWidth: 286, cardHeight: 250, frameWidth: 400, band,
    });
    expect(box.tailLeft).toBe(286 - 24);
  });

  it('positions the tail directly under the point when the card is not clamped', () => {
    const box = anchorCallout({
      point: { x: 200, y: 100 }, cardWidth: 286, cardHeight: 250, frameWidth: 400, band,
    });
    // point.x - left - 5.5, unclamped: 200 - 57 - 5.5 = 137.5
    expect(box.tailLeft).toBeCloseTo(137.5, 5);
  });

  it('honours caller-supplied gap/margin overrides', () => {
    const box = anchorCallout({
      point: { x: 200, y: 100 }, cardWidth: 286, cardHeight: 250, frameWidth: 400, band,
      gap: 40, margin: 20,
    });
    expect(box.top).toBe(140);
    expect(box.left).toBeGreaterThanOrEqual(20);
  });
});

describe('calloutLeaveBy', () => {
  it('is null when the drive is unmeasured', () => {
    expect(calloutLeaveBy('2026-06-15T04:30:00Z', null)).toBeNull();
    expect(calloutLeaveBy('2026-06-15T04:30:00Z', undefined)).toBeNull();
  });

  it('is null when the event time is absent', () => {
    expect(calloutLeaveBy(null, 60)).toBeNull();
  });

  it('carries no day word when the departure shares the event\'s UK day', () => {
    // 04:30 UTC on 15 June = 05:30 BST; a 60-minute drive + 20 setup leaves 03:50 BST, same day.
    const leave = calloutLeaveBy('2026-06-15T04:30:00Z', 60);
    expect(leave).not.toBeNull();
    expect(leave.dayWord).toBeNull();
  });

  it('marks the day when the departure crosses midnight (never wraps silently)', () => {
    // 00:30 UTC on 15 June = 01:30 BST; a 100-minute drive + 20 setup (2h total) leaves 23:30 BST
    // on the 14th — the prototype's `m2hm` would silently wrap that to "23:30" with no day marker
    // at all (plan §4.12's own ban); this rule marks it instead of suppressing it.
    const leave = calloutLeaveBy('2026-06-15T00:30:00Z', 100);
    expect(leave).not.toBeNull();
    expect(leave.time).toBe('23:30');
    expect(leave.dayWord).not.toBeNull();
    expect(typeof leave.dayWord).toBe('string');
  });
});

describe('calloutFacts', () => {
  it('omits Drive AND Leave by entirely when the drive is unmeasured', () => {
    const facts = calloutFacts({ driveMinutes: null, bortleClass: 3 });
    expect(facts.map((f) => f.key)).toEqual(['dark']);
  });

  it('shows a bare duration when miles are unknown (an away origin)', () => {
    const facts = calloutFacts({ driveMinutes: 95, distanceMiles: null });
    const drive = facts.find((f) => f.key === 'drive');
    // No trailing "· N mi" clause at all — not merely "not a match", since "min" itself contains
    // the substring "mi" and would falsely pass a bare `/mi/` check.
    expect(drive.value).toBe('1h 35min');
    expect(drive.value).not.toContain('·');
  });

  it('appends straight-line miles only when they are known (home origin)', () => {
    const facts = calloutFacts({ driveMinutes: 95, distanceMiles: 42 });
    const drive = facts.find((f) => f.key === 'drive');
    expect(drive.value).toBe('1h 35min · 42 mi');
  });

  it('adds a Leave-by fact only when BOTH the drive and the event time are known', () => {
    const withTime = calloutFacts({ driveMinutes: 60, eventTimeIso: '2026-06-15T04:30:00Z' });
    expect(withTime.some((f) => f.key === 'leave')).toBe(true);
    const withoutTime = calloutFacts({ driveMinutes: 60, eventTimeIso: null });
    expect(withoutTime.some((f) => f.key === 'leave')).toBe(false);
  });

  it('marks the dark-sky fact when the Bortle class is at or below the threshold', () => {
    const dark = calloutFacts({ driveMinutes: null, bortleClass: 3 });
    expect(dark.find((f) => f.key === 'dark').value).toBe('3 · dark');
  });

  it('does not claim dark-sky above the threshold', () => {
    const notDark = calloutFacts({ driveMinutes: null, bortleClass: 7 });
    expect(notDark.find((f) => f.key === 'dark').value).toBe('7');
  });

  it('omits the dark-sky fact when the Bortle class is unknown', () => {
    const facts = calloutFacts({ driveMinutes: null, bortleClass: null });
    expect(facts.find((f) => f.key === 'dark')).toBeUndefined();
  });

  it('returns an empty array when nothing is known at all', () => {
    expect(calloutFacts({ driveMinutes: null, bortleClass: null })).toEqual([]);
  });

  it('orders Drive, then Leave by, then Dark sky', () => {
    const facts = calloutFacts({
      driveMinutes: 30, distanceMiles: 10, eventTimeIso: '2026-06-15T04:30:00Z', bortleClass: 2,
    });
    expect(facts.map((f) => f.key)).toEqual(['drive', 'leave', 'dark']);
  });
});

describe('isCoastalTidalLocation', () => {
  it('is true when tideType carries at least one preference', () => {
    expect(isCoastalTidalLocation({ tideType: ['HIGH'] })).toBe(true);
  });

  it('is false for an empty tideType array', () => {
    expect(isCoastalTidalLocation({ tideType: [] })).toBe(false);
  });

  it('is false when tideType is absent or null', () => {
    expect(isCoastalTidalLocation({})).toBe(false);
    expect(isCoastalTidalLocation({ tideType: null })).toBe(false);
    expect(isCoastalTidalLocation(null)).toBe(false);
  });
});

describe('filterCalloutTopics', () => {
  const kingBadge = { type: 'KING_TIDE', label: 'King tide' };
  const springBadge = { type: 'SPRING_TIDE', label: 'Spring tide' };
  const dustBadge = { type: 'DUST', label: 'Saharan dust' };

  it('keeps a non-tide badge regardless of coastalTidal', () => {
    expect(filterCalloutTopics([dustBadge], false)).toEqual([dustBadge]);
    expect(filterCalloutTopics([dustBadge], true)).toEqual([dustBadge]);
  });

  it('drops KING_TIDE/SPRING_TIDE badges for a non-coastal-tidal location', () => {
    expect(filterCalloutTopics([kingBadge, springBadge, dustBadge], false)).toEqual([dustBadge]);
  });

  it('keeps KING_TIDE/SPRING_TIDE badges for a coastal-tidal location', () => {
    expect(filterCalloutTopics([kingBadge, springBadge], true)).toEqual([kingBadge, springBadge]);
  });

  it('tolerates a missing/undefined badges array', () => {
    expect(filterCalloutTopics(undefined, true)).toEqual([]);
    expect(filterCalloutTopics(null, false)).toEqual([]);
  });
});

describe('buildRegionGlossIndex / regionGlossFor', () => {
  function daysFixture() {
    return [
      {
        date: '2026-06-15',
        eventSummaries: [
          {
            targetType: 'SUNSET',
            regions: [
              { regionName: 'Northumberland', glossHeadline: 'Clear skies inland', glossDetail: 'A calm evening with light cloud burning off by dusk.' },
              { regionName: 'The Lakes', glossHeadline: null, glossDetail: null },
            ],
          },
        ],
      },
    ];
  }

  it('joins on date|targetType|regionName and prefers the detail over the headline', () => {
    const index = buildRegionGlossIndex(daysFixture());
    expect(regionGlossFor(index, '2026-06-15', 'SUNSET', 'Northumberland'))
      .toBe('A calm evening with light cloud burning off by dusk.');
  });

  it('falls back to the headline when detail is blank', () => {
    const days = [{
      date: '2026-06-15',
      eventSummaries: [{
        targetType: 'SUNSET',
        regions: [{ regionName: 'Northumberland', glossHeadline: 'Clear skies inland', glossDetail: null }],
      }],
    }];
    const index = buildRegionGlossIndex(days);
    expect(regionGlossFor(index, '2026-06-15', 'SUNSET', 'Northumberland')).toBe('Clear skies inland');
  });

  it('does not index a region with neither headline nor detail', () => {
    const index = buildRegionGlossIndex(daysFixture());
    expect(regionGlossFor(index, '2026-06-15', 'SUNSET', 'The Lakes')).toBeNull();
  });

  it('returns null for a window the index was never built with', () => {
    const index = buildRegionGlossIndex(daysFixture());
    expect(regionGlossFor(index, '2026-06-16', 'SUNSET', 'Northumberland')).toBeNull();
    expect(regionGlossFor(index, '2026-06-15', 'SUNRISE', 'Northumberland')).toBeNull();
  });

  it('returns null with no region name, no index, or an unbuilt days array', () => {
    const index = buildRegionGlossIndex(daysFixture());
    expect(regionGlossFor(index, '2026-06-15', 'SUNSET', null)).toBeNull();
    expect(regionGlossFor(null, '2026-06-15', 'SUNSET', 'Northumberland')).toBeNull();
    expect(buildRegionGlossIndex(undefined).size).toBe(0);
    expect(buildRegionGlossIndex([{ date: null }]).size).toBe(0);
  });
});
