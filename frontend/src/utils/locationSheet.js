import { daysOut, resolveConfidence } from './confidenceUtils.js';
import { formatDriveDuration, formatTime } from './briefingDisplay.js';
import { GLANCE_MINUTES } from './planningArea.js';
import { leaveByParts } from './leaveBy.js';
import { DARK_SKY_THRESHOLD } from './mapOverlay.js';
import { subjectWordsOf } from './locationTypes.js';
import { filterCalloutTopics } from './windowFirstTopics.js';

/**
 * Whether a location is coastal — it has at least one {@code TideType} preference.
 *
 * <p>⚠️ <b>A second copy of `mapCallout.isCoastalTidalLocation`, deliberately and temporarily.</b>
 * `mapCallout.js` imports {@code shortDow} from THIS module, so importing it back would close a
 * cycle, and the predicate is one line. PR #749 introduces a served per-location tide answer and is
 * the natural home for a shared one — this stays local until that lands rather than minting a third
 * module the same week. It asks an ASTRONOMICAL question (is this place on the coast at all), never
 * a preference-weighted one: nothing here compares an extreme's kind to the configured
 * {@code TideType}, which is the conflation CLAUDE.md's two-tide-axes rule forbids.
 */
function isCoastalTidal(location) {
  return Array.isArray(location?.tideType) && location.tideType.length > 0;
}
import { regionGlossFor } from './regionGloss.js';

/**
 * The four-day location sheet — one place, its six windows, and what each of them costs to reach
 * (plan D10, P8).
 *
 * <h2>Three payloads meet here, and each one answers exactly one question</h2>
 *
 * <p>Nothing in this module derives a fact a payload already carries, and no fact is taken from two
 * places:
 *
 * <ul>
 *   <li><b>Which six windows</b>, their day and their event come from {@code buildHeatStripCards}'
 *       descriptors, unchanged — the same six the strip draws and the pane opens, so a sheet cannot
 *       name a window the page behind it does not have. Away days keep their slot here for the
 *       reason they keep it there: the sheet is a picture of the week, and a missing row would
 *       silently renumber it.</li>
 *   <li><b>The rating and the "why"</b> come from <b>one</b> score row per window
 *       ({@code LocationEvaluationView}: {@code rating} + {@code summary}, which is the field D10
 *       names — {@code claudeSummary} is the briefing payload's name for the same prose). Taking the
 *       star from one store and the sentence from another would let a card print 4★ over a
 *       paragraph written for a 2★ evaluation; whichever store wins supplies both, which is the rule
 *       {@code EvaluationViewService.toEnrichmentResult} states from the other end. Since #405 the
 *       view path and the briefing-enrichment path share one freshness resolver, so this row agrees
 *       with the spot card's {@code claudeRating} by construction rather than by luck.</li>
 *   <li><b>The event time and the region's confidence</b> come from the briefing slot, through
 *       {@link buildSlotIndex} — the score rows carry neither.</li>
 * </ul>
 *
 * <h2>⚠️ Both indexes join id-first, and the score one had to be rebuilt to do it</h2>
 *
 * <p>P8's first cut looked its ratings up through the provider's {@code scoreIndex}, which is keyed
 * on {@code date|targetType|locationName} alone. An adversarial review caught it against a note the
 * provider had already written for this phase by name: that index "is name-keyed and drops every row
 * missing a region or location name, i.e. a different population from the one the heat join saw".
 * The consequence was visible rather than theoretical — the sheet timed its rows <em>id-first</em>
 * (this file's own {@link buildSlotIndex}) and rated them name-only, so a location renamed since the
 * last evaluation run showed a correct departure time under "Not scored yet" while the heat field
 * behind the dialog, which is id-first, still painted its rating. Two roster entries sharing a
 * display name resolved to whichever was indexed first, printing another place's stars and another
 * place's prose. So {@link buildScoreIndex} reads the raw {@code scoreRows} the provider now
 * publishes, and both indexes here apply one key policy: id first, name second, an id hit ends the
 * lookup, first-inserted wins. A name is a display string a user can edit; an id is not.
 *
 * <h2>⚠️ The confidence is the LOCATION'S OWN region's, not the window's</h2>
 *
 * <p>The strip reads {@code card.confidence}, which {@code buildWindowCards} documents as <em>the
 * top region's</em> — correct there, because a thumbnail is a picture of the whole roster. It is
 * wrong here, and the first cut used it: search matches the whole roster, so the location on screen
 * is routinely in a different region from the one that led its window, and the row's provisional
 * mark then qualified a Northumberland rating with the Lake District's certainty — silently, in both
 * directions. {@code BriefingRegion.confidence} is served per region on the same
 * {@code eventSummary.regions[]} this file already walks for the event time, so it costs nothing to
 * take the right one.
 *
 * <h2>⚠️ The ratings are NOT {@code heatSpots[].scores}, and the difference is deliberate</h2>
 *
 * <p>That array is the field's population and it withholds every rating for a location that is not a
 * sky subject — a woodland GO means heavy cloud and mist, so blending one into a field of sky scores
 * would bloom gold over coasts rated 1 on precisely the morning the sky is at its worst. The rule is
 * about <em>blending and comparing across locations</em>. This sheet does neither: its rows are one
 * place, so every star on it is on one polarity and the comparison it invites is between Thursday
 * and Friday at the same wood. Reading the field's array here would leave a searched wood with six
 * blank rows and no explanation. The precedent is already in the arm — {@code
 * WindowFirstRegionalPanel} and {@code HeatmapGrid} both record that per-location detail is
 * canopy-inclusive where the aggregate above it is not.
 *
 * <h2>The drive is measured from the page's origin, and marked when the place is out of scope</h2>
 *
 * <p>{@link buildLocationSheet} takes whatever reach map the page is planning from
 * ({@code effectiveReachById}) rather than the per-user home map. At home the two are the same
 * object, so this is the prototype's own rule ("home minutes"); away it is the base-measured drive
 * every other figure on the screen already means, and the sheet says which base it measured from.
 * The alternative — home minutes under an away origin — would put two journeys on one screen: the
 * spot card behind the sheet would read "42 min" and the sheet "3h 10" for the same place, with the
 * lens bar above both saying {@code Drive from Keswick}. P5 recorded the single-producer rule and P7
 * implemented it at the provider precisely so that every consumer switches together.
 *
 * <p>What the prototype's marker carries is a different fact, and it is kept — <b>but it names its
 * own scope</b>, which the prototype's bare "outside your plan" does not. The scope means two things
 * ({@code planOrigin.scopeRegions}: the planning area at home, the origin's own region away), and
 * only one of them is about distance. A Dales spot forty-five minutes from a Keswick base is outside
 * the plan and near — so a bare badge sat over "45 min from Keswick" and read as broken. It now says
 * {@code outside Lake District} / {@code outside your 3h area}, which is the fact in both cases.
 */

