import { dayLabelFor, eventWord } from './windowFirstCards.js';
import { resolveConfidence, daysOut } from './confidenceUtils.js';

/**
 * The Map tab's single chronological event list ("EV") — map-tab-v2-plan.md §3 P6.
 *
 * <p>Replaces three controls that used to say the same thing in different words: the date strip,
 * the Sunrise/Sunset/Astro/Aurora pills, and the in-map window select. One list, one act.
 *
 * <h2>Solar-first, with honest gaps (§2, §4.6)</h2>
 *
 * <p>Every date is asked four questions in order — solar sunrise, solar sunset, that night's astro,
 * that night's aurora — and only the ones with something to say produce a row. A night event sorts
 * <b>after</b> its day's sunset, because that is when it happens (README "The window control").
 *
 * <h2>Served figures win; a client max is licensed only where nothing is served</h2>
 *
 * <p>A solar row's best score is the window's own served {@code bestRating} — <b>never</b>
 * recomputed here, because the served figure already exists and a client argmax risks disagreeing
 * with it (plan §2.12's ban on re-deriving a server-owned verdict). Astro and aurora carry no served
 * roster best at all, so their row takes a client {@code Math.max} over that night's served stars —
 * the one named member of the licensed per-payload map/filter class (plan §4.6, decision D-3).
 *
 * <h2>D-13 — the browsable horizon outruns the briefing</h2>
 *
 * <p>The briefing renders at most six events (~3 days); the map's own domain is the forecast
 * endpoint's full date range. Dates beyond the briefing's horizon still produce solar rows —
 * unscored, {@code scored: false} — rather than silently shrinking how far the control can browse
 * (the pane's retired {@code DateStrip} javadoc defended exactly this ground).
 *
 * <h2>Aurora is absent, not greyed, for LITE (cross-vendor review on #723)</h2>
 *
 * <p>An earlier revision of the plan promised a greyed row; it is not implemented, because
 * {@code AuroraForecastController} is role-gated at class level and the available-dates endpoint
 * folds a LITE 403 to {@code []} — so a LITE client cannot even learn a night exists to grey it out.
 * {@code isLite} therefore omits aurora rows entirely rather than rendering a disabled one.
 */

/** The kind literals a row's {@code kind} field takes — also the CSS/colour class key. */
export const EVENT_KIND = { SOLAR: 'solar', ASTRO: 'astro', AURORA: 'aur' };

/**
 * A rating this list will show: a finite number. Anything else is "not scored" — mirrors
 * {@code heatSpots.js}'s own rule so a malformed row degrades the same way everywhere.
 */
function numOrNull(v) {
  return typeof v === 'number' && Number.isFinite(v) ? v : null;
}

/**
 * The client max over a night's served stars — the one licensed re-derivation this module
 * performs, and only because astro/aurora carry no served roster best at all.
 *
 * <p><b>The menu-best population (plan §4.14, stated in code as that section requires):</b> this
 * is the max over the FULL served roster for the night — every location the astro/aurora fetch
 * returned a result for — never narrowed to the reader's active filters (star floor, subject,
 * drive time, dark-sky) or to the map's area/scope segment (My area vs Whole catalogue). Kept
 * deliberately consistent with the solar rule below rather than independently scope-filtered: a
 * dropdown column that showed a filtered figure for some rows and an unfiltered one for others
 * would be mixing two different populations under one "N★ best" heading with nothing telling the
 * reader so.
 *
 * <p><b>Exported so the Regions jump list (map-tab-v2-plan.md §3 P11) can reuse the SAME licence
 * at a finer key.</b> `utils/regionsJump.js`'s `buildNightRegionBest` groups these identical served
 * rows by region (via the location-name→region-name join `heat.spots` already carries) and calls
 * this function once per group — no server-owned per-region figure exists for a night window, so
 * grouping the served rows more finely is the same licensed client max, not a new re-derivation.
 * The window dropdown and the jump list must therefore never disagree about a night's best per
 * region, since both ultimately reduce to this one function over the same rows.
 *
 * @param {Array<{stars: ?number}>} rows
 * @returns {?number} the highest star value, or null when nothing is scored
 */
export function bestOfNight(rows) {
  let best = null;
  for (const row of Array.isArray(rows) ? rows : []) {
    const s = numOrNull(row?.stars);
    if (s != null && (best == null || s > best)) best = s;
  }
  return best;
}

/**
 * A night row's pill/dropdown label — "Tonight", "Tomorrow night", "Thursday night" — mirroring
 * the design bundle's {@code evLabel} night branch, built from the app's own day-label vocabulary
 * (never a fresh spelling of "Today"/"Tomorrow") rather than the bundle's own day-of-month compare.
 */
