import { describe, it, expect } from 'vitest';
import { buildWindowCards, VERDICT_LABEL } from '../utils/windowFirstCards.js';
import { buildHeatStripCards } from '../utils/windowFirstStrip.js';
import { buildSearchGroups } from '../utils/planSearch.js';
import { verdictScopeSpoken, verdictScopeText } from '../utils/reachLens.js';

/**
 * The reach-scoped Plan headline on the card builder (reach-scoped-card-plan R2 steps 8 and 10).
 *
 * <p><b>What breaks if these fail:</b> a tier tap leaves the headline word, its mean, the pick or the haze
 * describing the whole roster while the Spread row beside them counts only what is within reach (the defect this
 * series exists to end), or, the other way, a card with no scoped answer to hand stops being exactly what it was
 * and the Any tier quietly changes.
 */

const TODAY = '2026-08-04';
const TOMORROW = '2026-08-05';
const REGION = 'Northumberland';

const SLOTS = [
  { locationId: 1, locationName: 'Nearby Wood', claudeRating: null, canopy: false },
  { locationId: 2, locationName: 'Far Beach', claudeRating: 4, canopy: false },
];
const REACH = new Map([
  [1, { driveMinutes: 20, distanceMiles: 8, driveStatus: 'MEASURED' }],
  [2, { driveMinutes: 70, distanceMiles: 50, driveStatus: 'MEASURED' }],
]);
const SERVED_PICK = {
  kind: 'BEST', regionName: REGION, headline: 'Fiery', detail: 'Open sky', locationName: 'Far Beach', locationId: 2,
};

function days({ pick = SERVED_PICK } = {}) {
  return [{
    date: TODAY,
    eventSummaries: [{
      targetType: 'SUNSET',
      regions: [{
        regionName: REGION,
        displayVerdict: 'WORTH_IT',
        meanRating: 3.9,
        meanRatingDelta: 0.5,
        sampleSufficient: true,
        confidence: 'high',
        slots: SLOTS,
      }],
      unregioned: [],
      window: {
        verdict: 'WORTH_IT', confidence: 'high', badges: [], pick, eventTime: `${TODAY}T20:11:00`,
      },
    }],
  }];
}

/** A tier's entry for the one window, with the figures overridable. */
function entry(overrides = {}) {
  return {
    date: TODAY,
    targetType: 'SUNSET',
    displayVerdict: 'STAND_DOWN',
    meanRating: 2.0,
    topMeanRating: 2.0,
    confidence: 'medium',
    blanked: false,
    thinCount: null,
    topRegionName: 'Lakes',
    pick: null,
    pickRegion: null,
    regions: [],
    ...overrides,
  };
}

const TIERS = {
  45: [entry()],
  90: [entry({
    displayVerdict: 'WORTH_IT', meanRating: 4.1, topMeanRating: 4.1, confidence: 'high',
    pick: { ...SERVED_PICK, headline: 'In reach', averageRating: 4.1 },
  })],
  150: [entry({ displayVerdict: 'MAYBE', meanRating: 3.0, topMeanRating: 3.0, thinCount: 3 })],
};

const EV = [{ date: TODAY, targetType: 'SUNSET' }];

function cardWith(lens, { picksLocked = false, pick } = {}) {
  return buildWindowCards(EV, days({ pick }), TODAY, TOMORROW, new Set(), REACH, {
    limitMinutes: 45, defaultLimitMinutes: 45, reachMeasured: true, ...lens,
  }, picksLocked)[0];
}

