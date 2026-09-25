import { describe, it, expect } from 'vitest';
import { raceModel, raceSentence, formatRaceTime } from '../utils/dawnRace.js';

/**
 * The dawn/dusk race's pure geometry (`lunar-eclipse-plan.md` §2.7, §3 L4).
 *
 * <h2>⚠️ `raceSentence`'s clock assertions are LITERAL strings, not the formatter under test</h2>
 *
 * <p>An earlier draft of this file compared {@code raceSentence}'s output against
 * {@code formatTime(sight.umbraStart)} — the SAME (wrong) conversion the bug this file now guards
 * against was making, so the fixture and the assertion were both wrong the same way and the test
 * passed regardless (`dawnRace.js`'s own class doc has the full account: {@code EclipseSight}
 * instants are served ALREADY Europe/London-local, and {@code formatTime} applies that conversion
 * a SECOND time). The `raceSentence` tests below assert LITERAL `HH:mm` strings copied straight off
 * each fixture's own digits instead — the one honest oracle, since {@link formatRaceTime} performs
 * no zone conversion at all (it reads the served digits back losslessly), so a fixture's digits ARE
 * its own expected display string.
 *
 * <h2>Every geometry expectation is computed here, not copied off the design bundle's own numbers</h2>
 *
 * <p>Fixtures below pick clean, arbitrary times and every non-clock expectation is DERIVED from them
 * with the same arithmetic
 * `raceModel` itself uses, so a fixture can never accidentally satisfy its own assertion.
 */

const HOUR_MS = 60 * 60 * 1000;
const MIN_MS = 60 * 1000;

function ms(iso) {
  return new Date(`${iso}Z`).getTime();
}

/** A DAWN sight that sets in shadow — the plan's own worked shape (28 Aug 2026-like). */
function dawnSight(overrides = {}) {
  return {
    race: 'DAWN',
    moonAltAtMax: 7,
    moonAzCardinal: 'WSW',
    maximum: '2026-08-28T05:13:00',
    umbraStart: '2026-08-28T03:34:00',
    umbraEnd: '2026-08-28T06:52:00',
    moonset: '2026-08-28T06:16:00',
    moonrise: null,
    setsInShadow: true,
    risesInShadow: false,
    stops: [
      { key: 'NAUTICAL_DAWN', time: '2026-08-28T03:09:00' },
      { key: 'CIVIL_DAWN', time: '2026-08-28T05:24:00' },
      { key: 'SUNRISE', time: '2026-08-28T06:11:00' },
      { key: 'GOLDEN_MORNING_END', time: '2026-08-28T06:41:00' },
    ],
    ...overrides,
  };
}

/** A DUSK sight that rises in shadow — the mirror shape (a 2028-12-31-like evening eclipse). */
function duskSight(overrides = {}) {
  return {
    race: 'DUSK',
    moonAltAtMax: 5,
    moonAzCardinal: 'ENE',
    maximum: '2028-12-31T17:42:00',
    umbraStart: '2028-12-31T15:07:00',
    umbraEnd: '2028-12-31T19:52:00',
    moonset: null,
    moonrise: '2028-12-31T15:53:00',
    setsInShadow: false,
    risesInShadow: true,
    stops: [
      { key: 'GOLDEN_EVENING_START', time: '2028-12-31T15:35:00' },
      { key: 'SUNSET', time: '2028-12-31T16:00:00' },
      { key: 'CIVIL_DUSK', time: '2028-12-31T16:38:00' },
      { key: 'NAUTICAL_DUSK', time: '2028-12-31T17:16:00' },
    ],
    ...overrides,
  };
}

describe('raceModel — gate', () => {
  it('returns null with no sight at all', () => {
    expect(raceModel(null)).toBeNull();
  });

  it('returns null when the sight carries no race', () => {
    expect(raceModel(dawnSight({ race: null }))).toBeNull();
  });

  it('returns null on a stop list of the wrong length', () => {
    expect(raceModel(dawnSight({ stops: dawnSight().stops.slice(0, 2) }))).toBeNull();
  });

  it('returns null when the stop keys are not the DAWN set in DAWN order', () => {
    const wrongOrder = [...dawnSight().stops].reverse();
    expect(raceModel(dawnSight({ stops: wrongOrder }))).toBeNull();
  });

  it('returns null when a required instant is unparseable', () => {
    expect(raceModel(dawnSight({ maximum: 'not-a-date' }))).toBeNull();
  });
});