export function nightLabel(date, todayStr, tomorrowStr) {
  if (date === todayStr) return 'Tonight';
  return `${dayLabelFor(date, todayStr, tomorrowStr)} night`;
}

/**
 * A beyond-briefing (D-13) solar row's label, built the same way {@code buildWindowCards} builds
 * an ordinary non-lead window's label ({@code "${day} ${event}"}) — never a fresh phrasing, so a
 * date that later enters the briefing horizon does not read differently the day before it does.
 */
function beyondBriefingLabel(date, targetType, todayStr, tomorrowStr) {
  const day = dayLabelFor(date, todayStr, tomorrowStr);
  const word = eventWord(targetType);
  return `${day} ${word.charAt(0).toUpperCase()}${word.slice(1)}`;
}

/**
 * Builds one solar EV row from a served window (the shape {@code WindowFirstMapPane} builds
 * {@code heat.windows} in, enriched with {@code confidenceTier} and {@code badges}) or synthesises
 * an unscored D-13 row when none was served for this date/type.
 *
 * <p><b>The menu-best population (plan §4.14, stated in code as that section requires):</b>
 * {@code served.bestRating} is the served {@code BriefingWindow.bestRating} figure verbatim,
 * already computed server-side over the FULL roster for that window — never re-derived
 * client-side (the licensed-per-payload-class ban this plan repeatedly cites), so it is
 * unaffected by the reader's active filters or by the map's area/scope segment by construction:
 * there is no client computation here to have scoped in the first place. {@link bestOfNight}'s own
 * doc comment states why the night rows are held to the identical, unfiltered rule rather than
 * independently scope-filtered.
 */
function solarRow(date, targetType, served, todayStr, tomorrowStr, inForecastDomain) {
  const eventType = targetType;
  if (served) {
    return {
      id: `solar:${date}:${eventType}`,
      kind: EVENT_KIND.SOLAR,
      eventType,
      date,
      label: served.label,
      time: served.time || '',
      confidence: resolveConfidence(
        { confidence: served.confidenceTier }, daysOut(date, todayStr),
      ),
      bestRating: numOrNull(served.bestRating),
      scored: numOrNull(served.bestRating) != null,
      badges: Array.isArray(served.badges) ? served.badges : [],
      inForecastDomain,
    };
  }
  return {
    id: `solar:${date}:${eventType}`,
    kind: EVENT_KIND.SOLAR,
    eventType,
    date,
    label: beyondBriefingLabel(date, targetType, todayStr, tomorrowStr),
    time: '',
    confidence: resolveConfidence(null, daysOut(date, todayStr)),
    bestRating: null,
    scored: false,
    badges: [],
    inForecastDomain,
  };
}

/**
 * Builds one night (astro or aurora) EV row.
 *
 * @param {'astro'|'aur'} kind
 * @param {string} date
 * @param {Array<{locationName: string, stars: ?number, nightStart: ?string}>} rows the night's
 *        served results — astro conditions or aurora forecast results, whichever this row is for
 * @param {string} todayStr
 * @param {string} tomorrowStr
 * @param {boolean} inForecastDomain whether `date` is one of the forecast endpoint's own dates —
 *        the EV-ownership forwarding rule (plan §3 P6) reads this directly
 */
function nightRow(kind, date, rows, todayStr, tomorrowStr, inForecastDomain) {
  const eventType = kind === EVENT_KIND.ASTRO ? 'ASTRO' : 'AURORA';
  const first = (Array.isArray(rows) ? rows : []).find((r) => r?.nightStart);
  const best = bestOfNight(rows);
  return {
    id: `${kind}:${date}:${eventType}`,
    kind,
    eventType,
    date,
    label: nightLabel(date, todayStr, tomorrowStr),
    // P5's served night window — never re-derived client-side (map-tab-v2-plan.md §3 P5's own
    // warning against recomputing a solar instant the score was not actually taken over). Left
    // blank rather than guessed when no row carries one.
    nightStart: first?.nightStart ?? null,
    confidence: resolveConfidence(null, daysOut(date, todayStr)),
    bestRating: best,
    scored: best != null,
    // Neither night kind carries served topics yet (§6 O-10) — an invented one would be a
    // narrative this module has no evidence for.
    badges: [],
    // The astro roster is bortle-enriched by construction (`AstroConditionsService` scores only
    // dark-sky locations), so every astro row's "best" answers a narrower question than a solar
    // row's — never the whole catalogue. Aurora carries the equivalent caveat for its own reason:
    // it is Kp/latitude-led, not a place property, but the flag exists so a caller can annotate
    // either kind rather than let the roster's scope go unstated (README OPEN 1's caveat).
    rosterNote: kind === EVENT_KIND.ASTRO ? 'dark-sky locations only' : null,
    inForecastDomain,
  };
}