/** How many stars a window needs before the lead line counts it. */
const STRONG_RATING = 4;

/** The projector's own rating bounds. A value outside them is discarded, never displayed. */
const MIN_RATING = 1;
const MAX_RATING = 5;

/** A score bar's own bounds — the model's 0–100 output range, integer, same discard rule as rating. */
const MIN_SCORE = 0;
const MAX_SCORE = 100;

/** Discards a score-bar value outside 0–100 or non-integer, mirroring the rating bound above. */
function boundedScore(value) {
  return Number.isInteger(value) && value >= MIN_SCORE && value <= MAX_SCORE ? value : null;
}

/**
 * A light-time boundary as served, or null. No shape check beyond "a non-blank string": the
 * formatter downstream is the one that knows what parses, and a second opinion here could only
 * disagree with it.
 */
function isoOrNull(value) {
  return typeof value === 'string' && value.trim() !== '' ? value : null;
}

/**
 * One labelled light window as {@code {label, range}}, or null when either end is unprintable.
 *
 * <p>Both ends or neither. Half a window is a claim the reader cannot act on — "golden 20:47–"
 * says the light starts and never says it ends — and the sheet's rule everywhere else is that an
 * absent fact is silence rather than a partial one.
 */
function lightWindow(label, startIso, endIso) {
  const start = formatTime(startIso);
  const end = formatTime(endIso);
  return start && end ? { label, range: `${start}–${end}` } : null;
}

/**
 * The solar event's own instant, recovered from a score row's light boundaries.
 *
 * <p>Not a second source for the event time — a THIRD-choice fallback for it, and strictly closer
 * to the truth than the one it displaces. `SolarService.goldenBlueWindow` returns the event itself
 * as a shared boundary (sunrise IS `blueHourEnd` and `goldenHourStart`; sunset IS `goldenHourEnd`
 * and `blueHourStart`), so this row already carries this location's own sunrise or sunset whenever
 * it carries a light line at all.
 *
 * <p>⚠️ It exists because Phase 2 made an old gap visible. When the briefing carries no slot for a
 * window — which `BriefingHonestyFilter` produces deliberately, by emptying the slot list of a
 * region nothing has scored — the header time falls back to `card.time`, the window's roster-wide
 * header clock, i.e. some other location's. That was quietly wrong before; with a light line under
 * it built from THIS location's geometry, it becomes two sunrise times minutes apart on one row
 * with nothing explaining the gap. This is the same defect the header comment below records the
 * first cut having, arriving by a different route.
 *
 * <p>The twin is tried second so a midnight-sentinel null on one end (the backend drops those) does
 * not cost the event time, which the other end still carries.
 *
 * <p>Exported since map-tab-v2-plan.md §3 P9: the Map tab's callout recovers the same instant for
 * its own leave-by fact, from the same {@code buildScoreIndex} row this file already builds — a
 * second recovery would risk disagreeing with this one about which boundary is the event.
 *
 * @param {?object} score      the score-row entry, or null
 * @param {string}  targetType SUNRISE or SUNSET
 * @returns {?string} the event's UTC instant as served, or null
 */