describe('buildWindowCards — the reach-scoped headline', () => {
  it('swaps the word, its label, the mean, the haze, the blanked note, the thin count and the pick at the active tier', () => {
    const card = cardWith({ reachVerdicts: TIERS });

    expect(card.verdict).toBe('STAND_DOWN');
    expect(card.verdictLabel).toBe('Poor');
    expect(card.verdictMean).toBe(2.0);
    expect(card.topMeanRating).toBe(2.0);
    // STAND_DOWN carries no haze: the served tier is not borrowed.
    expect(card.confidence).toBeNull();
    expect(card.verdictBlanked).toBe(false);
    expect(card.pick).toBeNull();
    expect(card.verdictScope).toEqual({ tierMinutes: 45 });
  });

  it('keeps the served figures beside the scoped ones, for H2 and for the Map', () => {
    const card = cardWith({ reachVerdicts: TIERS });

    expect(card.regionVerdict).toBe('WORTH_IT');
    expect(card.regionVerdictMean).toBe(3.9);
    expect(card.regionConfidence).toBe('high');
    expect(card.regionPick).toMatchObject({ kind: 'best', regionName: REGION, locationName: 'Far Beach' });
    // The served top region rides beside the word's own, for H2 to name.
    expect(card.regionHotRegionName).toBe(REGION);
  });

  it('points the thumbnail highlight at the in-reach top region the word is about, and drops the served movement', () => {
    const plain = cardWith({});
    const card = cardWith({ reachVerdicts: TIERS });

    // Unscoped: the served top region and its delta, exactly as before.
    expect(plain.hotRegionName).toBe(REGION);
    expect(plain.movement).toEqual({ regionName: REGION, delta: 0.5 });
    // Scoped: the server's in-reach region, and no movement (the served one names a region the word is not about).
    expect(card.hotRegionName).toBe('Lakes');
    expect(card.movement).toBeNull();
    expect(cardWith({ reachVerdicts: { 45: [entry({ topRegionName: null })] } }).hotRegionName).toBeNull();
  });

  it('is a LOOKUP: the label is the served word\'s, whatever the number, and an unknown word is Awaiting', () => {
    const maybe = cardWith({ limitMinutes: 150, reachVerdicts: TIERS });
    expect(maybe.verdict).toBe('MAYBE');
    expect(maybe.verdictLabel).toBe(VERDICT_LABEL.MAYBE);
    expect(maybe.scopedThinCount).toBe(3);

    const unknown = cardWith({ reachVerdicts: { 45: [entry({ displayVerdict: 'SOMETHING_NEW' })] } });
    expect(unknown.verdict).toBe('SOMETHING_NEW');
    expect(unknown.verdictLabel).toBe(VERDICT_LABEL.AWAITING);
    const missing = cardWith({ reachVerdicts: { 45: [entry({ displayVerdict: null })] } });
    expect(missing.verdict).toBe('AWAITING');
  });

  it('a tier change is a lookup in what is held: each tier wears its own entry, with its own scope', () => {
    const at45 = cardWith({ limitMinutes: 45, reachVerdicts: TIERS });
    const at90 = cardWith({ limitMinutes: 90, reachVerdicts: TIERS });
    const at150 = cardWith({ limitMinutes: 150, reachVerdicts: TIERS });

    expect([at45.verdict, at90.verdict, at150.verdict]).toEqual(['STAND_DOWN', 'WORTH_IT', 'MAYBE']);
    expect([at45.verdictScope.tierMinutes, at90.verdictScope.tierMinutes, at150.verdictScope.tierMinutes])
      .toEqual([45, 90, 150]);
    expect(at90.confidence).toBe('high');
    expect(at90.pick).toMatchObject({ kind: 'best', headline: 'In reach', locationId: 2 });
  });

  it('gives a card with no entry to wear exactly what it was: Any, a window the tier lacks, an unmeasured reader', () => {
    const baseline = cardWith({});
    const any = cardWith({ limitMinutes: null, reachVerdicts: TIERS });
    const noWindow = cardWith({ reachVerdicts: { 45: [entry({ date: TOMORROW })] } });
    const noTier = cardWith({ limitMinutes: 60, reachVerdicts: TIERS });
    const unmeasured = cardWith({ reachMeasured: false, reachVerdicts: TIERS });

    expect(baseline.verdict).toBe('WORTH_IT');
    expect(baseline).not.toHaveProperty('verdictScope');
    // Under Any the whole card is what it would be with no answer held.
    expect(any).toEqual(cardWith({ limitMinutes: null }));
    expect(noWindow).toEqual(baseline);
    expect(noTier).toEqual(cardWith({ limitMinutes: 60 }));
    expect(unmeasured).toEqual(cardWith({ reachMeasured: false }));
    expect(cardWith({ reachVerdicts: null })).toEqual(baseline);
  });

  it('withholds the scoped pick from a reader without best bets, as it withholds the served one', () => {
    expect(cardWith({ limitMinutes: 90, reachVerdicts: TIERS }, { picksLocked: true }).pick).toBeNull();
  });

  it('prints a blanked qualifier the server reports, and a thin count only from the server', () => {
    const blanked = cardWith({ reachVerdicts: { 45: [entry({ blanked: true, meanRating: null })] } });
    expect(blanked.verdictBlanked).toBe(true);
    expect(blanked.verdictMean).toBeNull();
    expect(blanked.scopedThinCount).toBeNull();
  });

  it('keeps the haze for a word that carries one, from the scoped entry and not the served tier', () => {
    const card = cardWith({
      reachVerdicts: { 45: [entry({ displayVerdict: 'UNRATED', meanRating: 2.0, confidence: 'low' })] },
    });

    expect(card.verdict).toBe('UNRATED');
    expect(card.confidence).toBe('low');
    expect(card.regionConfidence).toBe('high');
  });

  it('an away card wears the origin region\'s scoped word, and the server\'s already-matched pick', () => {
    const origin = { id: 7, name: REGION, baseName: 'Alnwick' };
    const away = buildWindowCards(EV, days(), TODAY, TOMORROW, new Set(), REACH, {
      limitMinutes: 45, defaultLimitMinutes: 45, reachMeasured: true, origin,
      reachVerdicts: { 45: [entry({ displayVerdict: 'MAYBE', meanRating: 3.1, pick: SERVED_PICK })] },
    })[0];

    expect(away.verdict).toBe('MAYBE');
    expect(away.verdictMean).toBe(3.1);
    expect(away.pick).toMatchObject({ kind: 'best', regionName: REGION });
    expect(away.verdictScope).toEqual({ tierMinutes: 45 });
    expect(away.regionVerdict).toBe('WORTH_IT');
  });

  it('an Also with no surviving Best is still dropped when the scoped picks are all Also', () => {
    const card = cardWith({
      limitMinutes: 90,
      reachVerdicts: { 90: [entry({ displayVerdict: 'WORTH_IT', pick: { ...SERVED_PICK, kind: 'ALSO' } })] },
    });

    expect(card.pick).toBeNull();
  });
});