/**
 * Formats a served UTC-naive night instant (e.g. {@code nightStart}) as a UK clock time, via the
 * caller-supplied formatter — kept as an injected function rather than an import so this module
 * stays free of any DOM/`Intl` assumption a pure-logic unit test would otherwise have to satisfy.
 *
 * @param {?string} instant
 * @param {(v: ?string) => ?string} formatTimeUk
 * @returns {string} 'HH:MM', or '' when absent/unparseable
 */
function formatNightTime(instant, formatTimeUk) {
  if (!instant || typeof formatTimeUk !== 'function') return '';
  return formatTimeUk(instant) || '';
}

/**
 * The Map tab's own SOLAR horizon — briefing dates (served `heat.windows`) plus forecast dates
 * (`forecastDates`), clipped to the UK civil today-forward (D-13's own rule; browser-pass finding
 * against the same clipping this function now shares). Exported so a caller can bound something
 * ELSE against the identical domain {@link buildMapEvents} derives its D-13 filler rows from,
 * rather than defining a second, possibly-diverging notion of "the horizon".
 *
 * <p><b>Why this exists (PR #731 review):</b> `MapView.jsx`'s astro/aurora multi-date fetch — the
 * dropdown's "N★ best" preview — used to fetch every date the astro/aurora available-dates
 * endpoints returned, with no cap. Those endpoints answer with every distinct date EVER
 * persisted (writers replace a rerun date's row rather than pruning it), so a long-lived database
 * fans a single Map-tab mount out to hundreds of concurrent requests. The dropdown only ever
 * states a "best" for rows the current EV list actually carries, and the astro/aurora night rows
 * that matter for that preview are the ones sitting near the solar horizon — so intersecting the
 * available-dates lists against THIS function's result, before fetching, bounds the fan-out to
 * the horizon's own size (naturally ≤ about a week) with no new backend endpoint needed. A night
 * outside the horizon still gets a real EV row (`buildMapEvents` does not consult this function at
 * all) and, once actually SELECTED, still gets its own dedicated single-date fetch regardless of
 * range (`MapView.jsx`'s `nightDate`-keyed effects) — only the unbounded PREVIEW fetch is capped.
 *
 * @param {object} args
 * @param {Array<{date: string}>} [args.solarWindows] served solar windows (`heat.windows`)
 * @param {string[]} [args.forecastDates] every date `GET /api/forecast` returned
 * @param {string} args.todayStr the UK civil today — dates before it are excluded
 * @returns {string[]} sorted, deduplicated dates, `>= todayStr`
 */
export function solarHorizonDates({ solarWindows = [], forecastDates = [], todayStr }) {
  const set = new Set();
  for (const w of solarWindows) {
    if (w?.date && w.date >= todayStr) set.add(w.date);
  }
  for (const d of forecastDates) {
    if (d >= todayStr) set.add(d);
  }
  return Array.from(set).sort();
}

/**
 * Builds the Map tab's single chronological EV list.
 *
 * @param {object} args
 * @param {Array<{date: string, targetType: 'SUNRISE'|'SUNSET', label: string, time: string,
 *   bestRating: ?number, confidenceTier: ?string, badges: ?Array}>} args.solarWindows the served
 *   solar windows — `WindowFirstMapPane`'s `heat.windows`, chronological
 * @param {string[]} args.forecastDates every date `GET /api/forecast` returned (`allDates`),
 *   sorted — the map's own full browsable domain (D-13) and the EV-ownership forwarding test
 * @param {string} args.todayStr today's UK calendar date
 * @param {string} args.tomorrowStr tomorrow's UK calendar date
 * @param {string[]} [args.astroAvailableDates] dates with stored astro conditions
 * @param {Map<string, Array>} [args.astroConditionsByDate] date → that night's astro condition rows
 * @param {string[]} [args.auroraAvailableDates] dates with stored aurora forecast results
 * @param {Map<string, Array>} [args.auroraResultsByDate] date → that night's aurora result rows
 * @param {boolean} [args.isLite] true for a LITE account — aurora rows are omitted outright
 * @param {(v: ?string) => ?string} [args.formatTimeUk] UTC-naive → UK 'HH:MM' formatter, injected
 *   so this module makes no `Intl`/timezone assumption of its own (defaults to a passthrough that
 *   renders no clock time, which is a safe empty state rather than a wrong one)
 * @returns {Array<object>} the EV rows, chronological, night-after-sunset
 */
