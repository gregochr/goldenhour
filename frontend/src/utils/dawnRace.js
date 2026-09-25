import { parseUtcInstant } from './conversions.js';
import { buildRuleGradient } from '../components/shared/MastheadLight.jsx';

/**
 * The dawn/dusk race — the one genuinely new component the lunar eclipse work adds
 * (`docs/engineering/lunar-eclipse-plan.md` §2.7, §4 #11). A horizontal timeline of the moon
 * racing the brightening (or darkening) sky to the horizon, for one location's served
 * {@code BriefingSlot.EclipseSight}.
 *
 * <h2>This module derives nothing the backend has not already decided</h2>
 *
 * <p>Every instant here — {@code umbraStart}, {@code umbraEnd}, {@code maximum}, {@code moonset},
 * {@code moonrise}, the four light {@code stops} — is served. {@link raceModel} is a pure
 * filter/map/select over those served instants: it floors a track start, clips a track end,
 * decides which of two already-served booleans ({@code setsInShadow}/{@code risesInShadow}) draws
 * a hatch, and turns instants into {@code [0,1]} positions and a CSS gradient. It is the
 * {@code comingUpSparkline.js} precedent (CLAUDE.md's Backend-heavy bullet), not a new derivation
 * class: nothing here computes a moon position, a light boundary or a verdict.
 *
 * <h2>Direction-agnostic by construction</h2>
 *
 * <p>A {@code "DAWN"} sight races the moon SETTING as the sky brightens toward sunrise; a
 * {@code "DUSK"} sight races the moon RISING as the sky darkens toward and past sunset. The two are
 * time-reversed mirrors of the same shape (plan §2.7's "DUSK mirrors it with the evening keys"),
 * so {@link raceModel} runs one branch per direction using the direction's own served stops
 * ({@code NAUTICAL_DAWN/CIVIL_DAWN/SUNRISE/GOLDEN_MORNING_END} for DAWN,
 * {@code GOLDEN_EVENING_START/SUNSET/CIVIL_DUSK/NAUTICAL_DUSK} for DUSK) rather than a client-side
 * table of which key means what — the stops arrive already keyed and already sorted.
 *
 * <h2>⚠️ Times are the sight's OWN convention, and it is NOT {@code solarEventTime}'s</h2>
 *
 * <p>{@code BriefingSlot.solarEventTime} is a genuine UTC instant, bare-serialised
 * ({@code utils/conversions.js}'s own note: "every solar event time" holds a value "produced in
 * UTC" despite the bare string) — the client converts it to UK local with {@link formatTime}.
 * {@code BriefingSlot.EclipseSight}'s instants are a DIFFERENT convention: {@code
 * EclipseSightAssembler} passes every one of them through {@code LunarEclipseWording.toLondonLocal}
 * before serialising, so the served digits are <b>already</b> Europe/London wall-clock time — the
 * class's own javadoc calls this out explicitly ("the same instant, London local"). Verified against
 * a running backend (2026-09-24, BST): a served {@code eclipse.stops[SUNRISE].time} of
 * {@code "06:54:56"} sits one hour ahead of that same window's {@code solarEventTime} of
 * {@code "05:55:25"} for an identical real sunrise — the gap IS the BST offset already applied.
 * Running an {@code EclipseSight} instant through {@link formatTime} would apply that offset a
 * SECOND time, printing an eclipse an hour later than it actually falls whenever BST is in force
 * (invisible in GMT, where the two conventions coincide) — a defect an earlier draft of this file
 * shipped and this note now guards against. {@link formatRaceTime} is the correct read: it recovers
 * the served digits losslessly by formatting on the UTC calendar the SAME instant {@link toMs}
 * parsed them onto, never on the UK calendar {@link formatTime} would apply a second time.
 */

/** One hour, in milliseconds — the track-start floor's own unit. */
const HOUR_MS = 60 * 60 * 1000;

/** Fifteen minutes, in milliseconds — how far past the horizon-clipped edge the track runs on. */
const FIFTEEN_MIN_MS = 15 * 60 * 1000;