export function eventInstantOf(score, targetType) {
  if (!score) return null;
  return targetType === 'SUNRISE'
    ? (score.blueHourEnd ?? score.goldenHourStart ?? null)
    : (score.goldenHourEnd ?? score.blueHourStart ?? null);
}

/**
 * This window's golden and blue hours, in the order they happen for its own event side.
 *
 * <p>Sunrise runs blue → golden (civil dawn, sunrise, then the sun climbing to +6°); sunset runs
 * golden → blue (the sun falling to the horizon, then civil dusk). The map popup already prints
 * them in exactly this order for exactly this reason, and a drill-down that reversed them would
 * have the same two facts telling two different stories about the same evening.
 *
 * @param {?object} score      the score-row entry, or null
 * @param {string}  targetType SUNRISE or SUNSET
 * @returns {?Array<{label: string, range: string}>} ordered windows, or null when neither prints
 */
function lightWindows(score, targetType) {
  if (!score) return null;
  const golden = lightWindow('golden', score.goldenHourStart, score.goldenHourEnd);
  const blue = lightWindow('blue', score.blueHourStart, score.blueHourEnd);
  const ordered = (targetType === 'SUNRISE' ? [blue, golden] : [golden, blue]).filter(Boolean);
  return ordered.length > 0 ? ordered : null;
}

/** Every slot in an event summary, regioned and unregioned alike — {@code solarEventTimes}' rule. */
function slotsOf(eventSummary) {
  const regioned = (eventSummary?.regions ?? []).flatMap(
    (region) => (region?.slots ?? []).map((slot) => ({ slot, region })),
  );
  return [...regioned, ...(eventSummary?.unregioned ?? []).map((slot) => ({ slot, region: null }))];
}

/** The window half of both indexes' key. */
function tailOf(date, targetType) {
  return `${date}|${targetType}`;
}

/** Puts a value under the id key and the name key, first-inserted winning in each. */
function index(byId, byName, locationId, locationName, tail, value) {
  if (locationId != null) {
    const key = `${locationId}|${tail}`;
    if (!byId.has(key)) byId.set(key, value);
  }
  if (locationName) {
    const key = `${locationName}|${tail}`;
    if (!byName.has(key)) byName.set(key, value);
  }
}

/**
 * Each slot's own solar event time and its region's confidence, keyed by location and window.
 *
 * <p><b>Unregioned slots are indexed too</b>, unlike {@code buildWindowSpots}, which drops them.
 * That filter exists so the strip's badge population matches the header star's; this index answers
 * factual questions about one location and has no aggregate to protect. Such a slot carries a null
 * confidence, which is the honest answer — a region's confidence is a fact about a region.
 *
 * @param {Array} days {@code briefing.days}
 * @returns {{byId: Map<string, object>, byName: Map<string, object>}} the two indexes, each valued
 *          {@code {eventTime, confidence}}
 */
export function buildSlotIndex(days) {
  const byId = new Map();
  const byName = new Map();
  for (const day of Array.isArray(days) ? days : []) {
    if (!day?.date) continue;
    for (const summary of day.eventSummaries ?? []) {
      if (!summary?.targetType) continue;
      const tail = tailOf(day.date, summary.targetType);
      for (const { slot, region } of slotsOf(summary)) {
        if (!slot?.solarEventTime) continue;
        index(byId, byName, slot.locationId, slot.locationName, tail, {
          eventTime: slot.solarEventTime,
          confidence: region?.confidence ?? null,
        });
      }
    }
  }
  return { byId, byName };
}

/**
 * Each coastal location's map-tab tide-alignment facts per window (the tide-chip bundle rev 2) —
 * whether THIS window's water actually lands on the light, keyed exactly like {@link buildSlotIndex}
 * so `MapView`/`MapCallout` read it through the same {@link lookupForWindow}.
 *
 * <p>Reads two of {@code BriefingSlot.TideInfo}'s four sibling fields off each slot flat
 * ({@code tideOnTheLight}, {@code nearestSolarOffsetPhrase} — {@code @JsonUnwrapped} puts them
 * directly on the slot, the same way {@code slot.tideAligned} already reaches this file's other
 * readers) — never {@code tideAligned}, which tests the location's configured {@code TideType}
 * PREFERENCE, a different question the map's glyph and tiebreaker must not answer (CLAUDE.md's
 * tide-axis rule against conflating the two). The other two wire fields
 * ({@code nearestSolarOffsetMinutes}, {@code nearestExtremeKind}) have no reader on this arm — the
 * chip, tooltip and callout only ever need the boolean and the already-formatted phrase — so this
 * INDEX carries only what is read; the wire keeps serving all four regardless.
 *
 * <p>A slot with no derivable tide-alignment fact ({@code tideOnTheLight === null} — inland, or no
 * stored extremes near this event) is SKIPPED rather than indexed as "not aligned": a missing entry
 * and a {@code false} one are different claims, and only the deriver knows which is true.
 *
 * @param {Array} days {@code briefing.days}
 * @returns {{byId: Map<string, object>, byName: Map<string, object>}} the two indexes, each valued
 *          {@code {onTheLight, phrase}}
 */
