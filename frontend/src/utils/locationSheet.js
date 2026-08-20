import { daysOut, resolveConfidence } from './confidenceUtils.js';
import { formatDriveDuration, formatTime } from './briefingDisplay.js';
import { GLANCE_MINUTES } from './planningArea.js';
import { leaveByParts } from './leaveBy.js';

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
 * Each location's rating and "why" per window, from the RAW score rows.
 *
 * <p>Not the provider's {@code scoreIndex} — see the module comment for the defect that cost.
 * {@code rating} is bounded here rather than at the render, mirroring {@code buildWindowSpots}: a
 * value outside 1–5 would otherwise reach {@code spotBadgeStyle}, which clamps, and paint a badge
 * for a rating the pipeline never produced.
 *
 * @param {Array} scoreRows raw {@code LocationEvaluationView} rows
 * @returns {{byId: Map<string, object>, byName: Map<string, object>}} valued
 *          {@code {rating, summary}}
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
    index(byId, byName, row.locationId, row.locationName,
      tailOf(row.date, row.targetType), { rating, summary });
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
 */
function shortDow(dateStr) {
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
function handoffRow(rows) {
  if (rows.length === 0) return null;
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
 * @param {?object} origin the origin descriptor, or null for home
 * @returns {string} the badge's words
 */
function outsideLabel(origin) {
  return origin
    ? `outside ${origin.name}`
    : `outside your ${formatDriveDuration(GLANCE_MINUTES)} area`;
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
 * @returns {object} the sheet's content
 */
export function buildLocationSheet(spot, windows, {
  scoreIndex = null, slotIndex = null, scoresKnown = false, reachById = null,
  scopeRegionNames = null, origin = null, todayStr = '',
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
      // fixture, with nothing on screen to explain the gap. `card.time` survives only as the
      // fallback for a window the briefing carries no slot for.
      time: formatTime(slot?.eventTime) || card.time,
      away: Boolean(card.away),
      stateLabel: card.verdictLabel,
      rating,
      summary: card.away ? null : (score?.summary ?? null),
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
  const handoff = handoffRow(rows);
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
  };
}
