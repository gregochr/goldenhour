import { dayLabelFor, eventWord, pastDayLabel } from './windowFirstCards.js';
import { resolveConfidence, daysOut } from './confidenceUtils.js';
import { isNightOver } from './mapDates.js';
import { parseUtcInstant } from './conversions.js';
import { windowKey } from './windowKeys.js';
import { appNow } from './rewind.js';
import { bigSeasWindowAhead } from './bigSeasWindow.js';
import { heatPointsFor, POINT_SCORE_INDEX } from './heatSpots.js';

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
 * <h2>D-14 — a night that is over is not a row</h2>
 *
 * <p>The astro and aurora available-date endpoints answer with every night ever stored, and nothing
 * prunes either table. Unclipped, the list opened on that whole history: each past night labelled
 * with a bare weekday ("Sunday night" under "SUN 12", with no month to tell April from next week),
 * reading "—" because the preview fetch never covered it, and reachable by walking {@code ‹} back
 * into it. So a night row is offered only while its night is not over ({@code mapDates.isNightOver}):
 * tonight and later, plus the night in progress — yesterday's date, between UK midnight and dawn.
 * {@code resolveMapDate} judges a night the reader chose by the same answer, and so does
 * {@link isForwardableRow}. Owner decision, 2026-09-14 (map-tab-v2-plan.md §5 D-14); the comments
 * that used to call the unclipped list deliberate were describing the code, not citing a decision.
 *
 * <p>The past-dated rows this keeps — the night in progress, and the exception below — are in
 * {@code MapView}'s preview fetch as well, so they carry a best and a time like any other night row.
 *
 * <p>⚠️ <b>What it costs.</b> LITE cannot read the night in progress (aurora status is PRO/ADMIN
 * only), so for LITE yesterday's astro night is over from UK midnight, not dawn, and a LITE reader can
 * no longer reach the night still running over them — accepted by the owner, exit recorded as §6
 * O-21. And astro rows are written only by hand-started colour runs, so on a day past the last run's
 * horizon the tab offers no astro row at all (§6 O-22).
 *
 * <p>⚠️ <b>One exception: the night the map is already showing</b> keeps its row after it ends,
 * until the map leaves it, so the pill never says "No forecast" over stars still painted for it —
 * #803's shape. It is a bridge rather than a hold. A night the pane handed to {@code App} (every
 * night it will take, {@link isForwardableRow}) is moved on by {@code App}'s own clamp: in the very
 * render that clips the row at dawn for PRO/ADMIN, and at {@code App}'s next render after UK midnight
 * for LITE — within about 30 seconds while the health stream is connected, since every push re-renders
 * {@code App}. The
 * row outlasts that only for a night kept local because {@code App} would refuse it: one beyond the
 * forecast's own dates, or an ended night picked again from this list. That stays until the reader
 * leaves it or {@code App}'s date next moves. It can be stepped away from, never back to.
 *
 * <h2>Past windows — the outcome-rating series (docs/engineering/outcome-rating-plan.md §2 #2)</h2>
 *
 * <p>The forecast list serves today−2 onward, and until O3a this list refused every solar window
 * that had gone: the briefing withdraws an elapsed window, and the filler branch is gated on
 * {@code date >= todayStr}. A solar window is now a <b>past row</b> ({@code row.past === true})
 * when it has no served window, is in the forecast domain, has forecast rows ({@code
 * pastSolarWindows}) and its roster-earliest event time has elapsed by the afterglow rule
 * ({@link isPastSolarWindow}) — the same instant and thirty minutes the briefing withdraws it on, so
 * the Map and the Plan tab retire a window at the same minute. A past row is {@code served: false},
 * {@code scored: false}, forwarded to {@code App} with a provenance flag ({@link isForwardableRow}),
 * and rated from the forecast rows alone (the briefing score index serves the latest result, which
 * for a passed slot may postdate the event). {@code pastSolarWindows}, {@code pastDayLabel} and the
 * {@code past} filter are the already-licensed filter/map/select class over served facts: they
 * compute no rating and no verdict.
 *
 * <p>The heat field paints a past window too (O3c): the pane lists it in {@code heat.windows} flagged
 * {@code past: true} — which {@link buildMapEvents} and {@link solarRowPredicate} read as "not served"
 * — with a {@link pastWindowBest} client max as its {@code bestRating}, the one rating derivation this
 * adds (a new named member of the licensed client-max class, beside {@link bestOfNight}).
 *
 * <h2>Aurora is absent, not greyed, for LITE (cross-vendor review on #723)</h2>
 *
 * <p>An earlier revision of the plan promised a greyed row; it is not implemented, because
 * {@code AuroraForecastController} is role-gated at class level and the available-dates endpoint
 * folds a LITE 403 to {@code []} — so a LITE client cannot even learn a night exists to grey it out.
 * {@code isLite} therefore omits aurora rows entirely rather than rendering a disabled one.
 * The Lite reader's way in is the Pro door under the window list (`GET /api/aurora/nights`, drawn by
 * `WindowControl`/`MapPeekWindowsSection`, never an entry here): docs/engineering/aurora-pro-door-plan.md.
 */

/** The kind literals a row's {@code kind} field takes — also the CSS/colour class key. */
export const EVENT_KIND = { SOLAR: 'solar', ASTRO: 'astro', AURORA: 'aur', HW: 'hw' };

/**
 * The {@code eventType} of the daylight High-water row (hot-topics-by-day-plan §3 M3). Not a forecast
 * window: no light score, no fetch, no heat, no stand-down. Every reader that branches on an
 * {@code eventType} or a {@code kind} must give it its own answer or none at all, never the sunset's
 * and never a night's.
 */
export const HIGH_WATER = 'HIGH_WATER';

/** Whether an EV row is the daylight High-water row. */
export function isHighWaterRow(row) {
  return row?.kind === EVENT_KIND.HW;
}

/**
 * Whether a row {@code kind} is a night (astro or aurora). The High-water row is neither a night nor a
 * solar window, so a test written as {@code kind !== 'solar'} reads it wrongly; ask this instead.
 */
export function isNightKind(kind) {
  return kind === EVENT_KIND.ASTRO || kind === EVENT_KIND.AURORA;
}

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
 * A PAST solar window's {@code bestRating} for the heat field — the highest star among the points
 * the field actually PAINTS for it. outcome-rating-plan.md §2 #3 (O3c).
 *
 * <p>A <b>new named member of the licensed Map-tab client-max class</b> (CLAUDE.md Backend-heavy,
 * fifth class), beside {@link bestOfNight}, and deliberately NOT folded into the filter/map/select
 * class: it takes a max across a roster of places, a derivation, where that class only reads or
 * reshapes a figure already served. The briefing serves no window once it has gone, so no served
 * field states this figure, and {@code MapView}'s {@code windowUnscored} needs one to decide whether
 * the colour key is drawn. Nothing else here derives (the entry's {@code conf} reuses
 * {@code resolveConfidence}/{@code confidenceScalar}). CLAUDE.md's Backend-heavy bullet records
 * it as the class's third member.
 *
 * <p><b>Over the painted points, never the raw rows</b> (Codex, #1185 round 8). The population is
 * {@link heatPointsFor}'s — the spots {@code buildHeatSpots} kept (it drops an unregioned place and
 * one with no usable coordinates, and withholds the scores of a WOODLAND/BLUEBELL/WILDLIFE-only
 * site) that hold a finite rating in this window. A window whose only rated rows are a wood's, or an
 * unregioned place's, therefore reads {@code null} and the key is withheld rather than drawn over an
 * empty field. Like {@code bestOfNight} it is unfiltered by the reader's rating floor, subject chips,
 * reach and dark-sky toggle: the key asks "is there a rating behind the picture", not "what is left
 * after my filters".
 *
 * @param {Array<{scores: Array<?number>}>} spots spots from {@code buildHeatSpots}
 * @param {number} windowIndex which of the spots' windows to read (the index the point set was built at)
 * @returns {?number} the highest painted star, or null when the field would paint nothing
 */
export function pastWindowBest(spots, windowIndex) {
  let best = null;
  for (const point of heatPointsFor(spots, windowIndex)) {
    const s = point.r[POINT_SCORE_INDEX];
    if (best == null || s > best) best = s;
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
 *
 * <p>The event word is lower case, matching the served label's own non-lead form
 * ({@code windowFirstStrip.js}'s {@code `${day} ${eventWord}`}) — a filler row must not be the
 * only place on the map tab that capitalises it.
 */
function beyondBriefingLabel(date, targetType, todayStr, tomorrowStr) {
  const day = dayLabelFor(date, todayStr, tomorrowStr);
  const word = eventWord(targetType);
  return `${day} ${word}`;
}

/**
 * Strips a trailing "sunrise"/"sunset" word from a solar row's label, so the kind chip (which
 * already reads SUNRISE/SUNSET) is not repeated in the day text beside it — "Sunset · Tonight
 * Sunset 19:50" states the same fact twice in one pill. Mirrors the design bundle's `dayOnly`
 * rule (map-tab-v2.js ~:104) verbatim, including its fallback: a label that would strip to
 * nothing (one reading only "Sunset", no day at all) keeps its untouched form rather than going
 * blank.
 *
 * @param {string} label
 * @returns {string}
 */
function dayOnly(label) {
  const stripped = String(label).replace(/\s*\b(sunrise|sunset)\b\s*$/i, '').trim();
  return stripped || label;
}

/**
 * Builds one solar EV row from a served window (the shape {@code WindowFirstMapPane} builds
 * {@code heat.windows} in, enriched with {@code confidenceTier} and {@code badges}) or synthesises
 * an unscored D-13 row when the pane's rendered list carried none for this date/type. A filler may
 * still borrow the briefing's own served tide and clock time for the window ({@code lent}): the
 * briefing rolls those up for every event of its owned days, rendered or not.
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
function solarRow(
  date, targetType, served, todayStr, tomorrowStr, inForecastDomain, lent = null,
) {
  const eventType = targetType;
  if (served) {
    const label = served.label;
    return {
      id: `solar:${date}:${eventType}`,
      kind: EVENT_KIND.SOLAR,
      eventType,
      date,
      label,
      // The kind chip already says SUNRISE/SUNSET (`label` stays the full form for the pin
      // tooltip and the callout strip cell's `title`, neither of which carries a kind chip).
      dayLabel: dayOnly(label),
      time: served.time || '',
      confidence: resolveConfidence(
        { confidence: served.confidenceTier }, daysOut(date, todayStr),
      ),
      bestRating: numOrNull(served.bestRating),
      scored: numOrNull(served.bestRating) != null,
      // Whether the BRIEFING served this window, as distinct from `scored` ("is anything in it
      // rated"). The two differ for a served window nothing is rated in, and the difference is
      // load-bearing for any caller asking "which windows are still ahead of me": the briefing
      // withdraws an elapsed window (`PlanWindowProjector.hasPassed`) while the D-13 filler branch
      // below is only gated on `date >= todayStr` — so after this morning's sunrise the list still
      // leads with a filler SUNRISE row for a window hours in the past. Gate on THIS, never on list
      // position (map-landing-plan.md §3 L4 step 2). `served: false` also describes a PAST row
      // (outcome-rating-plan.md §2 #2): "which windows are still ahead" is `served` AND not `past`.
      served: true,
      // Whether the briefing marked this window a TRAVEL day. Carried, never derived — the away
      // state is `buildWindowCards`' own, and `landingRows` drops such a window rather than
      // offering it as one of "your next two" (map-landing-plan.md §3 L4).
      away: Boolean(served.away),
      badges: Array.isArray(served.badges) ? served.badges : [],
      // The forecast's own Best bet / Also good for this window — `'best'`, `'also'`, or null,
      // copied verbatim from the served window (map-landing-plan.md §3 L1). Server-owned: the
      // design bundle's client rank formula is deliberately not built (§4 #2).
      //
      // ⚠️ The window's VERDICT is deliberately not here. It is not a property of the served
      // window on this tab — it is a property of the reader's current scope segment, and it lives
      // in `utils/mapVerdict.js`, read per row in `MapView`. Carrying the served word here as well
      // would put two answers for one window in the reader's hands, disagreeing whenever "My area"
      // narrows; `WindowFirstMapPane`'s own mapper records the decision at length.
      pickKind: served.pickKind ?? null,
      // ⚠️ Carried WITH the kind, never separately (map-landing-plan.md §3 L6). A medallion whose
      // subject is a region must be able to test the pick's own region; one whose subject is the
      // window ignores this and reads the kind alone.
      pickRegion: served.pickRegion ?? null,
      // The served `BriefingWindowTide` for this window (tide-window-plan.md T3, §1 #4/#12) —
      // forwarded verbatim, never re-derived: the strip's chart, header and next-fit scan all read
      // this object's own fields (`curve`, `windowPosition`, `windowLevel`, `extremes`,
      // `locationName`…), and a solar window the briefing served but with no coastal representative
      // carries no `tide` at all, hence the fallback.
      tide: served.tide ?? null,
      inForecastDomain,
    };
  }
  const label = beyondBriefingLabel(date, targetType, todayStr, tomorrowStr);
  return {
    id: `solar:${date}:${eventType}`,
    kind: EVENT_KIND.SOLAR,
    eventType,
    date,
    label,
    dayLabel: dayOnly(label),
    // The served clock time lent with the tide, when there is one — never synthesised here.
    time: lent?.time || '',
    confidence: resolveConfidence(null, daysOut(date, todayStr)),
    bestRating: null,
    scored: false,
    // A D-13 filler: the pane's rendered list carried no window for this date and event, so nothing
    // here has been through the elapsed test (the briefing may still have served one — see `tide`).
    // See the served branch above.
    served: false,
    // A filler row is a date the pane's rendered list carried no window for, so it carries no away
    // state either — the travel ranges the pane knows about never reach this list.
    away: false,
    badges: [],
    // A D-13 filler row has no rendered window, so there is no pick. Null
    // rather than absent, so every solar row has one shape.
    //
    // ⚠️ A filler row is NOT pastness-filtered the way a served one is. The briefing withdraws an
    // elapsed window (`PlanWindowProjector.hasPassed`), but `forecastDates` still carries today, so
    // after this morning's sunrise a filler SUNRISE row is emitted for a window hours in the past
    // — the `date >= todayStr` gate above only excludes YESTERDAY. (Since the outcome-rating series
    // such a window becomes a `past` row instead whenever forecast rows with an event time exist for
    // it — `pastSolarRow`; a filler is now the elapsed window with NO such rows, or no time to
    // judge it by, and the future window the briefing has not served.) A caller wanting "the next N
    // windows" must therefore gate on `served`, never on list position alone
    // (map-landing-plan.md §3 L4 step 2). The one thing that IS pastness-filtered is the lent tide
    // and time: `lendFor` withholds both once the window has elapsed (`isEventTimePast`).
    pickKind: null,
    // Null rather than absent, with `pickKind` — the invariant the comment above states is that
    // every solar row has ONE shape, and L6 added this field to the served branch alone. A
    // completeness sweep caught the omission; it is harmless today only because every reader
    // short-circuits on the kind first.
    pickRegion: null,
    // A D-13 filler has no window in the pane's list, but the briefing may still have served one:
    // it rolls a tide up for every event of its four owned days while the pane lists only the six
    // it renders. `lent.tide` is that served rollup, forwarded verbatim; null where the briefing
    // carried none (past its owned days, or no drawable tide), and the tide is then never
    // synthesised. The row stays unscored and `served: false` — the tide and its clock time are all
    // a filler borrows; per-spot fit comes from the slots' own served tide facts, as for any row.
    tide: lent?.tide ?? null,
    inForecastDomain,
  };
}

/**
 * The daylight High-water row for one served big-seas day (hot-topics-by-day-plan §3 M3): inserted
 * between that date's sunrise and sunset. Not a window the forecast scores, so it is {@code served:
 * false}, {@code scored: false} and carries no badges, pick, tide or verdict.
 *
 * <p>{@code inForecastDomain} is REQUIRED: {@link isForwardableRow} rejects a row without it, so
 * selecting the row would skip {@code onSelectDate} and leave the parent date out of step with the
 * Map pane. The caller passes the date's own solar rows' value.
 *
 * @param {object} day the served {@code BigSeasDay} ({@code daylightHighWater} true)
 * @param {string} todayStr the UK civil today
 * @param {string} tomorrowStr the UK civil tomorrow
 * @param {boolean} inForecastDomain the date's forecast-domain flag
 * @param {(v: ?string) => ?string} formatTimeUk UTC-naive to UK 'HH:MM'
 * @returns {object} the EV row
 */
function highWaterRow(day, todayStr, tomorrowStr, inForecastDomain, formatTimeUk) {
  const dayLabel = dayLabelFor(day.date, todayStr, tomorrowStr);
  return {
    id: `hw:${day.date}:${HIGH_WATER}`,
    kind: EVENT_KIND.HW,
    eventType: HIGH_WATER,
    date: day.date,
    time: formatTimeUk(day.hwTime) || '',
    label: `${dayLabel} high water`,
    dayLabel,
    badges: [],
    bestRating: null,
    scored: false,
    served: false,
    away: false,
    pickKind: null,
    pickRegion: null,
    tide: null,
    inForecastDomain,
    // The served instants the expiry timer and the strip's tick read, forwarded verbatim.
    windowEnd: day.windowEnd ?? null,
    hwTime: day.hwTime ?? null,
    daylightStart: day.daylightStart ?? null,
    daylightEnd: day.daylightEnd ?? null,
  };
}

/**
 * The past-window inputs {@link buildMapEvents} and {@link solarRowPredicate} take
 * (outcome-rating-plan.md §2 #2): every (date, targetType) the locations hold at least one forecast
 * row for with {@code date <= todayStr}, each carrying {@code earliestEventTime} — the earliest
 * {@code solarEventTime} among its rows (UTC-naive, as served), the instant that decides whether the
 * window has elapsed. Null when no row of the window carries a parseable time: the window is then a
 * past row only on a date before today, and today's own stays a filler (the elapsed test needs a
 * time, and nothing stands in for one).
 *
 * <p>A filter/min over served rows — the already-licensed filter/map/select class (CLAUDE.md
 * Backend-heavy), the same earliest-served-instant {@code mapScopedTime.withScopedSolarTimes} takes
 * over a scope pool, here over the whole roster because the elapsed test is the briefing's own
 * (roster-earliest, {@code PlanWindowProjector.hasPassed}). It reads no rating. Chronological,
 * sunrise before sunset.
 *
 * @param {Array<{forecastsByDate: ?Map<string, {sunrise: ?object, sunset: ?object}>}>} locations
 * @param {string} todayStr the UK civil today
 * @returns {Array<{date: string, targetType: 'SUNRISE'|'SUNSET', earliestEventTime: ?string}>}
 */
export function buildPastSolarWindows(locations, todayStr) {
  const byKey = new Map();
  for (const loc of Array.isArray(locations) ? locations : []) {
    const days = loc?.forecastsByDate;
    if (!(days instanceof Map)) continue;
    for (const [date, entry] of days) {
      if (!date || date > todayStr) continue;
      for (const [slot, targetType] of [['sunrise', 'SUNRISE'], ['sunset', 'SUNSET']]) {
        const row = entry?.[slot];
        if (!row) continue;
        const key = windowKey(date, targetType);
        const known = byKey.get(key) ?? { date, targetType, earliestEventTime: null };
        const raw = row.solarEventTime ?? null;
        const at = parseUtcInstant(raw);
        if (at && (known.earliestEventTime == null
          || at < parseUtcInstant(known.earliestEventTime))) {
          known.earliestEventTime = raw;
        }
        byKey.set(key, known);
      }
    }
  }
  const order = (w) => `${w.date}${w.targetType === 'SUNRISE' ? 0 : 1}`;
  return Array.from(byKey.values()).sort((a, b) => (order(a) < order(b) ? -1 : 1));
}

/**
 * Builds one PAST solar EV row (outcome-rating-plan.md §2 #2): a window the briefing no longer
 * serves, whose roster-earliest event time has elapsed, drawn from the forecast rows the pane found
 * for it. It is {@code served: false} and {@code scored: false} — the briefing holds nothing for it
 * and its menu figure is not a thing this list states — and carries {@code past: true}, which is what
 * every consumer keys on (the pill's PAST tag, the callout's facts, the selection plumbing, the
 * rating accessor's forecast-row-only read).
 *
 * <p>{@code time} is the window's own earliest event time through {@code formatTimeUk}, a base that
 * exists even with no briefing and an empty scope pool; {@code withScopedSolarTimes} then narrows it
 * to the scope pool's earliest as it does for a served row. Left blank when no row carried a time,
 * never guessed.
 */
function pastSolarRow(date, targetType, pastWindow, todayStr, formatTimeUk) {
  const dayLabel = pastDayLabel(date, todayStr);
  return {
    id: `solar:${date}:${targetType}`,
    kind: EVENT_KIND.SOLAR,
    eventType: targetType,
    date,
    past: true,
    label: `${dayLabel} ${eventWord(targetType)}`,
    dayLabel,
    time: (pastWindow?.earliestEventTime ? formatTimeUk(pastWindow.earliestEventTime) : null) || '',
    // A past window carries no served confidence; the inferred, capped tier is the honest answer
    // (outcome-rating-plan.md §4 #5).
    confidence: resolveConfidence(null, daysOut(date, todayStr)),
    bestRating: null,
    scored: false,
    served: false,
    away: false,
    badges: [],
    pickKind: null,
    pickRegion: null,
    tide: null,
    inForecastDomain: true,
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
  const label = nightLabel(date, todayStr, tomorrowStr);
  return {
    id: `${kind}:${date}:${eventType}`,
    kind,
    eventType,
    date,
    label,
    // A night row carries no event word to repeat ("Tonight", "Saturday night") — the two fields
    // are always the same string, unlike a solar row's day-only stripped form.
    dayLabel: label,
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
 * outside the horizon that is not over — one beyond its far end, or the night in progress just
 * before its near end — still gets a real EV row (`buildMapEvents` does not consult this function
 * at all; its own clip is D-14's, in the module doc) and, once actually SELECTED, still gets its own
 * dedicated single-date fetch regardless of range (`MapView.jsx`'s `nightDate`-keyed effects) —
 * only the PREVIEW fetch is capped.
 *
 * @param {object} args
 * @param {Array<{date: string}>} [args.solarWindows] served solar windows (`heat.windows`)
 * @param {string[]} [args.forecastDates] every date with a sunrise or sunset row (`colourForecastDates`)
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
 * The nights of one kind {@code MapView}'s preview fetch covers — every stored night on the
 * {@link solarHorizonDates} horizon, plus the past-dated nights D-14 still offers as rows, which
 * that horizon cannot hold because it starts at today: the night in progress (yesterday's date
 * until dawn), and the night on screen once it has ended. Both are decided by
 * {@link isNightOffered}, the list's own rule, so the preview covers exactly the past rows the list
 * offers. Left out, those rows read "—" with no time even while selected: a selected night's own
 * fetch feeds the map's paint and the callout headline, never the list, the callout strip or the
 * Regions jump, which all read the preview.
 *
 * <p>At most one date per kind is added to the horizon's own, so PR #731's fan-out bound holds.
 *
 * @param {'ASTRO'|'AURORA'} eventType the kind these dates are for
 * @param {string[]} availableDates every night of that kind ever stored
 * @param {object} args
 * @param {string[]} args.horizonDates {@link solarHorizonDates}' result
 * @param {string} args.todayStr the UK civil today
 * @param {?string} [args.currentNightDate] the night in progress (`mapDates.resolveAuroraNight`)
 * @param {?string} [args.endedNightOnScreen] the date of the night of THIS kind the map is showing,
 *   only while that night is over — a derived key rather than the raw night on screen, because the
 *   caller memoises on it and a raw one would change on every step between two current nights
 * @returns {string[]} the dates to fetch, in `availableDates`' order
 */
export function nightPreviewDates(eventType, availableDates, {
  horizonDates, todayStr, currentNightDate = null, endedNightOnScreen = null,
}) {
  const nightOnScreen = endedNightOnScreen ? { eventType, date: endedNightOnScreen } : null;
  return availableDates.filter((d) => horizonDates.includes(d)
    || (d < todayStr && isNightOffered(eventType, d, { todayStr, currentNightDate, nightOnScreen })));
}

/**
 * Whether the EV list carries a SOLAR row for one (date, targetType) — <b>the</b> rule, called
 * once by {@link buildMapEvents} for each of a date's two solar events and once per lookup by
 * {@link solarRowPredicate}, so the two can never answer differently.
 *
 * <p>A served window is a row unconditionally: the briefing only ever renders current/future
 * events, so its presence is already evidence the date belongs on screen. A D-13 filler row
 * additionally requires {@code date >= todayStr} — see the call site's own comment for the
 * just-after-midnight case that gate exists for. A PAST window (a date before today with forecast
 * rows for that event, {@code pastWindow}) is a row too; today's own is already a row by the filler
 * clause, so no clock enters here.
 *
 * @param {*} servedWindow the served window for this (date, targetType), if any
 * @param {boolean} inForecastDomain whether `GET /api/forecast` returned this date
 * @param {string} date
 * @param {string} todayStr the caller's UK-civil today
 * @returns {boolean}
 */
function hasSolarRow(servedWindow, inForecastDomain, date, todayStr, pastWindow = null) {
  return Boolean(servedWindow)
    || (inForecastDomain && date >= todayStr)
    // A PAST window (outcome-rating-plan.md §2 #2): a date before today whose forecast rows exist.
    // Today's own is already a row by the clause above — filler until it elapses, past after — so
    // this clause needs no clock, and `isPastSolarWindow` is where the elapsed test lives.
    || (inForecastDomain && date < todayStr && Boolean(pastWindow));
}

/**
 * Whether one solar window is a PAST row — outcome-rating-plan.md §2 #2, the Map tab's answer to
 * "has this window gone, and are there forecast rows to show for it".
 *
 * <p>All four must hold: the briefing serves NO window for it, it is in the forecast domain
 * ({@code GET /api/forecast} returned its date), forecast rows exist for it ({@code pastWindow},
 * built by {@code WindowFirstMapPane} from {@code forecastsByDate}), and its roster-earliest event
 * time has elapsed by the afterglow rule ({@code isEventTimePast}) — the same instant and the same
 * thirty minutes {@code PlanWindowProjector.hasPassed} withdraws the served window on, so with a
 * live briefing the two conditions flip together and the Map and the Plan tab retire a window at
 * the same minute. ⚠️ The elapsed test is load-bearing on its own: an unserved window is not
 * necessarily over (the briefing can be absent or stale, and the D-13 filler exists to represent
 * in-domain windows it does not serve), so without it a future sunset would wear PAST whenever the
 * briefing lagged (Codex, #1185 round 6). A date before today passes trivially.
 *
 * <p>A filter over served facts — the already-licensed filter/map/select class (CLAUDE.md
 * Backend-heavy); it computes no rating and no verdict.
 *
 * @param {object} args
 * @param {*} [args.servedWindow] the served window for this (date, targetType), if any
 * @param {?{earliestEventTime: ?string}} [args.pastWindow] the pane's past-window entry, if any
 * @param {boolean} args.inForecastDomain whether `GET /api/forecast` returned this date
 * @param {string} args.date
 * @param {string} args.todayStr the UK civil today
 * @param {(eventTime: ?string) => boolean} [args.isEventTimePast] the app clock's elapsed test
 * @returns {boolean}
 */
export function isPastSolarWindow({
  servedWindow = null, pastWindow = null, inForecastDomain, date, todayStr,
  isEventTimePast = () => false,
}) {
  if (servedWindow || !inForecastDomain || !pastWindow || date > todayStr) return false;
  return date < todayStr || isEventTimePast(pastWindow.earliestEventTime ?? null);
}

/**
 * A reusable {@code (date, targetType) => boolean} answering "does the EV list have a solar row
 * for this window", over a prebuilt index of the same inputs {@link buildMapEvents} takes.
 *
 * <p><b>Why this is exported rather than reasoned about at the call site.</b> A window with no EV
 * row is not a window the reader can be shown an answer for: {@code WindowControl} says
 * <em>"No forecast"</em>, the heat field paints nothing, the legend key is withheld and
 * {@code MapCallout} refuses to mount. But {@code MapView}'s rating accessor reads two indexes
 * keyed by an arbitrary {@code date} — the briefing score index and each location's
 * {@code forecastsByDate} — and both legitimately carry rows for dates the EV list excludes
 * (`GET /api/forecast` serves {@code today-2} onward, and `GET /api/briefing/evaluate/scores`
 * the same). Left ungated, the star chips were the only thing on the tab still answering for a
 * window every other surface had gone quiet about, and they answered with whatever run last
 * scored that date. Observed in production on 2026-09-07: a map with no field, no colour key and
 * a pill reading "No forecast", captioned <em>133 of 253 shown · 130 rated</em>, every chip
 * carrying a 4★ from a run two days old.
 *
 * <p>The predicate is built once per input change and closed over two {@code Set}s, because its
 * caller asks it per location — a few hundred times per render.
 *
 * <p>⚠️ <b>An EMPTY domain answers {@code true}, because unknown is not "no".</b> With neither
 * input supplied this function has been told nothing about which SOLAR windows exist, and a
 * predicate with no evidence must not suppress. The state it exists for is the opposite one: a
 * list that HAS rows, none of them this window — the same distinction {@code WindowControl} draws
 * for itself between an empty list ("Genuinely nothing to show", where it renders no pill at all)
 * and {@code !active}. Suppressing on an empty domain would instead blank every rating on any
 * mount not handed the two props, which is a different and much larger behaviour change.
 *
 * <p>⚠️ Do not read the empty case as "therefore the EV list is empty too". {@link buildMapEvents}
 * also emits NIGHT rows from the astro/aurora available-date lists, which arrive on their own
 * fetches and owe nothing to either input here — so an empty solar domain does not imply an empty
 * control. The clause's safety rests on the two production mounts named below, not on that.
 *
 * <p>⚠️ It <b>never fires on the Map tab</b> — that tab is gated on {@code allDates.length > 0}
 * and {@code WindowFirstMapPane} always forwards that list — so it is not the part doing the work
 * there. It DOES fire on every Plan-tab overlay render, since that mount is handed neither
 * {@code heat} nor {@code forecastDates} by design; but the overlay is separately and
 * deliberately exempted by its caller, on the ground that it builds no EV list at all. Do not
 * collapse the two: this clause reads an absent input honestly, while the caller's exemption
 * states the actual reason — and a later change that hands the overlay a domain must not be able
 * to blank it.
 *
 * <p>⚠️ <b>Solar only — {@code served} holds no night keys</b>, so a night {@code targetType} could
 * only ever reach the domain arm: a night judged by the solar domain. Rather than ask every caller
 * to remember that, {@code MapView} enforces the scope at its own boundary and answers {@code true}
 * for any non-solar event before reaching this function at all.
 *
 * <p>⚠️ <b>Nothing here protects the night paths.</b> Astro scores and STORED aurora results are
 * fetched per {@code nightDate}, so they answer for the night on screen. The aurora branch also
 * falls back to {@code auroraScores}, fetched by {@code getAuroraLocations()} — <b>no date parameter
 * at all</b>, gated only on a live alert level — so that cache could answer for a night the reader
 * was not looking at: this same class of defect. {@code MapView} closed it in #814 by gating every
 * read of the cache on {@code liveAuroraOnScreen}; nothing HERE does, and it is named so the "solar
 * only" scope above is not mistaken for a claim that this function covers the night paths.
 *
 * @param {object} args
 * @param {Array<{date: string, targetType: string}>} [args.solarWindows] served solar windows
 *   (`heat.windows`)
 * @param {string[]} [args.forecastDates] every date with a sunrise or sunset row (`colourForecastDates`)
 * @param {Array<{date: string, targetType: string}>} [args.pastSolarWindows] the pane's past
 *   windows (outcome-rating-plan.md §2 #2) — each makes a date before today a row
 * @param {string} args.todayStr the UK civil today
 * @returns {(date: string, targetType: string) => boolean}
 */
export function solarRowPredicate({
  solarWindows = [], forecastDates = [], pastSolarWindows = [], todayStr,
}) {
  const served = new Set();
  for (const w of solarWindows) {
    if (w?.date && w?.targetType && !w.past) served.add(`${w.date}:${w.targetType}`);
  }
  const past = new Set();
  for (const w of pastSolarWindows) {
    if (w?.date && w?.targetType) past.add(windowKey(w.date, w.targetType));
  }
  const domain = new Set(forecastDates);
  const domainKnown = served.size > 0 || domain.size > 0;
  return (date, targetType) => !domainKnown || hasSolarRow(
    served.has(`${date}:${targetType}`), domain.has(date), date, todayStr,
    past.has(windowKey(date, targetType)) ? { date, targetType } : null,
  );
}

/**
 * Whether a stored night becomes an EV row — D-14's clip and its one exception (module doc), in one
 * place, because two things must agree on it: {@link buildMapEvents}, which offers the rows, and
 * {@code MapView}'s preview fetch, which must cover every past-dated one or that row reads "—".
 *
 * @param {'ASTRO'|'AURORA'} eventType the night's kind
 * @param {string} date the night, named by the date its dusk falls on
 * @param {object} args
 * @param {string} args.todayStr the UK civil today
 * @param {?string} [args.currentNightDate] the night in progress (`mapDates.resolveAuroraNight`)
 * @param {?{eventType: string, date: string}} [args.nightOnScreen] the night the map is showing
 * @returns {boolean}
 */
export function isNightOffered(eventType, date, { todayStr, currentNightDate = null, nightOnScreen = null }) {
  return !isNightOver(date, { todayStr, nightDate: currentNightDate })
    || (nightOnScreen?.eventType === eventType && nightOnScreen?.date === date);
}

/**
 * Builds the Map tab's single chronological EV list.
 *
 * @param {object} args
 * @param {Array<{date: string, targetType: 'SUNRISE'|'SUNSET', label: string, time: string,
 *   bestRating: ?number, confidenceTier: ?string, badges: ?Array}>} args.solarWindows the served
 *   solar windows — `WindowFirstMapPane`'s `heat.windows`, chronological
 * @param {string[]} args.forecastDates every date with a sunrise or sunset row (`colourForecastDates`) (`allDates`),
 *   sorted — the map's own full browsable domain (D-13) and the EV-ownership forwarding test
 * @param {string} args.todayStr today's UK calendar date
 * @param {string} args.tomorrowStr tomorrow's UK calendar date
 * @param {?string} [args.currentNightDate] the night in progress (`mapDates.resolveAuroraNight`),
 *   which keeps yesterday's night a row until dawn. Omitted or null, a night is judged by the
 *   calendar alone — D-14's degrade, in the module doc
 * @param {?{eventType: string, date: string}} [args.nightOnScreen] the night the map is showing, if
 *   it is showing one — offered as a row even once it is over (D-14's one exception)
 * @param {string[]} [args.astroAvailableDates] dates with stored astro conditions — EVERY night
 *   ever stored; the ones that are over are dropped here
 * @param {Map<string, Array>} [args.astroConditionsByDate] date → that night's astro condition rows
 * @param {string[]} [args.auroraAvailableDates] dates with stored aurora forecast results
 * @param {Map<string, Array>} [args.auroraResultsByDate] date → that night's aurora result rows
 * @param {boolean} [args.isLite] true for a LITE account — aurora rows are omitted outright
 * @param {?Map<string, {tide: object, eventTime: ?string, time: ?string}>} [args.tideByWindow]
 *   the briefing's served tide (with the window's event instant and its formatted clock time) per
 *   {@link windowKey}, lent to a D-13 filler row — a window the briefing carries but the
 *   pane's rendered-six list does not. Omitted, a filler has no tide
 * @param {(eventTime: ?string) => boolean} [args.isEventTimePast] the elapsed test (the app clock's,
 *   `briefingDisplay.isEventTimePast`), injected so this module reads no clock. A filler whose lent
 *   window has elapsed borrows nothing: the briefing withdraws an elapsed window and so must this.
 *   Defaults to never past
 * @param {Array<{date: string, targetType: 'SUNRISE'|'SUNSET', earliestEventTime: ?string}>}
 *   [args.pastSolarWindows] every (date, targetType) with at least one forecast row and
 *   {@code date <= todayStr}, as {@code WindowFirstMapPane} builds them from {@code forecastsByDate}:
 *   {@code earliestEventTime} is the earliest {@code solarEventTime} among the rows (UTC-naive), the
 *   instant that decides {@code past}. A window with no served window, in the forecast domain, whose
 *   earliest time has elapsed becomes a {@code past: true} row (module doc)
 * @param {(v: ?string) => ?string} [args.formatTimeUk] UTC-naive → UK 'HH:MM' formatter, injected
 *   so this module makes no `Intl`/timezone assumption of its own (defaults to a passthrough that
 *   renders no clock time, which is a safe empty state rather than a wrong one)
 * @param {?Array<{date: string, alignedEvent: ?('SUNRISE'|'SUNSET')}>} [args.bigSeas] the served
 *   {@code briefing.bigSeas} (big-seas plan F1). Filter/select only: the solar row a day's
 *   {@code alignedEvent} names is stamped {@code bigSeasAligned: true} (the week menu's ⚡); nothing
 *   is computed from the sea state; a day whose {@code windowEnd} is not after {@code now} stamps nothing.
 *   A day flagged {@code daylightHighWater} (and still ahead) also gets one {@code EVENT_KIND.HW} row
 *   between that date's sunrise and sunset (hot-topics-by-day-plan §3 M3)
 * @param {Date} [args.now] the app clock for that test, {@code appNow()} by default
 * @returns {Array<object>} the EV rows, chronological, night-after-sunset
 */
export function buildMapEvents({
  solarWindows = [],
  forecastDates = [],
  todayStr,
  tomorrowStr,
  currentNightDate = null,
  nightOnScreen = null,
  astroAvailableDates = [],
  astroConditionsByDate = new Map(),
  auroraAvailableDates = [],
  auroraResultsByDate = new Map(),
  isLite = false,
  formatTimeUk = () => null,
  tideByWindow = new Map(),
  isEventTimePast = () => false,
  pastSolarWindows = [],
  bigSeas = null,
  now = appNow(),
}) {
  const forecastDateSet = new Set(forecastDates);
  const pastByKey = new Map();
  for (const w of Array.isArray(pastSolarWindows) ? pastSolarWindows : []) {
    if (w?.date && w?.targetType) pastByKey.set(windowKey(w.date, w.targetType), w);
  }
  const tides = tideByWindow instanceof Map ? tideByWindow : new Map();
  const lendFor = (date, type) => {
    const entry = tides.get(windowKey(date, type));
    return entry && !isEventTimePast(entry.eventTime) ? entry : null;
  };
  const solarByDate = new Map();
  for (const w of solarWindows) {
    // A window flagged `past` is not SERVED (outcome-rating-plan.md §2 #3: the pane may list past windows
    // in `heat.windows` for the field) — "served" means the briefing rendered it.
    if (!w?.date || !w?.targetType || w.past) continue;
    const entry = solarByDate.get(w.date) || {};
    entry[w.targetType] = w;
    solarByDate.set(w.date, entry);
  }

  // D-14: both lists are every night ever stored, so this is where the history is dropped — before
  // the date set is built, so a past date contributes nothing at all rather than an empty day group.
  const offered = { todayStr, currentNightDate, nightOnScreen };
  const astroDates = new Set(astroAvailableDates.filter((d) => isNightOffered('ASTRO', d, offered)));
  const auroraDates = new Set((isLite ? [] : auroraAvailableDates)
    .filter((d) => isNightOffered('AURORA', d, offered)));

  const allDatesSet = new Set([
    ...forecastDates,
    ...solarByDate.keys(),
    ...astroDates,
    ...auroraDates,
  ]);
  const orderedDates = Array.from(allDatesSet).sort();

  // The served days that get a daylight High-water row: the flag, and a window still ahead (the line's
  // own test, `bigSeasWindowAhead`). One per date.
  const hwDayByDate = new Map();
  for (const day of Array.isArray(bigSeas) ? bigSeas : []) {
    if (day?.date && day.daylightHighWater === true && bigSeasWindowAhead(day, now)
      && !hwDayByDate.has(day.date)) {
      hwDayByDate.set(day.date, day);
    }
  }

  const rows = [];
  for (const date of orderedDates) {
    const served = solarByDate.get(date) || {};
    const inForecastDomain = forecastDateSet.has(date);
    const dateStart = rows.length;

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
    //
    // ⚠️ A PAST window (outcome-rating-plan.md §2 #2) is a third kind of unserved row: forecast rows
    // exist for it and its earliest event time has elapsed by the afterglow rule
    // (`isPastSolarWindow`), so it is the elapsed twin of the filler — same `served: false`, but
    // `past: true` and a label in the past vocabulary. It never borrows a lent tide or time: both
    // are withheld for an elapsed window by `lendFor` already.
    for (const type of ['SUNRISE', 'SUNSET']) {
      if (type === 'SUNSET' && hwDayByDate.has(date)) {
        // The daylight High-water row sits between the date's sunrise and sunset, and inherits the
        // domain of the date's own solar rows (computed as `solarRow` does when it has none).
        const sibling = rows.slice(dateStart).find((r) => r.kind === EVENT_KIND.SOLAR);
        const hwDomain = sibling
          ? sibling.inForecastDomain
          : inForecastDomain || Boolean(served.SUNRISE) || Boolean(served.SUNSET);
        // A date only a night contributed (outside the forecast domain, no solar window) has no day to
        // hang a High-water row on: `App` would refuse the date, so the row is not offered at all.
        if (hwDomain) {
          rows.push(highWaterRow(hwDayByDate.get(date), todayStr, tomorrowStr, hwDomain, formatTimeUk));
        }
      }
      const pastWindow = pastByKey.get(windowKey(date, type)) ?? null;
      if (!hasSolarRow(served[type], inForecastDomain, date, todayStr, pastWindow)) continue;
      if (isPastSolarWindow({
        servedWindow: served[type], pastWindow, inForecastDomain, date, todayStr, isEventTimePast,
      })) {
        rows.push(pastSolarRow(date, type, pastWindow, todayStr, formatTimeUk));
        continue;
      }
      const inDomain = inForecastDomain || Boolean(served[type]);
      rows.push(solarRow(
        date, type, served[type], todayStr, tomorrowStr, inDomain, lendFor(date, type),
      ));
    }

    // Night — after that day's sunset (README "The window control").
    if (astroDates.has(date)) {
      const nightRows = astroConditionsByDate.get(date) || [];
      const row = nightRow(EVENT_KIND.ASTRO, date, nightRows, todayStr, tomorrowStr, inForecastDomain);
      row.time = formatNightTime(row.nightStart, formatTimeUk);
      rows.push(row);
    }
    if (auroraDates.has(date)) {
      const nightRows = auroraResultsByDate.get(date) || [];
      const row = nightRow(EVENT_KIND.AURORA, date, nightRows, todayStr, tomorrowStr, inForecastDomain);
      row.time = formatNightTime(row.nightStart, formatTimeUk);
      rows.push(row);
    }
  }
  stampBigSeasAligned(rows, bigSeas, now);
  return rows;
}

/**
 * Stamps {@code bigSeasAligned: true} on the solar row each served big-seas day's
 * {@code alignedEvent} names (big-seas plan F1). A PAST row is never stamped: its window is over.
 *
 * @param {Array<object>} rows the EV rows, mutated in place (they are this call's own fresh objects)
 * @param {Date} now the app clock: a day whose window has ended stamps nothing, as the line shows nothing
 * @param {?Array<object>} bigSeas the served days, or nullish
 */
function stampBigSeasAligned(rows, bigSeas, now) {
  if (!Array.isArray(bigSeas) || bigSeas.length === 0) return;
  const keys = new Set(bigSeas
    .filter((d) => d?.date && d?.alignedEvent && bigSeasWindowAhead(d, now))
    .map((d) => windowKey(d.date, d.alignedEvent)));
  for (const row of rows) {
    if (row.kind === EVENT_KIND.SOLAR && !row.past && keys.has(windowKey(row.date, row.eventType))) {
      row.bigSeasAligned = true;
    }
  }
}

/**
 * Whether picking this row hands its date to {@code App} (forwarded) or keeps it in the pane (kept
 * local) — the EV-ownership forwarding rule of map-tab-v2-plan.md §3 P6.
 *
 * <p><b>It is {@code App}'s acceptance rule, stated on a row.</b> {@code mapDates.resolveMapDate}
 * takes a date only if the forecast returned it and it is not over: the calendar decides for a solar
 * pick, {@code mapDates.isNightOver} for a night pick. So a row is forwarded exactly when {@code App}
 * will take it, and every other row is kept local, where the pane's own astro/aurora fetches and
 * viewline gate still land on it. A forward {@code App} refuses is #803's "row that goes nowhere":
 * the pane dropped its local night and asked for a date the parent then declined.
 *
 * <p><b>D-14 moved the night arm from the calendar to the night test</b> (2026-09-14). On the
 * calendar ({@code date >= today}) the night in progress — yesterday's date until dawn — was kept
 * local although {@code App} would take it, so one night ended two ways: picked at 23:59 it was
 * forwarded and {@code App} moved the map on at dawn; picked at 00:01 it stayed on screen, ended,
 * until the reader left it. Forwarded, it ends at dawn like any other night, through {@code App}'s
 * clamp — the route the aurora banner and the auto-jump already took.
 *
 * @param {object} row an EV row built by {@link buildMapEvents}
 * @param {object} args
 * @param {string} args.todayStr the UK civil today, as {@code App} reads it
 * @param {?string} [args.currentNightDate] the night in progress, as {@code App} reads it
 * @returns {boolean}
 */
export function isForwardableRow(row, { todayStr, currentNightDate = null }) {
  if (!row?.inForecastDomain) return false;
  // The High-water row is dated like a solar window (a date, never a night), so it takes the calendar
  // test; the night test would pass a future date only by accident.
  return row.kind === EVENT_KIND.SOLAR || row.kind === EVENT_KIND.HW
    // A PAST solar row is forwarded with a provenance flag (`App.selectDate`'s `isPast`), exactly as
    // a night is: `resolveMapDate` refuses a date before today without it, so the flag, never the
    // value, is what keeps a stale past pick from holding the map after a reload.
    ? row.date >= todayStr || row.past === true
    : !isNightOver(row.date, { todayStr, nightDate: currentNightDate });
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