describe('raceModel — DAWN track boundaries', () => {
  const model = raceModel(dawnSight());

  it('floors the track start to the hour of min(umbraStart, first stop)', () => {
    // umbraStart 03:34 vs the first served stop (NAUTICAL_DAWN) 03:09 — the earlier one floors.
    expect(model.trackStart).toBe(ms('2026-08-28T03:00:00'));
  });

  it('clips the track end to max(clipped umbraEnd, the race stop) + 15 min', () => {
    // setsInShadow, so the umbra clips at moonset (06:16), which beats SUNRISE (06:11).
    expect(model.trackEnd).toBe(ms('2026-08-28T06:16:00') + 15 * MIN_MS);
  });

  it('draws the umbra band from umbraStart to the horizon-clipped end', () => {
    expect(model.umbra).toEqual({ from: ms('2026-08-28T03:34:00'), to: ms('2026-08-28T06:16:00') });
  });

  it('draws the hatch from moonset to the track end, only because it sets in shadow', () => {
    expect(model.hatch).toEqual({ from: ms('2026-08-28T06:16:00'), to: model.trackEnd });
  });

  it('positions the two ends of the track at 0 and 1', () => {
    expect(model.position(model.trackStart)).toBe(0);
    expect(model.position(model.trackEnd)).toBe(1);
  });

  it('clamps a time before the track start to 0', () => {
    expect(model.position(model.trackStart - HOUR_MS)).toBe(0);
  });

  it('clamps a time after the track end to 1 — the true umbraEnd (06:52) is beyond it', () => {
    expect(model.position(ms('2026-08-28T06:52:00'))).toBe(1);
  });

  it('returns null for a null instant', () => {
    expect(model.position(null)).toBeNull();
  });

  it('keeps positions monotone and within [0,1] across the whole span', () => {
    const times = [
      model.trackStart, ms('2026-08-28T03:34:00'), ms('2026-08-28T05:13:00'),
      ms('2026-08-28T06:11:00'), ms('2026-08-28T06:16:00'), model.trackEnd,
    ];
    let prev = -1;
    for (const t of times) {
      const p = model.position(t);
      expect(p).toBeGreaterThanOrEqual(0);
      expect(p).toBeLessThanOrEqual(1);
      expect(p).toBeGreaterThanOrEqual(prev);
      prev = p;
    }
  });
});

describe('raceModel — hatch only fires when the sight sets in shadow', () => {
  it('draws no hatch when setsInShadow is false, even with a moonset served', () => {
    const model = raceModel(dawnSight({ setsInShadow: false }));
    expect(model.hatch).toBeNull();
  });

  it('draws no hatch when there is no moonset at all', () => {
    const model = raceModel(dawnSight({ moonset: null, setsInShadow: true }));
    expect(model.hatch).toBeNull();
  });

  it('⚠️ never lets the hatch claim shadow past the eclipse’s own true end (adversarial-review find)', () => {
    // Without the clamp, `trackEnd`'s own +15 min pad can land PAST the served `umbraEnd` when
    // moonset, the race stop and umbraEnd all sit close together — the hatch would then say the
    // moon is still below the horizon, in shadow, for minutes after the eclipse actually ended.
    // Concrete counterexample: umbraEnd 06:10, moonset 06:05 (setsInShadow), SUNRISE(stop 2) 06:00
    // — trackEnd = max(min(06:10,06:05), 06:00) + 15 = 06:20, ten minutes past the true 06:10 end.
    const model = raceModel(dawnSight({
      umbraStart: '2026-08-28T03:00:00',
      umbraEnd: '2026-08-28T06:10:00',
      moonset: '2026-08-28T06:05:00',
      setsInShadow: true,
      stops: [
        { key: 'NAUTICAL_DAWN', time: '2026-08-28T04:30:00' },
        { key: 'CIVIL_DAWN', time: '2026-08-28T05:30:00' },
        { key: 'SUNRISE', time: '2026-08-28T06:00:00' },
        { key: 'GOLDEN_MORNING_END', time: '2026-08-28T06:45:00' },
      ],
    }));
    expect(model.trackEnd).toBe(ms('2026-08-28T06:20:00')); // the pad DOES overshoot the true end
    expect(model.hatch.to).toBe(ms('2026-08-28T06:10:00')); // but the hatch is clamped to it
  });
});