export function buildTideAlignmentIndex(days) {
  const byId = new Map();
  const byName = new Map();
  for (const day of Array.isArray(days) ? days : []) {
    if (!day?.date) continue;
    for (const summary of day.eventSummaries ?? []) {
      if (!summary?.targetType) continue;
      const tail = tailOf(day.date, summary.targetType);
      for (const { slot } of slotsOf(summary)) {
        if (slot?.tideOnTheLight == null) continue;
        index(byId, byName, slot.locationId, slot.locationName, tail, {
          onTheLight: Boolean(slot.tideOnTheLight),
          phrase: slot.nearestSolarOffsetPhrase ?? null,
        });
      }
    }
  }
  return { byId, byName };
}

/**
 * Each location's rating and "why" per window, from the RAW score rows.
 *
 * <p>Not the provider's {@code scoreIndex} — see the module comment for the defect that cost.
 * {@code rating} is bounded here rather than at the render, mirroring {@code buildWindowSpots}: a
 * value outside 1–5 would otherwise reach {@code spotBadgeStyle}, which clamps, and paint a badge
 * for a rating the pipeline never produced.
 *
 * <p>Also carries {@code fierySky}/{@code goldenHour} (location-sheet superset plan, Phase 1) —
 * the same two 0–100 model outputs the window popup's peek already shows, bounded here the same way
 * {@code rating} is: an out-of-range or non-integer value is discarded rather than reaching
 * {@link ScoreBar}, which clamps and would otherwise draw a bar for a number the pipeline never
 * produced.
 *
 * <p>And the four golden/blue hour boundaries (Phase 2), kept RAW here — UTC ISO strings exactly as
 * the backend serves them — because this index is the join and formatting is the render's job. They
 * are turned into UK clock times once, in {@link buildLocationSheet}, beside the row's own event
 * time, which is already formatted there for the same reason. Unlike the scores there is no range
 * to bound: an unparseable string produces no clock time and therefore no line, which is the same
 * discard by a different mechanism.
 *
 * @param {Array} scoreRows raw {@code LocationEvaluationView} rows
 * @returns {{byId: Map<string, object>, byName: Map<string, object>}} valued
 *          {@code {rating, summary, fierySky, goldenHour, goldenHourStart, goldenHourEnd,
 *          blueHourStart, blueHourEnd}}
 */
export function buildScoreIndex(scoreRows) {
  const byId = new Map();
  const byName = new Map();
  for (const row of Array.isArray(scoreRows) ? scoreRows : []) {
    if (!row?.date || !row?.targetType) continue;
    const rating = Number.isInteger(row.rating)
      && row.rating >= MIN_RATING && row.rating <= MAX_RATING
      ? row.rating
      : null;
    const summary = typeof row.summary === 'string' && row.summary.trim() !== ''
      ? row.summary.trim()
      : null;
    const fierySky = boundedScore(row.fierySkyPotential);
    const goldenHour = boundedScore(row.goldenHourPotential);
    index(byId, byName, row.locationId, row.locationName,
      tailOf(row.date, row.targetType), {
        rating,
        summary,
        fierySky,
        goldenHour,
        goldenHourStart: isoOrNull(row.goldenHourStart),
        goldenHourEnd: isoOrNull(row.goldenHourEnd),
        blueHourStart: isoOrNull(row.blueHourStart),
        blueHourEnd: isoOrNull(row.blueHourEnd),
      });
  }
  return { byId, byName };
}

/**
 * One location's entry in either index, or null.
 *
 * <p>Id first, and an id hit <b>ends</b> the lookup — {@code buildHeatSpots} states the same rule:
 * id-first only means anything if falling through is impossible once the authoritative key has
 * answered.
 *
 * @param {?object} idx        from {@link buildSlotIndex} or {@link buildScoreIndex}
 * @param {*}       locationId the location's id, or null
 * @param {?string} name       the location's display name
 * @param {string}  date       the window's date
 * @param {string}  targetType SUNRISE or SUNSET
 * @returns {?object} the entry, or null
 */
export function lookupForWindow(idx, locationId, name, date, targetType) {
  if (!idx) return null;
  const tail = tailOf(date, targetType);
  if (locationId != null) {
    const hit = idx.byId?.get(`${locationId}|${tail}`);
    if (hit !== undefined) return hit;
  }
  return (name ? idx.byName?.get(`${name}|${tail}`) : undefined) ?? null;
}

/** Day of the month for the row's date box, on the UK calendar the dates are already keyed to. */
function dayOfMonth(dateStr) {
  return new Date(`${dateStr}T12:00:00Z`)
    .toLocaleDateString('en-GB', { day: 'numeric', timeZone: 'UTC' });
}

