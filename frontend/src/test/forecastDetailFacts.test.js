/**
 * `utils/forecastDetailFacts.js` — the popup's detail facts, moved for the location sheet
 * (overlay convergence C2).
 *
 * What breaks if these fail: the sheet shows a badge the popup never showed (or drops one it did),
 * a LITE reader sees a PRO-only fact, or a thresholded fact (dust, rising tide) drifts from the
 * popup's own boundary values.
 */
import { describe, it, expect } from 'vitest';
import {
  comfortRows, detailBadges, generatedFooter, isDustEnhanced, mistBadgeOf, risingTideOf,
} from '../utils/forecastDetailFacts.js';

const PRO = { eventType: 'SUNSET', isPro: true };
const LITE = { eventType: 'SUNSET', isPro: false };
const keys = (badges) => badges.map((b) => b.key);

describe('isDustEnhanced — the popup\'s own boundary values', () => {
  it('is false for nothing, for AOD at exactly 0.3 and for dust at exactly 50', () => {
    expect(isDustEnhanced(null)).toBe(false);
    expect(isDustEnhanced({})).toBe(false);
    expect(isDustEnhanced({ aerosolOpticalDepth: 0.3 })).toBe(false);
    expect(isDustEnhanced({ dust: 50 })).toBe(false);
  });

  it('is true above either threshold while PM2.5 is unknown or under 35', () => {
    expect(isDustEnhanced({ aerosolOpticalDepth: 0.31 })).toBe(true);
    expect(isDustEnhanced({ dust: 51, pm25: 34.9 })).toBe(true);
  });

  it('is false when PM2.5 says smoke (35 and over)', () => {
    expect(isDustEnhanced({ aerosolOpticalDepth: 0.9, pm25: 35 })).toBe(false);
  });
});

describe('risingTideOf — a high tide within 90 minutes of the event', () => {
  const at = (high) => ({ solarEventTime: '2026-08-14T19:41:00', nextHighTideTime: high });

  it('applies at both edges of the window and nowhere past them', () => {
    expect(risingTideOf(at('2026-08-14T21:11:00'))).toEqual({
      minutesAway: 90, highTideTime: '2026-08-14T21:11:00',
    });
    expect(risingTideOf(at('2026-08-14T18:11:00'))?.minutesAway).toBe(-90);
    expect(risingTideOf(at('2026-08-14T21:12:00'))).toBeNull();
    expect(risingTideOf(at('2026-08-14T18:10:00'))).toBeNull();
  });

  it('is null when either instant is missing', () => {
    expect(risingTideOf(null)).toBeNull();
    expect(risingTideOf({ solarEventTime: '2026-08-14T19:41:00' })).toBeNull();
    expect(risingTideOf({ nextHighTideTime: '2026-08-14T19:41:00' })).toBeNull();
  });
});