export function buildMapEvents({
  solarWindows = [],
  forecastDates = [],
  todayStr,
  tomorrowStr,
  astroAvailableDates = [],
  astroConditionsByDate = new Map(),
  auroraAvailableDates = [],
  auroraResultsByDate = new Map(),
  isLite = false,
  formatTimeUk = () => null,
}) {
  const forecastDateSet = new Set(forecastDates);
  const solarByDate = new Map();
  for (const w of solarWindows) {
    if (!w?.date || !w?.targetType) continue;
    const entry = solarByDate.get(w.date) || {};
    entry[w.targetType] = w;
    solarByDate.set(w.date, entry);
  }

  const effectiveAuroraDates = isLite ? [] : auroraAvailableDates;

  const allDatesSet = new Set([
    ...forecastDates,
    ...solarByDate.keys(),
    ...astroAvailableDates,
    ...effectiveAuroraDates,
  ]);
  const orderedDates = Array.from(allDatesSet).sort();

  const rows = [];
  for (const date of orderedDates) {
    const served = solarByDate.get(date) || {};
    const inForecastDomain = forecastDateSet.has(date);

    // Solar: sunrise then sunset, in the order they occur.
    //
    // ⚠️ A SERVED row's own domain flag is `inForecastDomain || Boolean(served.X)`, not
    // `inForecastDomain` alone (adversarial review finding against #7's symmetric-forwarding
    // fix). `served` comes from `heat.windows` — the briefing's own rendered events — and a
    // window the briefing actually rendered is, definitionally, a real forecast date: the
    // briefing IS built from `GET /api/forecast`'s data. `forecastDates` is a SEPARATE prop the
    // pane also happens to hand down, and the two can genuinely be out of sync (most visibly in a
    // test fixture that supplies one and not the other, but nothing in the data model actually
    // guarantees they always agree either). Gating a served row on `forecastDates` membership
    // ALONE would silently make some real, rendered windows non-forwardable. Never applied to
    // night rows below: an astro/aurora result existing for a date is NOT evidence that date has
    // a colour forecast — that is exactly the gap D-13/the EV-ownership rule exists to name.
    // ⚠️ D-13's FILLER branch (no served window) additionally requires `date >= todayStr` —
    // browser-pass finding: right after UK midnight, `forecastDates` can still carry yesterday's
    // date (a stale/leftover key from a `forecastsByDate` map the pane has not yet refreshed), and
    // without this gate that produced a leading "Tuesday Sunrise / Sunset —" pair for an event
    // already hours in the past. `todayStr` is the caller's UK-civil "today" (`ukDateStr()` in
    // `MapView.jsx`) — this module never reads the wall clock itself, so the comparison is exactly
    // as UK-civil as whatever the caller passed, never the browser's own calendar. Plain ISO
    // (`YYYY-MM-DD`) string comparison sorts correctly with no parsing. A SERVED window is never
    // gated by this: the briefing only ever renders current/future events, so `served.X` being
    // present is already evidence the date belongs on screen regardless of this check.
    if (served.SUNRISE || (inForecastDomain && date >= todayStr)) {
      const sunriseInDomain = inForecastDomain || Boolean(served.SUNRISE);
      rows.push(solarRow(date, 'SUNRISE', served.SUNRISE, todayStr, tomorrowStr, sunriseInDomain));
    }
    if (served.SUNSET || (inForecastDomain && date >= todayStr)) {
      const sunsetInDomain = inForecastDomain || Boolean(served.SUNSET);
      rows.push(solarRow(date, 'SUNSET', served.SUNSET, todayStr, tomorrowStr, sunsetInDomain));
    }

    // Night — after that day's sunset (README "The window control").
    if (astroAvailableDates.includes(date)) {
      const nightRows = astroConditionsByDate.get(date) || [];
      const row = nightRow(EVENT_KIND.ASTRO, date, nightRows, todayStr, tomorrowStr, inForecastDomain);
      row.time = formatNightTime(row.nightStart, formatTimeUk);
      rows.push(row);
    }
    if (effectiveAuroraDates.includes(date)) {
      const nightRows = auroraResultsByDate.get(date) || [];
      const row = nightRow(EVENT_KIND.AURORA, date, nightRows, todayStr, tomorrowStr, inForecastDomain);
      row.time = formatNightTime(row.nightStart, formatTimeUk);
      rows.push(row);
    }
  }
  return rows;
}

/**
 * Finds the EV row matching a given kind/date/eventType — the "which row is currently selected"
 * question `MapView` asks on every render rather than owning a second, independently-mutated index
 * (plan §3 P6's EV-ownership paragraph: the pane owns the key, not a duplicate of `eventType`/
 * `date`). Returns -1 when nothing matches (e.g. before the list has data).
 *
 * @param {Array<object>} events the built EV list
 * @param {string} eventType
 * @param {string} date
 * @returns {number} the matching index, or -1
 */
export function findEvIndex(events, eventType, date) {
  return events.findIndex((e) => e.eventType === eventType && e.date === date);
}