describe('the scope fold and the surfaces that print it', () => {
  const EVENTS = [{ date: TODAY, targetType: 'SUNSET' }];

  function fold(lens) {
    const cards = buildWindowCards(EVENTS, days(), TODAY, TOMORROW, new Set(), REACH, {
      limitMinutes: 45, defaultLimitMinutes: 45, reachMeasured: true, ...lens,
    });
    return buildHeatStripCards(EVENTS, cards, new Set(), days(), TODAY, TOMORROW)[0];
  }

  it('folds the scope and the served word and mean for H2', () => {
    const strip = fold({ reachVerdicts: TIERS });

    expect(strip.verdict).toBe('STAND_DOWN');
    expect(strip.verdictLabel).toBe('Poor');
    expect(strip.verdictScope).toEqual({ tierMinutes: 45 });
    expect(strip.regionVerdict).toBe('WORTH_IT');
    expect(strip.regionVerdictMean).toBe(3.9);
    expect(strip.regionHotRegionName).toBe(REGION);
    expect(strip.hotRegionName).toBe('Lakes');
    expect(strip.scopedThinCount).toBeNull();
    // The strip's own pick fields are the scoped ones, and the Map reads them (R3).
    expect(strip.pickKind).toBeNull();
  });

  it('adds none of it to a card with no scoped headline', () => {
    const strip = fold({});

    for (const key of ['verdictScope', 'regionVerdict', 'regionVerdictMean', 'scopedThinCount']) {
      expect(strip).not.toHaveProperty(key);
    }
    expect(strip.verdict).toBe('WORTH_IT');
  });

  it('spells the scope in the Spread row\'s compact form, and says nothing without one', () => {
    expect(verdictScopeText({ tierMinutes: 45 })).toBe('within 45m');
    expect(verdictScopeText({ tierMinutes: 90 })).toBe('within 1h30');
    expect(verdictScopeText({ tierMinutes: 150 })).toBe('within 2h30');
    expect(verdictScopeText({ tierMinutes: 60 })).toBe('within 1h');
    expect(verdictScopeText(undefined)).toBeNull();
    expect(verdictScopeText(null)).toBeNull();
    expect(verdictScopeText({ tierMinutes: null })).toBeNull();
    // Spoken long, as the Spread qualifier's own `spoken` does.
    expect(verdictScopeSpoken({ tierMinutes: 45 })).toBe('within 45 min');
    expect(verdictScopeSpoken({ tierMinutes: 90 })).toBe('within 1h 30min');
    expect(verdictScopeSpoken({ tierMinutes: 150 })).toBe('within 2h 30min');
    expect(verdictScopeSpoken(null)).toBeNull();
    expect(verdictScopeSpoken({ tierMinutes: null })).toBeNull();
  });

  it('search rows print the scoped word with its scope, and an unscoped one exactly as before', () => {
    const scoped = fold({ reachVerdicts: TIERS });
    const plain = fold({});
    const rowOf = (window) => buildSearchGroups('', { windows: [window], regions: [], locations: [] })
      .find((group) => group.id === 'windows').rows[0];

    expect(scoped.time).not.toBe('');
    expect(rowOf(scoped).sub).toBe(`${scoped.time} · Poor · within 45m`);
    expect(rowOf(scoped).subParts.map((part) => part.text)).toEqual([scoped.time, 'Poor', 'within 45m']);
    expect(rowOf(plain).sub).toBe(`${plain.time} · Worth it`);
    expect(rowOf(plain).subParts).toHaveLength(2);
  });
});
