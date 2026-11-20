import { describe, it, expect } from 'vitest';
import { eclipseSpotLine, formatRaceTime } from '../utils/dawnRace.js';

/**
 * The eclipse sight's per-location line (`lunar-eclipse-plan.md` §3 L7) and its clock formatter.
 *
 * <p>⚠️ The clock assertions are LITERAL strings, not the formatter under test: {@code EclipseSight} instants are
 * served ALREADY Europe/London-local, so {@link formatRaceTime} performs no zone conversion and a fixture's digits ARE
 * its own expected display string (`dawnRace.js`'s module doc has the full account). The dawn/dusk race model and its
 * sentence were the deleted window popup's, and their tests went with them.
 */

const ms = (iso) => new Date(`${iso}Z`).getTime();

/** A DAWN sight that sets in shadow. */
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
    ...overrides,
  };
}

/** A DUSK sight that rises in shadow. */
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
    ...overrides,
  };
}

describe('formatRaceTime', () => {
  it('is empty for null and the UTC digits otherwise', () => {
    expect(formatRaceTime(null)).toBe('');
    expect(formatRaceTime(undefined)).toBe('');
    expect(formatRaceTime(ms('2026-09-28T06:54:00'))).toBe('06:54');
  });

  it('performs no zone conversion: the served digits are the display, verbatim', () => {
    // An EclipseSight instant must never pick up a UK-zone shift on the way to the screen.
    expect(formatRaceTime(new Date('2026-08-28T05:13:00Z').getTime())).toBe('05:13');
  });
});

describe('eclipseSpotLine — the per-location sheet/callout line (L7)', () => {
  it('is null with no sight at all', () => {
    expect(eclipseSpotLine(null)).toBeNull();
  });

  it('is null when the sight carries no altitude', () => {
    expect(eclipseSpotLine(dawnSight({ moonAltAtMax: null }))).toBeNull();
  });

  it('is null when the sight carries no bearing', () => {
    expect(eclipseSpotLine(dawnSight({ moonAzCardinal: null }))).toBeNull();
  });

  it('states "sets … in shadow" for a DAWN sight that sets before the eclipse ends', () => {
    // dawnSight(): 7° up WSW, moonset 06:16, setsInShadow true — the plan's own worked example.
    expect(eclipseSpotLine(dawnSight())).toBe('moon 7° up WSW at max · sets 06:16 in shadow');
  });

  it('states "rises … in shadow" for a DUSK sight that rises already eclipsed', () => {
    // duskSight(): 5° up ENE, moonrise 15:53, risesInShadow true.
    expect(eclipseSpotLine(duskSight())).toBe('moon 5° up ENE at max · rises 15:53 in shadow');
  });

  it('states "above the horizon throughout" when neither sets nor rises in shadow — no horizon-clearance claim', () => {
    // Neither flag set: the moon was up for the whole umbral span (plan §4 #3 — this states only
    // that the served altitude never crossed zero, never that the sky was clear).
    const sight = dawnSight({ setsInShadow: false, risesInShadow: false, moonAltAtMax: 22, moonAzCardinal: 'S' });
    expect(eclipseSpotLine(sight)).toBe('moon 22° up S at max · above the horizon throughout');
  });

  it('falls back to "above the horizon throughout" when setsInShadow is true but no moonset was served', () => {
    // Defensive: a served flag with no instant behind it must not crash or print "sets undefined".
    const sight = dawnSight({ setsInShadow: true, moonset: null });
    expect(eclipseSpotLine(sight)).toBe('moon 7° up WSW at max · above the horizon throughout');
  });

  it('prints a negative moonAltAtMax as-is, unworded, INSIDE the setsInShadow branch — a real, non-contradictory reading', () => {
    // The moon can genuinely set in shadow before reaching its recorded maximum altitude, so a
    // negative reading here is not the bug this file's own next block pins — only the FLAG-LESS
    // branch needed new wording.
    const sight = dawnSight({ moonAltAtMax: -2, setsInShadow: true });
    expect(eclipseSpotLine(sight)).toBe('moon -2° up WSW at max · sets 06:16 in shadow');
  });

  it('reads a sight with no served race at all — a high-moon eclipse still has an altitude and a set/rise answer', () => {
    const sight = dawnSight({
      race: null, stops: [], setsInShadow: false, risesInShadow: false, moonAltAtMax: 41,
    });
    expect(eclipseSpotLine(sight)).toBe('moon 41° up WSW at max · above the horizon throughout');
  });
});