/** The served stop keys, in served order, for each race direction (`EclipseSightAssembler`'s own). */
const DAWN_STOP_KEYS = ['NAUTICAL_DAWN', 'CIVIL_DAWN', 'SUNRISE', 'GOLDEN_MORNING_END'];
const DUSK_STOP_KEYS = ['GOLDEN_EVENING_START', 'SUNSET', 'CIVIL_DUSK', 'NAUTICAL_DUSK'];

/** Plain-language labels for the phase ticks — the served stop key, worded for the label row. */
const TICK_LABEL = {
  NAUTICAL_DAWN: 'nautical dawn',
  CIVIL_DAWN: 'civil dawn',
  SUNRISE: 'sunrise',
  GOLDEN_MORNING_END: 'golden hour ends',
  GOLDEN_EVENING_START: 'golden hour starts',
  SUNSET: 'sunset',
  CIVIL_DUSK: 'civil dusk',
  NAUTICAL_DUSK: 'nautical dusk',
};

/** A served instant → epoch milliseconds, or null when absent/unparseable. */
function toMs(value) {
  const d = parseUtcInstant(value);
  return d ? d.getTime() : null;
}

/**
 * Formats one of this model's epoch-millisecond instants (from {@link raceModel}'s markers/ticks)
 * as `HH:mm`, on the UTC calendar — deliberately NOT {@link formatTime}, the card's own
 * {@code solarEventTime} formatter. See the class doc's "Times are the sight's OWN convention"
 * section: every {@code EclipseSight} instant is served ALREADY converted to Europe/London
 * wall-clock digits, and {@link toMs} parses those served digits by appending {@code Z} — so this
 * instant's OWN UTC-calendar digits already equal the served ones. Formatting on the UTC calendar
 * is therefore the lossless round trip back to what the backend actually sent; formatting on the UK
 * calendar (what {@link formatTime} does) would apply Europe/London's offset a SECOND time and
 * print every eclipse instant an hour late for the seven months of BST.
 *
 * @param {?number} ms epoch milliseconds, or null
 * @returns {string} `HH:mm`, or `''` when absent
 */
export function formatRaceTime(ms) {
  if (ms == null) return '';
  return new Date(ms).toLocaleTimeString('en-GB', {
    hour: '2-digit', minute: '2-digit', timeZone: 'UTC',
  });
}

/** Floors an instant to the start of its own hour. */
function floorHour(ms) {
  return Math.floor(ms / HOUR_MS) * HOUR_MS;
}

/** Ceils an instant to the start of the following hour. */
function ceilHour(ms) {
  return Math.ceil(ms / HOUR_MS) * HOUR_MS;
}

/**
 * Builds the dawn/dusk race's pure geometry from one location's served sight, or null when there
 * is nothing to draw (no sight, no race, or a malformed stop list — the caller's gate is
 * {@code sight?.race != null}, this is a defensive second check).
 *
 * @param {?{race: ?string, stops: Array<{key: string, time: string}>, umbraStart: string,
 *   umbraEnd: string, maximum: string, moonset: ?string, moonrise: ?string,
 *   setsInShadow: boolean, risesInShadow: boolean}} sight the served {@code BriefingSlot.EclipseSight}
 * @returns {?{trackStart: number, trackEnd: number, position: (function(?number): ?number),
 *   umbra: {from: number, to: number}, hatch: ?{from: number, to: number},
 *   markers: Array<{key: string, role: string, t: number, label: ?string, hidePhone: boolean}>,
 *   ticks: Array<{key: string, t: number, label: string}>, gradient: string}}
 */