describe('detailBadges', () => {
  it('is empty without a detail row', () => {
    expect(detailBadges(null, PRO)).toEqual([]);
    expect(detailBadges({}, PRO)).toEqual([]);
  });

  it('words dust for a PRO reader and plainly for a LITE one', () => {
    const detail = { aerosolOpticalDepth: 0.5 };
    expect(detailBadges(detail, PRO)[0].text).toBe(
      'Elevated dust (e.g. Saharan dust) — expect unusually vivid warm tones',
    );
    expect(detailBadges(detail, LITE)[0].text).toBe('High aerosols');
  });

  it('shows the cloud inversion to PRO only, and never for NONE', () => {
    expect(detailBadges({ inversionPotential: 'STRONG' }, PRO)[0]).toMatchObject({
      key: 'inversion', text: 'Strong cloud inversion — dramatic sea of clouds likely',
    });
    expect(detailBadges({ inversionPotential: 'MODERATE' }, PRO)[0].text)
      .toBe('Moderate cloud inversion — cloud blanket below viewpoint possible');
    expect(detailBadges({ inversionPotential: 'NONE' }, PRO)).toEqual([]);
    expect(detailBadges({ inversionPotential: 'STRONG' }, LITE)).toEqual([]);
  });

  it('shows Claude\'s valley mist reading to PRO only, for LIKELY and FOGBOUND alone', () => {
    expect(detailBadges({ mistPotential: 'LIKELY' }, PRO)[0]).toMatchObject({
      key: 'mist', tone: 'sea', text: 'Valley mist likely',
    });
    expect(detailBadges({ mistPotential: 'FOGBOUND' }, PRO)[0]).toMatchObject({
      key: 'mist', tone: 'amber', text: 'Fogbound',
    });
    expect(detailBadges({ mistPotential: 'POSSIBLE' }, PRO)).toEqual([]);
    expect(detailBadges({ mistPotential: 'NONE' }, PRO)).toEqual([]);
    expect(detailBadges({ mistPotential: 'SOMETHING' }, PRO)).toEqual([]);
    expect(detailBadges({}, PRO)).toEqual([]);
    expect(detailBadges({ mistPotential: 'LIKELY' }, LITE)).toEqual([]);
  });

  it('mistBadgeOf is null for a missing detail', () => {
    expect(mistBadgeOf(null)).toBeNull();
    expect(mistBadgeOf(undefined)).toBeNull();
  });

  it('shows bluebell from 2, with its summary for PRO and a locked upsell for LITE', () => {
    expect(detailBadges({ bluebellScore: 1 }, PRO)).toEqual([]);
    expect(detailBadges({ bluebellScore: 3, bluebellSummary: 'Carpets in the glen' }, PRO)[0])
      .toMatchObject({ text: 'Bluebell: Carpets in the glen', tone: 'indigo' });
    expect(detailBadges({ bluebellScore: 4 }, PRO)[0])
      .toMatchObject({ text: 'Bluebell: 4/5', tone: 'indigo-strong' });
    expect(detailBadges({ bluebellScore: 4, bluebellSummary: 'Peak' }, LITE)[0]).toMatchObject({
      text: 'Bluebell conditions — Upgrade to Pro', locked: true,
    });
  });

  it('words the rising tide against the row\'s own event', () => {
    const base = { solarEventTime: '2026-08-14T05:00:00' };
    const after = detailBadges({ ...base, nextHighTideTime: '2026-08-14T05:30:00' },
      { eventType: 'SUNRISE', isPro: true });
    expect(after[0].text).toMatch(/^Rising tide — high at .+ \(30 min after sunrise\)$/);
    const before = detailBadges({ ...base, nextHighTideTime: '2026-08-14T04:45:00' }, PRO);
    expect(before[0].text).toMatch(/\(15 min before sunset\)$/);
    const at = detailBadges({ ...base, nextHighTideTime: '2026-08-14T05:00:00' }, PRO);
    expect(at[0].text).toMatch(/\(at sunset\)$/);
  });

  it('shows sea state for everyone, to one decimal', () => {
    const badges = detailBadges({ seaState: 'Moderate', significantWaveHeightMetres: 1.26 }, LITE);
    expect(badges[0]).toMatchObject({ key: 'sea-state', text: 'Seas 1.3 m · Moderate' });
  });

  it('shows surge only for a HIGH or MODERATE risk with a figure, for everyone', () => {
    expect(detailBadges({ surgeTotalMetres: 0.72, surgeRiskLevel: 'HIGH' }, LITE)[0].text)
      .toBe('Surge 0.7 m above normal');
    expect(detailBadges({ surgeTotalMetres: 0.4, surgeRiskLevel: 'MODERATE' }, PRO)).toHaveLength(1);
    expect(detailBadges({ surgeTotalMetres: 0.4, surgeRiskLevel: 'LOW' }, PRO)).toEqual([]);
    expect(detailBadges({ surgeRiskLevel: 'HIGH' }, PRO)).toEqual([]);
  });

  it('keeps the popup\'s order', () => {
    const detail = {
      aerosolOpticalDepth: 0.5,
      inversionPotential: 'STRONG',
      mistPotential: 'LIKELY',
      bluebellScore: 3,
      solarEventTime: '2026-08-14T19:41:00',
      nextHighTideTime: '2026-08-14T19:50:00',
      seaState: 'Slight',
      significantWaveHeightMetres: 0.5,
      surgeTotalMetres: 0.6,
      surgeRiskLevel: 'HIGH',
    };
    expect(keys(detailBadges(detail, PRO))).toEqual([
      'dust', 'inversion', 'mist', 'bluebell', 'rising-tide', 'sea-state', 'surge',
    ]);
  });
});

