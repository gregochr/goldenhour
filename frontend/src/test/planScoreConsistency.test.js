import { describe, it, expect } from 'vitest';
import { buildBriefingScoreIndex } from '../utils/briefingScoreIndex.js';
import { resolveSpotPeek } from '../utils/windowSpotPeek.js';
import { buildScoreIndex, buildLocationSheet } from '../utils/locationSheet.js';

/**
 * The location-sheet superset plan's own consistency invariant (§3.5): one served row, reduced two
 * different ways for two different surfaces, must not disagree.
 *
 * <p>Contracts §3's corrected claim: production has already shown "Worth it · best 4★" over a grid
 * of "Poor" from exactly this class of bug — two panels deriving from the same snapshot through two
 * different reductions, silently drifting apart. Here the two reductions are the provider's
 * name/region-keyed `scoreIndex` (`buildBriefingScoreIndex` → `resolveSpotPeek`, feeding the window
 * popup's peek) and the sheet's own id-first `buildScoreIndex` (→ `buildLocationSheet`, feeding the
 * location sheet). Both are fed from the SAME `GET /api/briefing/evaluate/scores` response — one
 * `LocationEvaluationView` row here — and this test proves the two reductions of it agree on the two
 * new fields Phase 1 adds.
 */

const VIEW = {
  locationId: 7,
  locationName: 'Bamburgh',
  regionName: 'Northumberland',
  date: '2026-08-14',
  targetType: 'SUNSET',
  rating: 4,
  summary: 'A clean burn on the horizon.',
  fierySkyPotential: 73,
  goldenHourPotential: 66,
};

/** Builds both indexes from one raw view row, and both surfaces' derived fiery/golden values. */
function reduceBothWays(view) {
  const briefingScores = new Map([[
    `${view.regionName}|${view.date}|${view.targetType}|${view.locationName}`,
    {
      locationName: view.locationName,
      rating: view.rating,
      fierySkyPotential: view.fierySkyPotential,
      goldenHourPotential: view.goldenHourPotential,
      summary: view.summary,
      triageReason: null,
      triageMessage: null,
    },
  ]]);
  const peek = resolveSpotPeek(
    { locationName: view.locationName, rating: view.rating, driveMinutes: 40, solarEventTime: '2026-08-14T19:41:00' },
    view.date, view.targetType, buildBriefingScoreIndex(briefingScores),
  );
  const sheet = buildLocationSheet(
    { id: view.locationId, name: view.locationName, regionName: view.regionName },
    [{
      key: `${view.date}:${view.targetType}`, date: view.date, targetType: view.targetType,
      dow: 'Fri', sunrise: false, label: 'Tonight Sunset', time: '20:37', away: false,
    }],
    { scoreIndex: buildScoreIndex([view]), scoresKnown: true, todayStr: '2026-08-14' },
  );
  return { peek, sheetRow: sheet.rows[0] };
}

describe('the peek and the sheet report identical fiery/golden values for one served row', () => {
  it('agrees on an ordinary in-range row', () => {
    const { peek, sheetRow } = reduceBothWays(VIEW);
    expect(peek.fierySky).toBe(VIEW.fierySkyPotential);
    expect(peek.goldenHour).toBe(VIEW.goldenHourPotential);
    expect(sheetRow.fierySky).toBe(peek.fierySky);
    expect(sheetRow.goldenHour).toBe(peek.goldenHour);
  });

  /**
   * ⚠️ The case an adversarial review of this test's first cut caught missing. Feeding only
   * in-range values (73/66) meant the two reductions could never actually disagree — an
   * out-of-range row is exactly the shape production would send if the pipeline ever emitted a
   * malformed score, and it is the one input where `resolveSpotPeek` and `buildScoreIndex` used to
   * differ: the sheet's `boundedScore` discarded it while the peek passed it straight through,
   * clamped, still rendering a bar. Both now apply the same 0–100-integer bound (Phase 1), so this
   * asserts silence on BOTH surfaces rather than a bar on one and nothing on the other.
   */
  it('⚠️ agrees on a malformed row too — silence on both, never a bar on one and not the other', () => {
    const malformed = { ...VIEW, fierySkyPotential: 101, goldenHourPotential: -1 };
    const { peek, sheetRow } = reduceBothWays(malformed);
    expect(peek.fierySky).toBeNull();
    expect(peek.goldenHour).toBeNull();
    expect(sheetRow.fierySky).toBeNull();
    expect(sheetRow.goldenHour).toBeNull();
  });
});