/**
 * Three-letter weekday for a date — the strip's own {@code calDow}, which is module-private there.
 *
 * <p>Noon UTC <b>and</b> {@code timeZone: 'UTC'}: the pair is what keeps the answer off the reader's
 * calendar. Noon alone survives every offset for the date's own day, and the explicit zone is what
 * stops {@code toLocaleDateString} reading the device's — east of UTC by more than twelve hours,
 * noon UTC is the following day, and the departure's day word would name the wrong night on the one
 * surface that exists to name it. Covered in {@code locationSheetAbroad.test.js}, which is pinned to
 * a zone east of the UK for exactly this.
 *
 * <p>Exported since map-tab-v2-plan.md §3 P9: the callout's own leave-by fact marks a midnight
 * crossing with the identical day word rather than authoring a second formatter for the same rule.
 */
export function shortDow(dateStr) {
  return new Date(`${dateStr}T12:00:00Z`)
    .toLocaleDateString('en-GB', { weekday: 'short', timeZone: 'UTC' });
}

/**
 * The window this sheet's one map handoff should open on.
 *
 * <p>The best-rated window, because that is the one a reader who searched a place came to find; the
 * first forecast window when nothing here is rated, so the action is never withheld. Ties break
 * earliest, which is the only tie-break that does not require inventing a preference.
 *
 * <p><b>An away window is never chosen while any forecast one exists.</b> A travel day's slots are
 * collected and never evaluated, so opening the map there lands on a date the pipeline skipped.
 * Where <em>every</em> window is away the answer is the first row anyway — the sheet must still
 * offer the map, and a footer that vanished exactly when the rest of the card is emptiest would be
 * the worst moment to withhold it.
 *
 * @param {Array} rows the built rows
 * @returns {?object} the row to hand off, or null when there are no rows at all
 */
function handoffRow(rows, focusKey = null) {
  if (rows.length === 0) return null;
  // ⚠️ The window the reader ARRIVED on wins, when there is one and it was forecast. Adversarial
  // review, confirmed: increment §2 says `◍ Show on map →` "returns to the map at the current
  // window", and before this the sheet had no idea what the current window was — so it handed back
  // its best-RATED one, i.e. a reader who came from Tonight Sunset was offered Saturday Sunrise.
  // An AWAY focus window falls through: opening the map on a date the pipeline skipped is the very
  // thing this function's own javadoc refuses.
  const focused = focusKey ? rows.find((row) => row.key === focusKey && !row.away) : null;
  if (focused) return focused;
  const forecast = rows.filter((row) => !row.away);
  const rated = forecast.filter((row) => row.rating != null);
  if (rated.length > 0) {
    return rated.reduce((best, row) => (row.rating > best.rating ? row : best));
  }
  return forecast[0] ?? rows[0];
}

/**
 * The lead line — what the six windows add up to, or null when they add up to nothing sayable.
 *
 * <p><b>It states no denominator, and that is a rule rather than a simplification.</b> The prototype
 * prints "N of 6", which is true of a demo where every window is scored and false of a real forecast
 * at T+4. The obvious repair — "N of the SCORED windows" — was built and then removed by an
 * adversarial review, because the window-first §6 sweep bans exactly that sentence: <em>"No counts
 * of our own data ('11 aligned' is a fact about the database, not about tonight)"</em>, and "3
 * scored windows" is a count of evaluation rows the pipeline produced. It also put two integers
 * meaning different things side by side ("The next 4 days here · 2 of 4"). What is left is a count
 * of the sky.
 *
 * <p>Null when the ratings are not known — an unfetched or failed request is not evidence that
 * nothing was rated. "None at {@code 4★+}" when they are known and none reaches the bar: that is the
 * "don't bother" signal, and it is the only place on the sheet that gives it.
 *
 * @param {Array}   rows       the built rows
 * @param {number}  dayCount   how many distinct days the windows span
 * @param {boolean} scoresKnown whether the ratings response has actually arrived
 * @returns {?string} the sentence, or null
 */
function leadLine(rows, dayCount, scoresKnown) {
  if (!scoresKnown || rows.length === 0) return null;
  const strong = rows.filter((row) => row.rating != null && row.rating >= STRONG_RATING).length;
  const days = `The next ${dayCount} day${dayCount === 1 ? '' : 's'} here`;
  const count = strong === 0
    ? `none at ${STRONG_RATING}★+`
    : `${strong} window${strong === 1 ? '' : 's'} at ${STRONG_RATING}★+`;
  return `${days} · ${count}`;
}

/**
 * What the "outside" badge says, which is never just "outside your plan".
 *
 * <p>Exported since M3 because the search dropdown makes the same claim on the same rows, and the
 * bundle's own copy for it there ("outside your plan") is the vague form this function exists to
 * refuse — a reader who has moved the origin needs to know WHICH plan. One vocabulary, two
 * surfaces, so a place can never be "outside your 3h area" on the sheet and "outside your plan" in
 * the box that opened it.
 *
 * @param {?object} origin the origin descriptor, or null for home
 * @returns {string} the badge's words
 */