export function raceModel(sight) {
  if (!sight || (sight.race !== 'DAWN' && sight.race !== 'DUSK')) return null;
  const dawn = sight.race === 'DAWN';
  const expectedKeys = dawn ? DAWN_STOP_KEYS : DUSK_STOP_KEYS;
  const stops = Array.isArray(sight.stops) ? sight.stops : [];
  if (stops.length !== 4 || !expectedKeys.every((key, i) => stops[i]?.key === key)) return null;

  const umbraStart = toMs(sight.umbraStart);
  const umbraEnd = toMs(sight.umbraEnd);
  const maximum = toMs(sight.maximum);
  const moonset = toMs(sight.moonset);
  const moonrise = toMs(sight.moonrise);
  const stopMs = stops.map((s) => ({ key: s.key, t: toMs(s.time) }));
  if (umbraStart == null || umbraEnd == null || maximum == null
    || stopMs.some((s) => s.t == null)) {
    return null;
  }

  let trackStart;
  let trackEnd;
  let umbraFrom;
  let umbraTo;
  let hatch = null;
  let umbraLabelT;
  let umbraLabelRole;
  let horizonT;
  let horizonRole;

  if (dawn) {
    // The moon SETS as dawn brightens. The near umbra boundary (u1, "enters shadow") is almost
    // always inside the visible track and is the one labelled; the far one (u4) is usually beyond
    // it and is only ever implied by the hatch running to the track's own right edge.
    const raceStopT = stopMs[2].t; // SUNRISE
    trackStart = floorHour(Math.min(umbraStart, stopMs[0].t));
    const clippedUmbraEnd = moonset != null ? Math.min(umbraEnd, moonset) : umbraEnd;
    trackEnd = Math.max(clippedUmbraEnd, raceStopT) + FIFTEEN_MIN_MS;
    umbraFrom = umbraStart;
    umbraTo = clippedUmbraEnd;
    umbraLabelT = umbraStart;
    umbraLabelRole = 'enters shadow';
    if (sight.setsInShadow && moonset != null) {
      // `Math.min(trackEnd, umbraEnd)`, never `trackEnd` alone: the +15 min pad above can land
      // PAST the eclipse's own true end when moonset, the race stop and umbraEnd all sit close
      // together (a real adversarial-review find) — an unclamped hatch would then claim the moon
      // is still below the horizon, in shadow, for minutes after the eclipse actually finished.
      // Clamping can only ever SHORTEN the band, never lengthen it past what `trackEnd` allows.
      hatch = { from: moonset, to: Math.min(trackEnd, umbraEnd) };
    }
    horizonT = moonset;
    horizonRole = 'moonset';
  } else {
    // The mirror: the moon RISES as dusk darkens. The near umbra boundary here is the FAR one
    // (u4, "leaves shadow") — the eclipse is already under way before dusk and the track's own
    // left edge is where the hatch (still below the horizon) is implied from.
    const raceStopT = stopMs[1].t; // SUNSET
    trackEnd = ceilHour(Math.max(umbraEnd, stopMs[3].t));
    const clippedUmbraStart = moonrise != null ? Math.max(umbraStart, moonrise) : umbraStart;
    trackStart = Math.min(clippedUmbraStart, raceStopT) - FIFTEEN_MIN_MS;
    umbraFrom = clippedUmbraStart;
    umbraTo = umbraEnd;
    umbraLabelT = umbraEnd;
    umbraLabelRole = 'leaves shadow';
    if (sight.risesInShadow && moonrise != null) {
      // The mirror of the DAWN clamp above: `Math.max(trackStart, umbraStart)`, never
      // `trackStart` alone, so the hatch can never claim the moon was already below the horizon,
      // in shadow, before the eclipse had actually begun.
      hatch = { from: Math.max(trackStart, umbraStart), to: moonrise };
    }
    horizonT = moonrise;
    horizonRole = 'moonrise';
  }

  if (!(trackEnd > trackStart)) return null;
  const span = trackEnd - trackStart;

  const position = (t) => {
    if (t == null) return null;
    return Math.min(1, Math.max(0, (t - trackStart) / span));
  };

  const raceStopIdx = dawn ? 2 : 1;
  const raceStop = stopMs[raceStopIdx];

  const markers = [
    { key: 'TRACK_START', role: 'trackStart', t: trackStart, label: null, hidePhone: true },
    { key: dawn ? 'UMBRA_START' : 'UMBRA_END', role: 'umbraLabel', t: umbraLabelT, label: umbraLabelRole, hidePhone: false },
    { key: 'MAXIMUM', role: 'maximum', t: maximum, label: 'max', hidePhone: false },
    {
      key: raceStop.key,
      role: 'raceEvent',
      t: raceStop.t,
      label: dawn ? 'sunrise' : 'sunset',
      hidePhone: true,
    },
  ];
  if (horizonT != null) {
    markers.push({ key: horizonRole.toUpperCase(), role: 'horizon', t: horizonT, label: horizonRole, hidePhone: false });
  }

  const ticks = stopMs
    .filter((_, i) => i !== raceStopIdx)
    .map((s) => ({ key: s.key, t: s.t, label: TICK_LABEL[s.key] || s.key.toLowerCase() }));

  const edgeStartKey = dawn ? 'NIGHT_START' : 'GOLDEN_EVENING_START';
  const edgeEndKey = dawn ? 'GOLDEN_MORNING_END' : 'NIGHT_END';
  const gradientStops = [
    { key: edgeStartKey, position: 0 },
    ...stopMs.map((s) => ({ key: s.key, position: position(s.t) * 100 })),
    { key: edgeEndKey, position: 100 },
  ];

  return {
    trackStart,
    trackEnd,
    position,
    umbra: { from: umbraFrom, to: umbraTo },
    hatch,
    markers,
    ticks,
    gradient: buildRuleGradient(gradientStops),
  };
}

