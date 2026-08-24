import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import { spotPeekPlacement, resolveSpotPeek } from '../utils/windowSpotPeek.js';
import { buildBriefingScoreIndex } from '../utils/briefingScoreIndex.js';

/** The panel's own width, mirrored from `PEEK_WIDTH`. Assertions read in these terms, not in px. */
const PEEK_WIDTH = 280;

/** jsdom's viewport is 1024×768 and its `innerWidth`/`innerHeight` are read-only accessors. */
function viewport(width, height) {
  Object.defineProperty(window, 'innerWidth', { configurable: true, writable: true, value: width });
  Object.defineProperty(window, 'innerHeight', { configurable: true, writable: true, value: height });
}

/** A DOMRect-shaped literal — `getBoundingClientRect`'s output is only read for these six fields. */
function rect({ left, top, width, height }) {
  return { left, top, width, height, right: left + width, bottom: top + height };
}

/** A spot card in a strip: ~275px wide, 78px tall, sitting 300px down a 768px-tall viewport. */
const CARD = rect({ left: 400, top: 300, width: 275, height: 78 });

/** The strip wrapper it lives in — the 1080px shell's pane, inset. */
const STRIP = rect({ left: 28, top: 280, width: 1008, height: 120 });

function scoreIndex(entries) {
  return buildBriefingScoreIndex(new Map(entries.map((e) => [
    `${e.regionName ?? 'Northumberland & Tyneside'}|${e.date}|${e.targetType}|${e.locationName}`,
    e,
  ])));
}

const SPOT = {
  key: '1',
  locationId: 1,
  locationName: 'Bamburgh Castle',
  regionName: 'Northumberland & Tyneside',
  rating: 4,
  driveMinutes: 66,
  distanceMiles: 47,
};

describe('spotPeekPlacement', () => {
  beforeEach(() => viewport(1280, 800));
  afterEach(() => {
    delete window.innerWidth;
    delete window.innerHeight;
  });

  it('centres the panel under the card it belongs to', () => {
    const { position } = spotPeekPlacement(CARD, STRIP);
    // The card's centre is 537.5; a 280px panel centred on it starts at 397.5.
    expect(position.left).toBeCloseTo(537.5 - PEEK_WIDTH / 2, 5);
  });

  it('clamps to the strip\'s left edge rather than the viewport\'s', () => {
    // Without the strip bound this would sit at -102, out over the page. The panel belongs to one
    // window card, so it must read as part of it — plan §5a's reason, one level down from the block.
    const first = rect({ left: 38, top: 300, width: 275, height: 78 });
    const { position } = spotPeekPlacement(first, STRIP);
    expect(position.left).toBe(STRIP.left + 10);
  });

  it('clamps to the strip\'s right edge', () => {
    const last = rect({ left: 750, top: 300, width: 275, height: 78 });
    const { position } = spotPeekPlacement(last, STRIP);
    expect(position.left).toBe(STRIP.right - 10 - PEEK_WIDTH);
  });

  it('pins to the left bound when the strip is narrower than the panel', () => {
    // `Math.min(centre - width/2, maxLeft)` would otherwise hand back a bound LEFT of leftBound,
    // putting the panel outside the strip on the side it was being clamped away from.
    const narrow = rect({ left: 100, top: 280, width: 200, height: 120 });
    const card = rect({ left: 110, top: 300, width: 180, height: 78 });
    const { position } = spotPeekPlacement(card, narrow);
    expect(position.left).toBe(narrow.left + 10);
  });

  it('falls back to the viewport when the strip is not mounted', () => {
    const last = rect({ left: 1100, top: 300, width: 275, height: 78 });
    const { position } = spotPeekPlacement(last, null);
    expect(position.left).toBe(1280 - 8 - 10 - PEEK_WIDTH);
  });

  it('sits below the card, one gap down', () => {
    const { placement, position } = spotPeekPlacement(CARD, STRIP);
    expect(placement).toBe('below');
    expect(position.top).toBe(CARD.bottom + 10);
    expect(position.bottom).toBeUndefined();
  });

  it('flips above when below cannot hold the restored score bars', () => {
    // Deliberately inside the 170–220 band the re-derivation created, because that is the only
    // place the change is observable: this card has 190px below it, which `CloseToHome`'s 170 would
    // call room enough and this panel's 220 does not. A card with 130px below flips under either
    // number and would pin nothing.
    const lowCard = rect({ left: 400, top: 514, width: 275, height: 78 });
    expect(800 - 8 - (lowCard.bottom + 10)).toBe(190);
    const { placement, position } = spotPeekPlacement(lowCard, STRIP);
    expect(placement).toBe('above');
    expect(position.bottom).toBe(800 - lowCard.top + 10);
    expect(position.top).toBeUndefined();
  });

  it('does not flip when above is tighter than below', () => {
    // A card near the bottom of a SHORT viewport: neither side fits, so flipping would buy nothing
    // and cost the reader the window card's own header.
    viewport(1280, 320);
    const { placement } = spotPeekPlacement(rect({ left: 400, top: 40, width: 275, height: 78 }), STRIP);
    expect(placement).toBe('below');
  });

  it('points the arrow at the card centre, offset by half its own side', () => {
    const { arrowLeft, position } = spotPeekPlacement(CARD, STRIP);
    expect(arrowLeft).toBeCloseTo((537.5 - position.left) - 11 / 2, 5);
  });

  it('holds the arrow clear of the panel corners when the card is scrolled half out', () => {
    // Reachable, and only on this surface: `.wf-spots` is a scroller, so a card the reader is
    // hovering can extend left of the strip's own edge. Its centre then sits LEFT of the clamped
    // panel, and an unclamped arrow would be drawn outside the shell entirely.
    const halfOut = rect({ left: -100, top: 300, width: 275, height: 78 });
    const { arrowLeft, position } = spotPeekPlacement(halfOut, STRIP);
    expect(position.left).toBe(STRIP.left + 10);
    expect(arrowLeft).toBe(16 - 11 / 2);
  });
});