export function outsideLabel(origin) {
  return origin
    ? `outside ${origin.name}`
    : `outside your ${formatDriveDuration(GLANCE_MINUTES)} area`;
}

/**
 * A window spot (or a field chip) as the identity this sheet takes — the M4 entry points' one
 * translation.
 *
 * <h2>Why a translation rather than a catalogue lookup</h2>
 *
 * <p>The sheet's {@code spot} prop has three fields and search hands it a <em>heat catalogue</em>
 * entry ({@code id}/{@code name}/{@code regionName}); M4's two new entries hand over a
 * {@code buildWindowSpots} descriptor ({@code locationId}/{@code locationName}/{@code regionName}),
 * which is the briefing's own vocabulary. Joining back through the heat catalogue to recover the
 * first shape would be a second join with a second failure mode — a location the briefing rates but
 * {@code GET /api/locations} has not published (a fresh roster entry, a poll landing between the two
 * fetches) would resolve to nothing and the chip a reader just clicked would open no sheet at all.
 * The three fields are already on the descriptor, so nothing is looked up.
 *
 * <p>The id is what matters and it is the SAME id: {@code buildLocationSheet} joins its ratings and
 * its times id-first through {@link lookupForWindow}, and {@code slot.locationId} is the field both
 * indexes are keyed on. A name-only translation would have re-introduced the exact defect the module
 * comment records.
 *
 * @param {?object} spot a {@code buildWindowSpots} descriptor, or a field chip carrying the same
 *        three fields
 * @returns {?{id: *, name: string, regionName: ?string}} the sheet's identity, or null
 */
export function sheetSpotOf(spot) {
  if (!spot) return null;
  return {
    id: spot.locationId ?? null,
    name: spot.locationName ?? '',
    regionName: spot.regionName ?? null,
  };
}

/**
 * The one row the map adds to this sheet (increment §2, "What the map adds: one row").
 *
 * <h2>Why it is here rather than on a second panel</h2>
 *
 * <p>These facts — subject tags, dark sky, coastal/tide, the week's topics — previously existed
 * ONLY on the map callout. That is what made routing the callout's prose into this sheet safe to
 * begin with: with them, the callout is a strict SUBSET of the sheet, and clicking through to the
 * deeper surface can never show less than the shallower one did. Without them the route would lose
 * information, which is what a second parallel panel was built to avoid and then thrown away for.
 *
 * <h2>It is not map-only</h2>
 *
 * <p>Derived from the location record and the windows the sheet already holds, so EVERY entry point
 * gets it — search, a popup field chip, a spot card, the map callout. A row that appeared only on
 * the map's route would make one dialog two dialogs again, one screen down.
 *
 * <p>Each fact is omitted rather than half-stated when its input is missing, the silence rule this
 * whole module follows: {@code null} {@code bortleClass} is "not enriched", not "bright".
 *
 * @param {?object} location the roster record — {@code locationType}, {@code bortleClass},
 *        {@code tideType}. Null (no record joined) yields an empty list, never a placeholder row
 * @param {Array} windows {@code buildHeatStripCards}' descriptors, for the week's topics
 * @returns {Array<{key: string, text: string}>} the facts, in the increment's own reading order
 */
function sheetMetaFacts(location, windows) {
  const facts = [];
  if (!location) return facts;

  const subjects = subjectWordsOf(location.locationType);
  if (subjects.length > 0) facts.push({ key: 'subjects', text: subjects.join(' · ') });

  // The SAME threshold and the SAME wording the callout's own fact list uses
  // (`mapCallout.calloutFacts`) — two spellings of one number is how a reader concludes the two
  // surfaces are measuring different things.
  if (Number.isFinite(location.bortleClass)) {
    facts.push({
      key: 'darksky',
      text: location.bortleClass <= DARK_SKY_THRESHOLD
        ? `Dark sky ${location.bortleClass} · dark`
        : `Dark sky ${location.bortleClass}`,
    });
  }

  // ⚠️ "the tide matters here", not the increment's literal "tide applies". The increment predates
  // #748's plain-English copy pass, whose whole thesis was removing exactly this register from
  // customer-facing surfaces ("held back" -> "not listed", "drive from origin" -> "drive time").
  // "Applies" is the legalistic form that pass was written to delete, so the string is reconciled
  // to the house style rather than shipped against it. Recorded in map-tab-v2-plan.md §4.
  if (isCoastalTidal(location)) facts.push({ key: 'coastal', text: 'Coastal · the tide matters here' });

  // The week's topics, de-duplicated by label across every window the sheet shows, and filtered to
  // this location exactly the way the callout filters its own — a day-scoped tide topic is not
  // about an inland place, and `filterCalloutTopics` is the one implementation of that rule.
  const seen = new Set();
  for (const card of Array.isArray(windows) ? windows : []) {
    for (const badge of filterCalloutTopics(card?.badges, isCoastalTidal(location))) {
      const label = typeof badge?.label === 'string' ? badge.label.trim() : '';
      if (!label || seen.has(label)) continue;
      seen.add(label);
      facts.push({ key: `topic:${label}`, text: label });
    }
  }
  return facts;
}