describe('comfortRows', () => {
  it('is empty without a temperature', () => {
    expect(comfortRows(null)).toEqual([]);
    expect(comfortRows({})).toEqual([]);
  });

  it('reads temperature, wind and rain chance off the slim row', () => {
    const rows = comfortRows({
      temperatureCelsius: 12.4,
      apparentTemperatureCelsius: 9.6,
      windSpeed: 5,
      windDirection: 270,
      precipitationProbabilityPercent: 30,
    });
    expect(rows.map((r) => `${r.strong}${r.rest}`)).toEqual([
      '12°C · feels like 10°C', '11.2 mph W', '30% rain chance',
    ]);
  });

  it('falls feels-like back to the air temperature, rain chance to 0 and adds precipitation when wet', () => {
    const rows = comfortRows({
      temperatureCelsius: 8, windSpeed: 2, precipitation: '1.25',
    });
    expect(rows[0].rest).toBe(' · feels like 8°C');
    expect(rows[1].rest).toBe('');
    expect(rows[2].strong).toBe('0%');
    expect(rows[3]).toMatchObject({ key: 'precip', strong: '1.3 mm' });
    expect(comfortRows({ temperatureCelsius: 8, windSpeed: 2, precipitation: 0 })).toHaveLength(3);
  });
});

describe('tide-light-pro: the rising-tide badge and the tide triage reason', () => {
  const RISING = { nextHighTideTime: '2026-08-14T20:30:00', solarEventTime: '2026-08-14T20:10:00' };
  // ADMIN reaches these functions with the same isPro flag as PRO (Admin includes Pro).
  const PRO_LIKE = [['PRO', PRO], ['ADMIN', { eventType: 'SUNSET', isPro: true }]];

  it.each(PRO_LIKE)('builds the rising-tide badge for %s', (_n, ctx) => {
    expect(keys(detailBadges(RISING, ctx))).toContain('rising-tide');
  });

  it('does not build the rising-tide badge for a Lite reader', () => {
    expect(keys(detailBadges(RISING, LITE))).not.toContain('rising-tide');
  });

  const run = { forecastRunAt: '2026-08-14T05:00:00', triageReason: 'TIDE_MISALIGNED' };

  it.each(PRO_LIKE)('keeps the tide wording in the triage footer for %s', (_n, ctx) => {
    expect(generatedFooter(run, null, false, ctx.isPro))
      .toContain('tide not aligned with location preference');
  });

  it('defaults to the Pro reading when no tier is passed', () => {
    expect(generatedFooter(run, null)).toContain('tide not aligned');
  });

  it('gives a Lite reader the generic not-evaluated line for a tide stand-down', () => {
    const footer = generatedFooter(run, null, false, false);
    expect(footer).toBe('Forecast generated: 14 Aug 2026 06:00 '
      + '(not evaluated by Claude due to regional conditions predicted poor)');
    expect(footer).not.toMatch(/tide/i);
  });

  it('leaves a Lite reader\'s other triage reasons alone', () => {
    expect(generatedFooter({ ...run, triageReason: 'PRECIPITATION' }, null, false, false))
      .toContain('active precipitation forecast at event time');
  });
});

describe('generatedFooter', () => {
  const forecast = { forecastRunAt: '2026-08-14T05:00:00' };

  it('is null without a run time', () => {
    expect(generatedFooter({}, null)).toBeNull();
    expect(generatedFooter(null, null)).toBeNull();
  });

  it('names the model, except for the wildlife placeholder', () => {
    expect(generatedFooter(forecast, null)).toBe('Forecast generated: 14 Aug 2026 06:00');
    expect(generatedFooter(forecast, { evaluationModel: 'HAIKU' }))
      .toBe('Forecast generated: 14 Aug 2026 06:00 by Haiku 4.5');
    expect(generatedFooter(forecast, { evaluationModel: 'WILDLIFE' }))
      .toBe('Forecast generated: 14 Aug 2026 06:00');
  });

  it('says a triaged row was not evaluated by Claude, unless a live rating outranks it', () => {
    const triaged = { ...forecast, triageReason: 'PRECIPITATION' };
    expect(generatedFooter(triaged, { evaluationModel: 'HAIKU' })).toBe(
      'Forecast generated: 14 Aug 2026 06:00 (Haiku 4.5 run, but not evaluated by Claude due to '
      + 'active precipitation forecast at event time)',
    );
    expect(generatedFooter({ ...forecast, triageReason: 'NEW_REASON' }, null))
      .toContain('regional conditions predicted poor');
    expect(generatedFooter(triaged, { evaluationModel: 'HAIKU' }, true))
      .toBe('Forecast generated: 14 Aug 2026 06:00 by Haiku 4.5');
  });
});