describe('resolveSpotPeek', () => {
  const DATE = '2026-08-09';
  const EVENT = 'SUNSET';

  it('shows a peek for a slot with both scores and a sentence', () => {
    const detail = resolveSpotPeek(SPOT, DATE, EVENT, scoreIndex([{
      date: DATE,
      targetType: EVENT,
      locationName: 'Bamburgh Castle',
      fierySkyPotential: 68,
      goldenHourPotential: 74,
      summary: 'Mid-level cloud should catch the last light, though the horizon may be hazy.',
    }]));
    expect(detail).toMatchObject({ fierySky: 68, goldenHour: 74 });
    expect(detail.clause).toBe('Mid-level cloud should catch the last light.');
  });

  /**
   * Location-sheet superset plan, Phase 1: `LocationFourDaySheet`'s `buildScoreIndex` discards a
   * fierySky/goldenHour value outside 0–100 or non-integer, and this peek must apply the SAME rule
   * to the same served row — an adversarial review of the plan's own consistency test caught the
   * asymmetry: without this, a malformed row showed a clamped bar here while the sheet, one
   * drill-down step deeper, showed nothing for it.
   */
  it('⚠️ discards a fierySky/goldenHour value outside 0–100, matching the sheet\'s bound', () => {
    for (const bad of [-1, 101, 50.5]) {
      const detail = resolveSpotPeek(SPOT, DATE, EVENT, scoreIndex([{
        date: DATE, targetType: EVENT, locationName: 'Bamburgh Castle',
        fierySkyPotential: bad, goldenHourPotential: bad, summary: 'x',
      }]));
      expect(detail.fierySky).toBeNull();
      expect(detail.goldenHour).toBeNull();
    }
    for (const good of [0, 100]) {
      const detail = resolveSpotPeek(SPOT, DATE, EVENT, scoreIndex([{
        date: DATE, targetType: EVENT, locationName: 'Bamburgh Castle',
        fierySkyPotential: good, goldenHourPotential: good,
      }]));
      expect(detail.fierySky).toBe(good);
      expect(detail.goldenHour).toBe(good);
    }
  });

  it('shows a peek for scores with no sentence, which the v1 rule refused', () => {
    // `CloseToHome` returns early on a missing summary because with the bars removed a peek would
    // restate the card. P10′ puts the bars back, so the premise is gone and the rule is re-decided:
    // the gate is "carries something the card does not", and two scores do.
    const detail = resolveSpotPeek(SPOT, DATE, EVENT, scoreIndex([{
      date: DATE, targetType: EVENT, locationName: 'Bamburgh Castle', fierySkyPotential: 41,
    }]));
    expect(detail).toMatchObject({ fierySky: 41, goldenHour: null, clause: null });
  });

  it('shows a peek for a sentence with no scores', () => {
    const detail = resolveSpotPeek(SPOT, DATE, EVENT, scoreIndex([{
      date: DATE,
      targetType: EVENT,
      locationName: 'Bamburgh Castle',
      summary: 'A thick marine layer is forecast to sit on the coast all evening.',
    }]));
    expect(detail).toMatchObject({ fierySky: null, goldenHour: null });
    expect(detail.clause).toBe('A thick marine layer is forecast to sit on the coast all evening.');
  });

  it('refuses a peek when the slot carries nothing the card does not', () => {
    // Rating and drive are both printed on the card, so a panel holding only those would appear to
    // say something and would not.
    expect(resolveSpotPeek(SPOT, DATE, EVENT, scoreIndex([{
      date: DATE, targetType: EVENT, locationName: 'Bamburgh Castle', rating: 4,
    }]))).toBeNull();
  });

  it('refuses a peek for a triaged slot, which states why the pipeline did not look', () => {
    // A triage message is a fact about the run rather than about the sky, and the strip has no
    // vocabulary for it — the card already says the same thing by omitting its rating badge.
    expect(resolveSpotPeek(SPOT, DATE, EVENT, scoreIndex([{
      date: DATE,
      targetType: EVENT,
      locationName: 'Bamburgh Castle',
      triageReason: 'HEAVY_CLOUD',
      triageMessage: 'Stood down: 96% cloud cover',
    }]))).toBeNull();
  });

  it('refuses a peek when the summary reduces to nothing', () => {
    // The gate has to be the thing RENDERED, not the raw field: a `summary != null` gate would pass
    // here and then draw an empty paragraph.
    expect(resolveSpotPeek(SPOT, DATE, EVENT, scoreIndex([{
      date: DATE, targetType: EVENT, locationName: 'Bamburgh Castle', summary: '   ',
    }]))).toBeNull();
  });

  it('refuses a peek before the scores request has resolved', () => {
    // The normal state of a first paint — the index is a separate request, and the strip must not
    // open an empty panel while it is in flight.
    expect(resolveSpotPeek(SPOT, DATE, EVENT, new Map())).toBeNull();
    expect(resolveSpotPeek(SPOT, DATE, EVENT, null)).toBeNull();
  });

  it('refuses a peek for a slot the index has no entry for', () => {
    expect(resolveSpotPeek(SPOT, DATE, EVENT, scoreIndex([{
      date: DATE, targetType: EVENT, locationName: 'Dunstanburgh', fierySkyPotential: 70,
    }]))).toBeNull();
  });

  it('takes the rating and the drive from the CARD, never from the index', () => {
    // A peek that disagreed with the card it hangs off is the exact failure the single-source
    // contract exists to prevent — and `buildWindowSpots` has already discarded an out-of-range
    // rating that the index still carries.
    const detail = resolveSpotPeek(
      { ...SPOT, rating: 4, driveMinutes: 66 },
      DATE,
      EVENT,
      scoreIndex([{
        date: DATE,
        targetType: EVENT,
        locationName: 'Bamburgh Castle',
        rating: 9,
        fierySkyPotential: 68,
      }]),
    );
    expect(detail.rating).toBe(4);
    expect(detail.driveMinutes).toBe(66);
  });

  it('carries nulls for an unrated spot with no drive time', () => {
    const detail = resolveSpotPeek(
      { ...SPOT, rating: null, driveMinutes: null },
      DATE,
      EVENT,
      scoreIndex([{
        date: DATE, targetType: EVENT, locationName: 'Bamburgh Castle', goldenHourPotential: 55,
      }]),
    );
    expect(detail).toMatchObject({ rating: null, driveMinutes: null, goldenHour: 55 });
  });

  it('carries the leave-by time, computed from the spot rather than from the index', () => {
    // The index has no event time at all, so this could only come from the descriptor — which is
    // also what the card reads, so the two cannot print different times. 21:11 BST − 1h6 − 20 min.
    const detail = resolveSpotPeek(
      { ...SPOT, solarEventTime: '2026-08-09T20:11:00', driveMinutes: 66 },
      DATE,
      EVENT,
      scoreIndex([{
        date: DATE, targetType: EVENT, locationName: 'Bamburgh Castle', fierySkyPotential: 68,
      }]),
    );
    expect(detail.leaveBy).toBe('19:45');
  });

  it('refuses a peek whose only new fact would be the leave-by time', () => {
    // Leave-by is not a fourth key to the gate: it is on the card the pointer is resting on. The
    // slot below has an event time and a drive but nothing the card lacks, so it opens nothing.
    expect(resolveSpotPeek(
      { ...SPOT, solarEventTime: '2026-08-09T20:11:00', driveMinutes: 66 },
      DATE,
      EVENT,
      scoreIndex([{ date: DATE, targetType: EVENT, locationName: 'Bamburgh Castle', rating: 4 }]),
    )).toBeNull();
  });

  it('carries a null leave-by when the spot has no event time, never a guess', () => {
    const detail = resolveSpotPeek(SPOT, DATE, EVENT, scoreIndex([{
      date: DATE, targetType: EVENT, locationName: 'Bamburgh Castle', fierySkyPotential: 68,
    }]));
    expect(detail.leaveBy).toBeNull();
  });

  it('keys on the window, so the same location on another evening is a different slot', () => {
    const index = scoreIndex([{
      date: DATE, targetType: 'SUNRISE', locationName: 'Bamburgh Castle', fierySkyPotential: 68,
    }]);
    expect(resolveSpotPeek(SPOT, DATE, 'SUNSET', index)).toBeNull();
    expect(resolveSpotPeek(SPOT, DATE, 'SUNRISE', index)).not.toBeNull();
  });
});
