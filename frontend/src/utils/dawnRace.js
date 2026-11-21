import { parseUtcInstant } from './conversions.js';

/**
 * The eclipse sight's time convention and the per-location eclipse line (`docs/engineering/lunar-eclipse-plan.md`).
 * The dawn/dusk race track this file once modelled is gone; the per-location line the Map
 * callout and the four-day sheet mount (`components/map/EclipseSpotLine.jsx`) is what remains.
 *
 * <h2>⚠️ Times are the sight's OWN convention, and it is NOT {@code solarEventTime}'s</h2>
 *
 * <p>{@code BriefingSlot.solarEventTime} is a genuine UTC instant, bare-serialised — the client converts it to UK
 * local with {@code formatTime}. {@code BriefingSlot.EclipseSight}'s instants are a DIFFERENT convention:
 * {@code EclipseSightAssembler} passes every one of them through {@code LunarEclipseWording.toLondonLocal} before
 * serialising, so the served digits are <b>already</b> Europe/London wall-clock time. Running one through
 * {@code formatTime} would apply the offset a SECOND time, printing an eclipse an hour late whenever BST is in force
 * (invisible in GMT, where the two conventions coincide). {@link formatRaceTime} is the correct read: it recovers the
 * served digits losslessly by formatting on the UTC calendar the SAME instant {@link toMs} parsed them onto.
 */

/** A served instant → epoch milliseconds, or null when absent/unparseable. */
function toMs(value) {
  const d = parseUtcInstant(value);
  return d ? d.getTime() : null;
}

/**
 * Formats one of the sight's epoch-millisecond instants as `HH:mm`, on the UTC calendar — deliberately NOT
 * {@code formatTime}. See the module doc: every {@code EclipseSight} instant is served ALREADY converted to
 * Europe/London wall-clock digits, and {@link toMs} parses those digits by appending {@code Z}, so this instant's OWN
 * UTC-calendar digits already equal the served ones.
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

/**
 * The per-location eclipse line (L7, `docs/engineering/lunar-eclipse-plan.md` §3 L7) — one
 * location's own moon geometry at maximum, and whether it sets or rises still in shadow. Mounted
 * as a sibling block after `TideFitBlock` in both `LocationFourDaySheet` and `MapCallout`
 * (`components/map/EclipseSpotLine.jsx`), fed from the served `BriefingSlot.eclipse`.
 *
 * <p>A pure filter/map/select over served instants (CLAUDE.md's Backend-heavy bullet): every fact printed ({@code moonAltAtMax},
 * {@code moonAzCardinal}, {@code moonset}, {@code moonrise}, {@code setsInShadow},
 * {@code risesInShadow}) is already on the wire, and it reads a sight with or without a served
 * {@code race} — a high-moon eclipse (§2.5's 22:42 example) still has an altitude, a bearing and a
 * set/rise-in-shadow answer.
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
 * {@code risesInShadow} branches below — an L4 accepted item (plan §4): a location can genuinely set (or rise) in shadow
 * before reaching its recorded maximum altitude, so "-3° up … sets 04:10 in shadow" is a real,
 * non-contradictory reading there, and only the flag-less branch above needed the new wording.
 *
 * <p>⚠️ <b>The served altitude is a ROUNDED integer, so testing its sign alone is not enough at the
 * zero boundary</b> (Codex, PR #918, second pass: `LunarEclipseCalculator` rounds before
 * serialising, so an actual −0.4° reading is served as {@code 0}). With neither flag set, {@code 0}
 * cannot tell "genuinely on the horizon" apart from "just below it, rounded up" — a case the first
 * fix's {@code alt < 0} test missed, since a rounded zero has no sign left to read. Rather than a
 * backend change to serve an explicit visibility state (the real exit if this ever needs finer
 * resolution — see plan §4a), the client makes NO visibility claim at exactly zero: {@code
 * "moon on the horizon at max"}, with no bearing, no "up", no "throughout" and no "not visible" —
 * only {@code alt > 0} keeps "above the horizon throughout" and only {@code alt < 0} keeps "below
 * the horizon … not visible from here".
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
  if (alt > 0) {
    return `${head} · above the horizon throughout`;
  }
  if (alt < 0) {
    return `moon ${Math.abs(alt)}° below the horizon at max · not visible from here`;
  }
  return 'moon on the horizon at max';
}