describe('eclipseSpotLine — below-horizon-at-max is NOT "above the horizon throughout" (Codex, PR #918)', () => {
  // EclipseSightAssembler attaches a sight to a location that qualifies purely on the 30-minute
  // elsewhere-in-the-span eligibility clause while sitting below the horizon AT MAX, and
  // LunarEclipseCalculator derives setsInShadow/risesInShadow only from a genuine crossing inside
  // the umbral span — so a location that never clears the horizon for the WHOLE span has both
  // flags false, exactly like one that is up for the whole span. The two must not collapse onto
  // the same string.
  it('states "not visible from here" with no bearing, never "above the horizon throughout", for a negative altitude with neither flag set', () => {
    const sight = dawnSight({ moonAltAtMax: -2, setsInShadow: false, risesInShadow: false });
    expect(eclipseSpotLine(sight)).toBe('moon 2° below the horizon at max · not visible from here');
  });

  it('takes the absolute value of the altitude, and never prints "up" or the cardinal bearing', () => {
    const sight = dawnSight({
      moonAltAtMax: -11, moonAzCardinal: 'NNE', setsInShadow: false, risesInShadow: false,
    });
    const line = eclipseSpotLine(sight);
    expect(line).toBe('moon 11° below the horizon at max · not visible from here');
    expect(line).not.toContain('up');
    expect(line).not.toContain('NNE');
  });

  it('states no visibility claim at exactly zero — the served altitude is a rounded integer (Codex, PR #918, second pass)', () => {
    // LunarEclipseCalculator rounds before serialising, so an actual -0.4° is served as 0: a
    // rounded zero cannot tell "genuinely on the horizon" apart from "just below it, rounded up".
    // Neither "above the horizon throughout" nor "not visible from here" is a claim the client can
    // back at this exact boundary.
    const sight = dawnSight({ moonAltAtMax: 0, setsInShadow: false, risesInShadow: false });
    const line = eclipseSpotLine(sight);
    expect(line).toBe('moon on the horizon at max');
    expect(line).not.toContain('up');
    expect(line).not.toContain('throughout');
    expect(line).not.toContain('not visible');
  });

  it('the zero boundary, exactly: 1 -> throughout, -1 -> not visible, 0 -> on the horizon', () => {
    const at = (moonAltAtMax) => eclipseSpotLine(
      dawnSight({ moonAltAtMax, setsInShadow: false, risesInShadow: false }),
    );
    expect(at(1)).toBe('moon 1° up WSW at max · above the horizon throughout');
    expect(at(-1)).toBe('moon 1° below the horizon at max · not visible from here');
    expect(at(0)).toBe('moon on the horizon at max');
  });
});

describe('eclipseSpotLine — unreadable and absent inputs', () => {
  const base = { moonAltAtMax: 12, moonAzCardinal: 'WSW' };
  it('is null without a sight, altitude or bearing', () => {
    expect(eclipseSpotLine(null)).toBeNull();
    expect(eclipseSpotLine({ moonAzCardinal: 'W' })).toBeNull();
    expect(eclipseSpotLine({ moonAltAtMax: 3 })).toBeNull();
  });
  it('words a set in shadow and a rise in shadow', () => {
    expect(eclipseSpotLine({ ...base, setsInShadow: true, moonset: '2026-09-28T06:05:00' }))
      .toBe('moon 12° up WSW at max · sets 06:05 in shadow');
    expect(eclipseSpotLine({ ...base, risesInShadow: true, moonrise: '2026-09-28T19:15:00' }))
      .toBe('moon 12° up WSW at max · rises 19:15 in shadow');
  });
  it('falls through when the set or rise time is unreadable', () => {
    expect(eclipseSpotLine({ ...base, setsInShadow: true, moonset: 'nonsense' }))
      .toBe('moon 12° up WSW at max · above the horizon throughout');
    expect(eclipseSpotLine({ ...base, risesInShadow: true, moonrise: 'nonsense' }))
      .toBe('moon 12° up WSW at max · above the horizon throughout');
  });
  it('falls through when the flag is set but the time is absent', () => {
    expect(eclipseSpotLine({ ...base, setsInShadow: true, moonset: null }))
      .toBe('moon 12° up WSW at max · above the horizon throughout');
    expect(eclipseSpotLine({ ...base, risesInShadow: true }))
      .toBe('moon 12° up WSW at max · above the horizon throughout');
  });
  it('words a below-horizon and an on-the-horizon moon without a visibility claim at zero', () => {
    expect(eclipseSpotLine({ moonAltAtMax: -2, moonAzCardinal: 'S' }))
      .toBe('moon 2° below the horizon at max · not visible from here');
    expect(eclipseSpotLine({ moonAltAtMax: 0, moonAzCardinal: 'S' })).toBe('moon on the horizon at max');
  });
});

describe('eclipseSpotLine — a rise that is not in shadow', () => {
  it('does not claim a rise in shadow just because a moonrise time is served', () => {
    const line = eclipseSpotLine({
      moonAltAtMax: 12, moonAzCardinal: 'E', setsInShadow: false, risesInShadow: false,
      moonset: null, moonrise: '2028-12-31T15:53:00',
    });

    expect(line).toBe('moon 12° up E at max · above the horizon throughout');
  });

  it('claims it when the flag and the time are both served', () => {
    const line = eclipseSpotLine({
      moonAltAtMax: 12, moonAzCardinal: 'E', setsInShadow: false, risesInShadow: true,
      moonset: null, moonrise: '2028-12-31T15:53:00',
    });

    expect(line).toBe('moon 12° up E at max · rises 15:53 in shadow');
  });
});