/**
 * The race's one accessible sentence, built from the same model {@link raceModel} draws the
 * (`aria-hidden`) track from — the tide-run chart's own rule (CLAUDE.md: "the chart is
 * {@code aria-hidden} and the verdict string is the entire accessible answer — do not hide it").
 *
 * @param {?object} sight the served {@code BriefingSlot.EclipseSight}
 * @returns {string} the sentence, or `''` when there is nothing to say (no model)
 */
export function raceSentence(sight) {
  const model = raceModel(sight);
  if (!model) return '';
  const dawn = sight.race === 'DAWN';
  const byRole = Object.fromEntries(model.markers.map((m) => [m.role, m]));
  // `byRole.maximum.t`, not `formatTime(sight.maximum)` — see the class doc: this is an
  // EclipseSight instant, already Europe/London local, and `formatTime` would apply that
  // conversion a second time.
  const maxTime = formatRaceTime(byRole.maximum?.t);
  const alt = sight.moonAltAtMax;
  const raceTime = formatRaceTime(byRole.raceEvent?.t);
  const raceWord = dawn ? 'sunrise' : 'sunset';
  const horizon = byRole.horizon;

  const parts = [];
  if (dawn) {
    const entersTime = formatRaceTime(byRole.umbraLabel?.t);
    parts.push(entersTime ? `In shadow from ${entersTime}` : 'In shadow');
    parts.push(maxTime ? `maximum ${maxTime} with the moon ${alt}° up` : `the moon ${alt}° up`);
    if (raceTime) parts.push(`${raceWord} ${raceTime}`);
    if (sight.setsInShadow && horizon?.t != null) {
      const setTime = formatRaceTime(horizon.t);
      if (setTime) parts.push(`sets ${setTime} still in shadow`);
    }
  } else {
    const leavesTime = formatRaceTime(byRole.umbraLabel?.t);
    if (sight.risesInShadow && horizon?.t != null) {
      const riseTime = formatRaceTime(horizon.t);
      if (riseTime) parts.push(`Rises ${riseTime} already in shadow`);
    } else {
      parts.push('In shadow');
    }
    parts.push(maxTime ? `maximum ${maxTime} with the moon ${alt}° up` : `the moon ${alt}° up`);
    if (raceTime) parts.push(`${raceWord} ${raceTime}`);
    if (leavesTime) parts.push(`leaves shadow ${leavesTime}`);
  }

  return `${parts.filter(Boolean).join(', ')}.`;
}