/**
 * Everything the sheet renders for one location.
 *
 * <p>Only the location's <em>identity</em> is held by the caller; every figure here is looked up
 * live from the payloads on each render, so a sheet left open across a poll shows the new forecast
 * rather than a snapshot of the old one.
 *
 * @param {object}  spot        a heat spot — {@code id}, {@code name}, {@code regionName}
 * @param {Array}   windows     {@code buildHeatStripCards}' descriptors, in render order
 * @param {object}  sources
 * @param {?object} sources.scoreIndex from {@link buildScoreIndex}
 * @param {?object} sources.slotIndex  from {@link buildSlotIndex}
 * @param {boolean} [sources.scoresKnown] whether the ratings response has arrived. False makes no
 *        claim about what is scored — the rule {@code scoresLoaded} exists for, stated at its
 *        declaration: "a failed or in-flight fetch is not evidence that nothing was rated"
 * @param {?Map}    sources.reachById  the reach map the PAGE plans from
 *        ({@code effectiveReachById}) — see the module comment for why not the home one
 * @param {string[]} [sources.scopeRegionNames] the region names in scope, from
 *        {@code planOrigin.scopeRegions}. Absent OR EMPTY means "not known", which marks nothing
 * @param {?object} [sources.origin]  the origin descriptor, for the badge's wording
 * @param {string}  sources.todayStr  today's UK date, for the confidence fallback
 * @param {?string} [sources.focusWindowKey] {@code date:targetType} of the window the reader
 *        arrived on (the map callout's route only). Seeds the expansion and the footer's map action;
 *        null everywhere else, which keeps those two on their existing rules
 * @param {?Map} [sources.regionGlossIndex] from {@code regionGloss.buildRegionGlossIndex} — the
 *        prose fallback, so this sheet can never show less than the callout that routes into it
 * @param {?object} [sources.location] this place's roster record ({@code locationType},
 *        {@code bortleClass}, {@code tideType}), for the meta row and the per-row tide sentence.
 *        Null is an ordinary state — the roster and the briefing arrive over two fetches — and
 *        yields no meta row rather than a row of blanks
 * @returns {object} the sheet's content
 */