describe('raceModel — DUSK mirrors the DAWN shape', () => {
  const model = raceModel(duskSight());

  it('ceils the track end to the hour of max(umbraEnd, the last stop)', () => {
    expect(model.trackEnd).toBe(ms('2028-12-31T20:00:00'));
  });

  it('starts the track 15 min before min(horizon-clipped umbraStart, the race stop)', () => {
    // risesInShadow, so umbraStart clips forward to moonrise (15:53), which beats SUNSET (16:00).
    expect(model.trackStart).toBe(ms('2028-12-31T15:53:00') - 15 * MIN_MS);
  });

  it('draws the umbra band from the horizon-clipped start to umbraEnd', () => {
    expect(model.umbra).toEqual({ from: ms('2028-12-31T15:53:00'), to: ms('2028-12-31T19:52:00') });
  });

  it('draws the hatch from the track start to moonrise, only because it rises in shadow', () => {
    expect(model.hatch).toEqual({ from: model.trackStart, to: ms('2028-12-31T15:53:00') });
  });

  it('draws no hatch when risesInShadow is false', () => {
    expect(raceModel(duskSight({ risesInShadow: false })).hatch).toBeNull();
  });

  it('⚠️ never lets the hatch claim shadow before the eclipse’s own true start (the DUSK mirror)', () => {
    // Mirror of the DAWN counterexample above: trackStart's own -15 min pad can land BEFORE the
    // served umbraStart when umbraStart, moonrise and the race stop all sit close together.
    // umbraStart 15:50, moonrise 15:53 (risesInShadow), SUNSET(stop 1) 16:00 — trackStart =
    // min(max(15:50,15:53), 16:00) - 15 = 15:38, twelve minutes before the true 15:50 start.
    const model = raceModel(duskSight({
      umbraStart: '2028-12-31T15:50:00',
      umbraEnd: '2028-12-31T19:52:00',
      moonrise: '2028-12-31T15:53:00',
      risesInShadow: true,
      stops: [
        { key: 'GOLDEN_EVENING_START', time: '2028-12-31T15:35:00' },
        { key: 'SUNSET', time: '2028-12-31T16:00:00' },
        { key: 'CIVIL_DUSK', time: '2028-12-31T16:38:00' },
        { key: 'NAUTICAL_DUSK', time: '2028-12-31T17:16:00' },
      ],
    }));
    expect(model.trackStart).toBe(ms('2028-12-31T15:38:00')); // the pad DOES undershoot the start
    expect(model.hatch.from).toBe(ms('2028-12-31T15:50:00')); // but the hatch is clamped to it
  });

  it('positions the two ends of the track at 0 and 1 and stays monotone between them', () => {
    expect(model.position(model.trackStart)).toBe(0);
    expect(model.position(model.trackEnd)).toBe(1);
    const times = [
      model.trackStart, ms('2028-12-31T15:53:00'), ms('2028-12-31T16:00:00'),
      ms('2028-12-31T17:42:00'), ms('2028-12-31T19:52:00'), model.trackEnd,
    ];
    let prev = -1;
    for (const t of times) {
      const p = model.position(t);
      expect(p).toBeGreaterThanOrEqual(prev);
      prev = p;
    }
  });
});