/**
 * The per-location eclipse line (L7, `docs/engineering/lunar-eclipse-plan.md` §3 L7) — one
 * location's own moon geometry at maximum, and whether it sets or rises still in shadow. Mounted
 * as a sibling block after `TideFitBlock` in both `LocationFourDaySheet` and `MapCallout`
 * (`components/map/EclipseSpotLine.jsx`), fed from the same served `BriefingSlot.eclipse` this
 * file's {@link raceModel}/{@link raceSentence} already read.
 *
 * <p>A THIRD pure filter/map/select over served instants, not a fourth derivation class (the class
 * doc above, CLAUDE.md's Backend-heavy bullet): every fact printed ({@code moonAltAtMax},
 * {@code moonAzCardinal}, {@code moonset}, {@code moonrise}, {@code setsInShadow},
 * {@code risesInShadow}) is already on the wire, and unlike {@link raceModel} this reads a sight
 * with NO served {@code race} at all — a high-moon eclipse (§2.5's 22:42 example) still has an
 * altitude, a bearing and a set/rise-in-shadow answer, even though it draws no track.
 *
 * <p>⚠️ <b>No horizon-clearance claim</b> (plan §4 #3, the dropped {@code clearToDeg}) —
 * "above the horizon throughout" states only that the served altitude never crossed zero during
 * the umbral span (neither {@code setsInShadow} nor {@code risesInShadow}), never that the sky was
 * clear or that any terrain was checked.
 *
 * <p>⚠️ <b>"Above the horizon throughout" is the visible-the-whole-time claim, and it must not
 * fire for a location that was never above the horizon at all.</b> {@code EclipseSightAssembler}
 * attaches a sight to every eligible location, including one that qualifies purely on the 30-minute
 * elsewhere-in-the-span clause (§2.3) while sitting below the horizon at the instant of maximum —
 * and {@code LunarEclipseCalculator} derives {@code setsInShadow}/{@code risesInShadow} only from a
 * genuine rise or set crossing *inside* the umbral span, so a location that stays below the horizon
 * for the WHOLE span (no crossing to report) also has BOTH flags false, exactly like a location that
 * stays above it the whole span. A found and fixed defect (Codex, PR #918): with neither flag set,
 * the two cases used to collapse onto one string, and a negative altitude then made it print a
 * self-contradicting "moon -2° up WSW at max · above the horizon throughout". The two are told apart
 * on the sign of {@code moonAltAtMax} alone — the same signal the elsewhere-in-the-span eligibility
 * rule already treats as "below the horizon right now" — printing "moon 2° below the horizon at max
 * · not visible from here" (no bearing; a below-horizon reading names no useful direction) for the
 * negative case, and reserving "above the horizon throughout" for a non-negative one, which is the
 * only reading consistent with the phrase's own claim.
 *
 * <p>A negative {@code moonAltAtMax} still prints as-is, unworded, inside the {@code setsInShadow}/
 * {@code risesInShadow} branches below — the same choice {@link raceSentence} makes for the
 * identical field (an L4 accepted item, plan §4): a location can genuinely set (or rise) in shadow
 * before reaching its recorded maximum altitude, so "-3° up … sets 04:10 in shadow" is a real,
 * non-contradictory reading there, and only the flag-less, negative-altitude branch above needed the
 * new wording.
 *
 * @param {?object} sight the served {@code BriefingSlot.EclipseSight} for one location, from
 *        {@code buildEclipseIndex}/{@code lookupForWindow} (`utils/locationSheet.js`)
 * @returns {?string} the line (no leading glyph — the component adds that), or null when the sight
 *          carries no altitude or bearing to print
 */
export function eclipseSpotLine(sight) {
  if (!sight || sight.moonAltAtMax == null || !sight.moonAzCardinal) return null;
  const alt = sight.moonAltAtMax;
  const head = `moon ${alt}° up ${sight.moonAzCardinal} at max`;
  if (sight.setsInShadow && sight.moonset != null) {
    const t = formatRaceTime(toMs(sight.moonset));
    if (t) return `${head} · sets ${t} in shadow`;
  }
  if (sight.risesInShadow && sight.moonrise != null) {
    const t = formatRaceTime(toMs(sight.moonrise));
    if (t) return `${head} · rises ${t} in shadow`;
  }
  if (alt < 0) {
    return `moon ${Math.abs(alt)}° below the horizon at max · not visible from here`;
  }
  return `${head} · above the horizon throughout`;
}