export function buildLocationSheet(spot, windows, {
  scoreIndex = null, slotIndex = null, scoresKnown = false, reachById = null,
  scopeRegionNames = null, origin = null, todayStr = '', location = null, focusWindowKey = null,
  regionGlossIndex = null,
} = {}) {
  const name = spot?.name ?? '';
  const locationId = spot?.id ?? null;
  const driveMinutes = (locationId == null ? null : reachById?.get(locationId)?.driveMinutes) ?? null;
  const rows = (Array.isArray(windows) ? windows : []).filter(Boolean).map((card) => {
    const slot = lookupForWindow(slotIndex, locationId, name, card.date, card.targetType);
    // Not looked up at all on an away day: a travel day's slots are collected and never evaluated,
    // so even a stale row for one must not become a forecast for a night nobody forecast.
    const score = card.away
      ? null
      : lookupForWindow(scoreIndex, locationId, name, card.date, card.targetType);
    const rating = score?.rating ?? null;
    const parts = card.away ? null : leaveByParts(slot?.eventTime, driveMinutes);
    // The day marker, named here rather than in the component so the ONE thing a renderer has to do
    // with it is print it. Null exactly when the departure shares the event's UK day.
    const leave = parts && { ...parts, dayWord: parts.sameDay ? null : shortDow(parts.date) };
    return {
      key: card.key,
      date: card.date,
      targetType: card.targetType,
      dow: card.dow,
      dayNum: dayOfMonth(card.date),
      eventWord: card.sunrise ? 'Sunrise' : 'Sunset',
      // The strip's own name for this window ("Tonight Sunset", "Sat Sunrise"), folded rather than
      // rebuilt: the footer's map action names the window it opens, and a second vocabulary for the
      // same window would make the reader translate between the button and the strip behind it.
      // Falls back to the row's own two words for a descriptor that carries no label.
      label: card.label || `${card.dow} ${card.sunrise ? 'sunrise' : 'sunset'}`,
      // ⚠️ THIS LOCATION'S own event time, not the window header's. `card.time` is the window's
      // single header time — the roster-wide earliest when the backend served one, and an
      // order-dependent first slot when it fell back to `getEventTime` — so on a sheet about one
      // place it is somebody else's clock, printed one line above a departure derived from the
      // right one. The first cut did exactly that and the two disagreed by four minutes in its own
      // fixture, with nothing on screen to explain the gap. Second choice is this location's own
      // event instant recovered from its score row (see `eventInstantOf` — the light line is built
      // from the same geometry, so without this the two could print different sunrises one line
      // apart). `card.time` survives only as the LAST resort, for a window with neither.
      time: formatTime(slot?.eventTime)
        || formatTime(card.away ? null : eventInstantOf(score, card.targetType))
        || card.time,
      away: Boolean(card.away),
      stateLabel: card.verdictLabel,
      rating,
      /**
       * This window's prose — this location's own served summary first, its REGION's gloss second.
       *
       * <p>⚠️ The fallback is not decoration; it is what makes increment §2's "strict subset" claim
       * true. The map callout has always had it ({@code MapCallout}'s own {@code reason}), and since
       * §1 the callout's clamped prose is a BUTTON into this sheet — so without the same fallback a
       * reader could click a sentence and arrive at "No read for this window yet.", losing the one
       * thing they clicked for. Found by adversarial review against the first cut.
       *
       * <p>Same order and same index as the callout's, so the two can never show different prose for
       * one window. Null on an away day, like every sibling here.
       */
      summary: card.away
        ? null
        : (score?.summary
          ?? regionGlossFor(regionGlossIndex, card.date, card.targetType, spot?.regionName)),
      // Location-sheet superset plan, Phase 1: the SAME score row rating and summary come from —
      // never a second lookup, which is P8's load-bearing rule restated for two more fields.
      fierySky: card.away ? null : (score?.fierySky ?? null),
      goldenHour: card.away ? null : (score?.goldenHour ?? null),
      // Phase 2, and from that SAME row again. Formatted here rather than in the component,
      // beside `time` above, so this sheet holds one UTC→UK rule and not two. The `card.away`
      // test mirrors its three neighbours rather than doing the work — the lookup itself is
      // already skipped for a travel day, so either gate alone suffices and neither is pinnable
      // on its own. What the tests pin is the behaviour: astronomy is true of a travel day, but
      // nothing was consulted for it, and a light window under "nothing was forecast" reads as a
      // forecast withheld rather than as a day off.
      light: card.away ? null : lightWindows(score, card.targetType),
      leave,
      // ⚠️ The LOCATION'S OWN region's confidence, never `card.confidence` (which is the top
      // region's — right for a roster-wide thumbnail, wrong for one place in another region). Null
      // on an unrated row: this channel qualifies a forecast, and there is no forecast to qualify.
      confidence: rating == null
        ? null
        : resolveConfidence({ confidence: slot?.confidence ?? null },
          daysOut(card.date, todayStr)),
      // "Nothing has looked at this window yet" is a claim about the pipeline, and an unfetched or
      // failed ratings request is no evidence for it.
      scoresKnown: Boolean(scoresKnown),
    };
  });

  // A max over one is not a comparison, so a single rated window claims no "best" — the same rule
  // the tide runs state ("a one-day run claims no peak"). Ties break earliest.
  const rated = rows.filter((row) => row.rating != null);
  const best = rated.length > 1
    ? rated.reduce((top, row) => (row.rating > top.rating ? row : top))
    : null;
  const handoff = handoffRow(rows, focusWindowKey);
  const scopeKnown = Array.isArray(scopeRegionNames) && scopeRegionNames.length > 0;

  return {
    name,
    regionName: spot?.regionName || null,
    driveMinutes,
    // ⚠️ An EMPTY scope array is unknown, not "outside everything". At home `scopeRegions` folds to
    // `areaRegions`, which is empty whenever the catalogue is — a state the sheet can be open
    // across. The badge claims a place is out of the plan; an unmeasured or unloaded planning area
    // is no evidence for it, which is the same direction `areaRegions` resolves an unmeasured
    // region in.
    outsideScope: scopeKnown
      && Boolean(spot?.regionName)
      && !scopeRegionNames.includes(spot.regionName),
    outsideLabel: outsideLabel(origin),
    rows,
    bestKey: best?.key ?? null,
    handoffKey: handoff?.key ?? null,
    lead: leadLine(rows, new Set(rows.map((row) => row.date)).size, scoresKnown),
    meta: sheetMetaFacts(location, windows),
    /**
     * The window the reader ARRIVED on, when one was named and this sheet actually has it.
     *
     * <p>Increment §1's promise is "the rest of THIS narrative", so the row carrying the prose the
     * reader clicked must be the one that opens. Distinct from {@link #bestKey}, which is a max over
     * this location's own windows and is what seeds every OTHER entry point.
     *
     * <p>Resolved against the built rows rather than passed through, so a key naming a window this
     * sheet does not contain (the map browses further than the briefing — plan D-13) degrades to the
     * ordinary seeding instead of opening nothing.
     */
    focusKey: focusWindowKey && rows.some((row) => row.key === focusWindowKey)
      ? focusWindowKey
      : null,
  };
}