describe('raceModel — markers and ticks', () => {
  it('carries exactly five DAWN markers, in role order, with the race event and track start hidden on phone', () => {
    const model = raceModel(dawnSight());
    expect(model.markers.map((m) => m.role)).toEqual([
      'trackStart', 'umbraLabel', 'maximum', 'raceEvent', 'horizon',
    ]);
    expect(model.markers.find((m) => m.role === 'trackStart').hidePhone).toBe(true);
    expect(model.markers.find((m) => m.role === 'raceEvent').hidePhone).toBe(true);
    expect(model.markers.find((m) => m.role === 'maximum').hidePhone).toBe(false);
  });

  it('omits the horizon marker on a DAWN sight with no moonset', () => {
    const model = raceModel(dawnSight({ moonset: null, setsInShadow: false }));
    expect(model.markers.map((m) => m.role)).not.toContain('horizon');
  });

  it('carries three phase ticks — every served stop except the race event', () => {
    const model = raceModel(dawnSight());
    expect(model.ticks.map((t) => t.key)).toEqual(['NAUTICAL_DAWN', 'CIVIL_DAWN', 'GOLDEN_MORNING_END']);
  });
});

describe('raceModel — gradient', () => {
  it('builds a CSS linear-gradient string bounded by the direction’s own night/light edge keys', () => {
    const dawnGradient = raceModel(dawnSight()).gradient;
    expect(dawnGradient).toMatch(/^linear-gradient\(90deg,/);
    const duskGradient = raceModel(duskSight()).gradient;
    expect(duskGradient).toMatch(/^linear-gradient\(90deg,/);
    // Different directions draw different gradients — the edge keys differ (NIGHT_START/
    // GOLDEN_MORNING_END for DAWN vs GOLDEN_EVENING_START/NIGHT_END for DUSK).
    expect(dawnGradient).not.toBe(duskGradient);
  });
});

describe('raceSentence — the one accessible answer', () => {
  it('is empty when there is no model to build it from', () => {
    expect(raceSentence(null)).toBe('');
    expect(raceSentence(dawnSight({ race: null }))).toBe('');
  });

  it('states the DAWN shape: enters shadow, maximum with altitude, sunrise, and sets still in shadow', () => {
    // Literal strings — see the class doc's ⚠️ above for why these are NOT `formatTime(sight.X)`.
    // The fixture's own raw digits (dawnSight(): umbraStart 03:34, maximum 05:13, stops[2] 06:11,
    // moonset 06:16 — the plan's own worked-example numbers) are the expected display, verbatim.
    const sentence = raceSentence(dawnSight());
    expect(sentence).toContain('In shadow from 03:34');
    expect(sentence).toContain('maximum 05:13 with the moon 7° up');
    expect(sentence).toContain('sunrise 06:11');
    expect(sentence).toContain('sets 06:16 still in shadow');
    expect(sentence).toBe(
      'In shadow from 03:34, maximum 05:13 with the moon 7° up, sunrise 06:11, sets 06:16 still in shadow.',
    );
  });

  it('omits the closing clause when the DAWN sight does not set in shadow', () => {
    const sentence = raceSentence(dawnSight({ setsInShadow: false }));
    expect(sentence).not.toContain('still in shadow');
  });

  it('states the DUSK shape: rises already in shadow, maximum, sunset, and leaves shadow', () => {
    // Literal strings, for the same reason — duskSight(): moonrise 15:53, maximum 17:42,
    // stops[1] 16:00, umbraEnd 19:52.
    const sentence = raceSentence(duskSight());
    expect(sentence).toBe(
      'Rises 15:53 already in shadow, maximum 17:42 with the moon 5° up, sunset 16:00, leaves shadow 19:52.',
    );
  });

  it('falls back to a bare "In shadow" opening on a DUSK sight that does not rise in shadow', () => {
    const sentence = raceSentence(duskSight({ risesInShadow: false }));
    expect(sentence.startsWith('In shadow,')).toBe(true);
  });

  it('formatRaceTime performs no zone conversion — the served digits are the display, verbatim', () => {
    // The regression guard for the bug this file's header documents: an EclipseSight instant must
    // never pick up a UK-zone shift on the way to the screen.
    const ms = new Date('2026-08-28T05:13:00Z').getTime();
    expect(formatRaceTime(ms)).toBe('05:13');
  });
});
