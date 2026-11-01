import { dayLabelFor, eventWord, SLOT_VERDICT_LABEL } from './windowFirstCards.js';
import {
  buildSlotIndex, buildTideAlignmentIndex, lookupForWindow, shortDow, slotsOf,
} from './locationSheet.js';
import { tideAccessibleClause, tierOf } from './mapTideFit.js';
import { formatDriveDuration } from './briefingDisplay.js';
import { formatEventTimeUk } from './conversions.js';
import { nightLabel } from './mapEvents.js';
import { isNightType, NIGHT_TYPE, nightRowFor } from './askNights.js';
import { windowKey } from './windowKeys.js';

/**
 * Ask PhotoCast's client model — pure, no React, no clock.
 *
 * <h2>Everything here is filter / map / select over served facts</h2>
 *
 * <p>CLAUDE.md's Backend-heavy rule applies in full and this module is inside its already-licensed
 * class (the same one {@code utils/comingUpFeed.js} and {@code utils/hourlyComfort.js} sit in), not
 * a new one: nothing ranks, counts a population or decides a verdict. What it does do, named so
 * nobody has to guess: join (a pick to its slot, its tide fact and its home drive time), filter (the
 * Ready questions offered on a tab, the picks with a slot), look up (a suggestion's id in the list),
 * SELECT the latest of several served {@code generatedAt}s ({@link newestRunLabel} — a pick of one
 * served value, not a computed one) and lexically map a served {@code HH:mm} to morning or evening
 * ({@link readyBusyLine}, the same class as the AM/PM word). A pick arrives from the
 * server already ranked, already validated against the briefing the answer was built from
 * (`AskAnswerValidator`), and carrying only its identity — location, window, why
 * ({@code docs/engineering/ask-photocast-plan.md} §1 #5). The card's name, rating, verdict word,
 * event time and tide fact are <em>joined</em> here from the briefing the Plan tab itself renders,
 * so Ask and the Plan tab cannot disagree about one slot. The one figure that is per-user, drive
 * time, is joined from the reader's HOME reach map (§1 #24).
 *
 * <p>The tide fact is the PREFERENCE axis ({@code tideAligned}, via {@link tierOf}), never
 * {@code tideOnTheLight} — CLAUDE.md's two-tide-axes rule, the same one the map's chip answers.
 *
 * <p>No module-level state: a logout must not carry an answer to the next reader.
 */

/** The one prompt every Ask entry (bar, field, peek row) and the input's placeholder read. */
export const ASK_PROMPT = 'Ask PhotoCast…';

/** The input's placeholder once typed questions are off for the day (design State 7). */
export const READY_ONLY_PLACEHOLDER = 'Ready questions only today';

/** The wire's `kind` values. */
export const KIND = { READY: 'ready', OWN: 'own', CANT: 'cant' };

/**
 * The phases with something on screen the reader may want to keep, minimise or end: not empty, and not
 * still being fetched (clearing an answer that is still on its way would drop a charged answer).
 * Includes {@code 'plan'} — a conversation that has an answer and is looking at one pick's plan is
 * still an answer.
 *
 * <p>The ONE list: {@code AskClearAnswer} (is a "New conversation" button drawn?), {@code askPeek} (is the phone
 * sheet minimised?), {@code MapPeekAsk} (does the ✕ clear?) and {@code MapView} all read it. They used
 * to be two copies that nothing pinned together, and adding the plan phase was two edits.
 */
export const SETTLED_PHASES = Object.freeze(['answer', 'plan', 'cant', 'error']);

/** AM for a sunrise, PM for a sunset — the map's short window word. */
const MERIDIEM = { SUNRISE: 'AM', SUNSET: 'PM' };

/**
 * The short meridiem word for a solar event: {@code 'AM'} for a sunrise, {@code 'PM'} for a sunset.
 *
 * <p>⚠️ <b>Null for the two night kinds, on purpose</b> ({@code ASTRO}, {@code AURORA} —
 * `docs/engineering/ask-night-plan.md` §2.6): a night has no AM/PM word, and this is the test that tells a
 * solar window from a night one wherever the two are drawn differently ({@code MapPeekSheet}'s row).
 * Whether a pick is a night is {@link isNightType}'s question; the card builder gates on the KIND, never
 * on this function's answer, which was once the guard that dropped every non-solar pick.
 *
 * @param {?string} targetType the served event type
 * @returns {?string} the word, or null for anything that is not a solar event
 */
export function meridiemOf(targetType) {
  return MERIDIEM[targetType] ?? null;
}

/**
 * The noun for a star count: "star" for exactly one, else "stars" — the one spelling of the
 * pick card's, the plan view's and the rank circle's accessible names.
 *
 * @param {number} n a rating
 * @returns {'star'|'stars'}
 */
export function starsWord(n) {
  return n === 1 ? 'star' : 'stars';
}

/** The lowest and highest star a rating can be: Claude's own 1–5 scale. */
const MIN_RATING = 1;
const MAX_RATING = 5;

/**
 * A window's short name, "Sat AM" / "Sun PM" for a solar window, "Sat night" / "Sat aurora" for a night.
 *
 * <p>Read from the pick's own date string and target type only. The date is already the UK civil
 * day (the server's {@code LocalDate}), so the weekday must never be re-derived through a
 * {@code Date} in the reader's zone: {@link shortDow} fixes noon UTC and names the zone, which is
 * what keeps a reader east of the UK from seeing the next day's name.
 *
 * @param {{date: string, targetType: string}} pick
 * @returns {?string} the short window, or null when the date or event is not one this knows
 */
export function shortWindow(pick) {
  if (typeof pick?.date !== 'string' || pick.date === '') return null;
  if (pick.targetType === NIGHT_TYPE.ASTRO) return `${shortDow(pick.date)} night`;
  if (pick.targetType === NIGHT_TYPE.AURORA) return `${shortDow(pick.date)} aurora`;
  const meridiem = meridiemOf(pick?.targetType);
  if (!meridiem) return null;
  return `${shortDow(pick.date)} ${meridiem}`;
}

/** The full weekday for an accessible name — {@link shortDow}'s own fixed-noon, named-zone rule. */
function longDow(dateStr) {
  // `dayLabelFor` answers "Today"/"Tomorrow" only for the two dates it is told; told neither, it is
  // the app's one long-weekday formatter, and the label must not turn into "Today" at midnight.
  return dayLabelFor(dateStr, null, null);
}

/** A rating on Claude's 1–5 scale, or null — the bound {@code buildScoreIndex} applies. */
function boundedRating(value) {
  return Number.isInteger(value) && value >= MIN_RATING && value <= MAX_RATING ? value : null;
}

/** The slot a pick names: its location, within the pick's own date and event. Null when absent. */
function slotFor(days, pick) {
  if (!Array.isArray(days)) return null;
  const day = days.find((d) => d?.date === pick.date);
  const summary = (day?.eventSummaries ?? []).find((s) => s?.targetType === pick.targetType);
  if (!summary) return null;
  const hit = slotsOf(summary).find(({ slot }) => slot?.locationId != null
    && slot.locationId === pick.locationId);
  return hit ? hit.slot : null;
}

/** The accessible name of a pick's rank circle: "Pick 1, Whitby, Saturday sunrise, 5 stars". */
function pickLabel({ rank, name, date, targetType, rating }) {
  const event = eventWord(targetType);
  const stars = rating == null ? null : `${rating} ${starsWord(rating)}`;
  return [`Pick ${rank}`, name, `${longDow(date)} ${event}`, stars].filter(Boolean).join(', ');
}

/**
 * The accessible name of a NIGHT pick's rank circle: "Pick 1, Hartside, Tonight astro, 4 stars, aurora 3
 * stars". The night's own word is the Map pill's ({@link nightLabel}), so the card and the window control
 * name one night alike.
 */
function nightPickLabel({
  rank, name, nightWord, kindWord, rating, otherKindWord, otherRating,
}) {
  const stars = rating == null ? null : `${rating} ${starsWord(rating)}`;
  const other = otherRating == null ? null : `${otherKindWord} ${otherRating} ${starsWord(otherRating)}`;
  return [`Pick ${rank}`, name, `${nightWord} ${kindWord}`, stars, other].filter(Boolean).join(', ');
}

/** A served string, trimmed, or null when it is absent or blank. */
function textOrNull(value) {
  return typeof value === 'string' && value.trim() !== '' ? value.trim() : null;
}

/** The reader's HOME drive to a place in whole minutes, or null. */
function homeDriveMinutes(homeReachById, locationId) {
  const reach = homeReachById instanceof Map ? homeReachById.get(locationId) : null;
  return Number.isFinite(reach?.driveMinutes) && reach.driveMinutes >= 0
    ? Math.round(reach.driveMinutes)
    : null;
}

/** "Waxing Crescent · 23%": the served moon facts of an astro row, or null with neither. */
function moonOf(row) {
  const phase = textOrNull(row?.moonPhase);
  const pct = Number.isFinite(row?.moonIlluminationPct) ? `${Math.round(row.moonIlluminationPct)}%` : null;
  return [phase, pct].filter(Boolean).join(' · ') || null;
}

/** "21:40–05:10" in the UK clock, or null when either end is not served. */
function nightWindowOf(start, end) {
  const from = formatEventTimeUk(start);
  const to = formatEventTimeUk(end);
  return from && to ? `${from}–${to}` : null;
}

/**
 * One NIGHT pick's card (`docs/engineering/ask-night-plan.md` §2.6): joined by (date, kind) to the night
 * payloads, never to the briefing — the briefing is the solar contract, and no slot of it holds a night.
 *
 * <p>The pick's OWN kind's row is required: an astro pick needs its astro row, an aurora pick its aurora
 * row, and a pick without one is dropped by the caller, never half-drawn. The OTHER kind's figure rides
 * beside it only when that payload happens to be held and the reader may see it ({@code canSeeAurora} —
 * the client never asks for an aurora payload unless it may, and never prints one it was not served).
 *
 * <p>{@code verdict} is null: a night has no GO/MARGINAL/STANDDOWN word (the wire's {@code
 * verdictAtAnswer} is null for one), so the card draws the star and the kind instead, and nothing that
 * reads a verdict (the map chip's tier colour) is given a made-up one.
 */
function nightCard(pick, nights, homeReachById) {
  const { canSeeAurora = false, todayStr = '', tomorrowStr = '' } = nights;
  const isAstro = pick.targetType === NIGHT_TYPE.ASTRO;
  const astroRow = nightRowFor(nights.astro, pick.date, pick.locationId);
  const auroraRow = canSeeAurora ? nightRowFor(nights.aurora, pick.date, pick.locationId) : null;
  const own = isAstro ? astroRow : auroraRow;
  if (!own) return null;
  const name = textOrNull(own.locationName) || pick.locationName;
  if (!name) return null;
  const astroStars = boundedRating(astroRow?.stars);
  const auroraStars = boundedRating(auroraRow?.stars);
  const rating = isAstro ? astroStars : auroraStars;
  const otherRating = isAstro ? auroraStars : astroStars;
  const windowRow = own.nightStart ? own : (astroRow ?? auroraRow);
  const nightStart = windowRow?.nightStart ?? null;
  const nightEnd = windowRow?.nightEnd ?? null;
  const bortle = [own, astroRow, auroraRow]
    .map((row) => row?.bortleClass)
    .find((value) => Number.isInteger(value)) ?? null;
  const nightWord = nightLabel(pick.date, todayStr, tomorrowStr);
  const driveMinutes = homeDriveMinutes(homeReachById, pick.locationId);
  return {
    rank: pick.rank,
    locationId: pick.locationId,
    name,
    regionName: pick.regionName ?? null,
    date: pick.date,
    targetType: pick.targetType,
    windowId: pick.windowId ?? null,
    windowKey: windowKey(pick.date, pick.targetType),
    why: typeof pick.why === 'string' ? pick.why : '',
    rating,
    verdict: null,
    verdictLabel: null,
    night: true,
    kind: isAstro ? 'astro' : 'aurora',
    nightWord,
    astroStars,
    auroraStars,
    nightStart,
    nightEnd,
    nightWindow: nightWindowOf(nightStart, nightEnd),
    bortle,
    moon: moonOf(astroRow),
    clearSky: textOrNull(astroRow?.cloudExplanation),
    // The night's start is the time "Leave home" counts back from, and the one clock the minimised line shows.
    eventTime: formatEventTimeUk(nightStart) ?? null,
    eventInstant: nightStart,
    summary: textOrNull(own.summary),
    dayWord: shortDow(pick.date),
    shortWindow: shortWindow(pick),
    driveMinutes,
    driveLabel: formatDriveDuration(driveMinutes),
    tide: null,
    label: nightPickLabel({
      rank: pick.rank,
      name,
      nightWord,
      kindWord: isAstro ? 'astro' : 'aurora',
      rating,
      otherKindWord: isAstro ? 'aurora' : 'astro',
      otherRating,
    }),
  };
}

/**
 * The cards for an answer's picks, each joined to the briefing the reader is looking at — or, for a night
 * pick, to the night payloads {@code nights} holds.
 *
 * <p>A pick whose slot is not in the client's briefing (a night pick: whose row is not in the night
 * payloads) is <b>dropped</b>, never rendered with the fields it cannot fill: the server validated it
 * against data the client may not yet have (or no longer have), and a card with a name and no figures
 * reads as a recommendation nobody can check. A slot that is there but carries no usable rating keeps its
 * card — the verdict word reads "Not scored" and no number is printed, which is what the Plan tab says of
 * that slot.
 *
 * <p>Ranks are the server's and are never renumbered, so the number on a card is the number on its
 * map marker (F3) whatever was dropped.
 *
 * <p>⚠️ <b>The guard is the KIND, not the meridiem.</b> A solar pick needs an AM/PM word and a night pick
 * a night kind; a pick that is neither is dropped. This used to read {@code !meridiemOf(targetType)} alone,
 * which dropped every night pick.
 *
 * @param {Array<object>} picks the answer's picks: {@code {rank, locationId, locationName,
 *        regionName, date, targetType, windowId, why}}
 * @param {Array<object>} briefingDays {@code briefing.days}
 * @param {?Map<number, {driveMinutes: ?number}>} homeReachById the reader's HOME reach map
 *        (`WindowFirstBriefingContext`'s {@code reachById}) — never {@code effectiveReachById},
 *        which the Plan origin replaces with a region base's drive times
 * @param {?{astro: ?Map<string, Array<object>>, aurora: ?Map<string, Array<object>>,
 *        canSeeAurora: ?boolean, todayStr: ?string, tomorrowStr: ?string}} [nights] the night
 *        payloads (`GET /api/astro/conditions`, `GET /api/aurora/forecast/results`) by date, whether
 *        the reader may see aurora figures, and the UK today/tomorrow the night's word reads from. Null
 *        or absent, every night pick is dropped
 * @returns {Array<{rank: number, locationId: number, name: string, regionName: ?string,
 *   date: string, targetType: string, windowId: ?string, windowKey: string, why: string, rating: ?number,
 *   verdict: ?string, verdictLabel: ?string, eventTime: ?string, eventInstant: ?string,
 *   summary: ?string, dayWord: string, shortWindow: string, driveMinutes: ?number,
 *   driveLabel: ?string,
 *   tide: ?{tier: 'match'|'miss', state: ?string, shortfall: ?string, clause: ?string},
 *   label: string}>} {@code eventInstant} is the slot's served UTC instant (the "Plan this" view's
 *   leave-by reads it raw) and {@code summary} the slot's served one-line reading (its note); neither is
 *   rendered by the pick card itself. A night card also carries {@code night: true}, {@code kind}
 *   ({@code 'astro'|'aurora'}), {@code nightWord}, {@code astroStars}, {@code auroraStars},
 *   {@code nightStart}, {@code nightEnd}, {@code nightWindow}, {@code bortle}, {@code moon} and
 *   {@code clearSky}; its {@code verdict}/{@code verdictLabel} are null and its {@code tide} is null.
 */
export function buildPickCards(picks, briefingDays, homeReachById, nights = null) {
  if (!Array.isArray(picks) || picks.length === 0) return [];
  const slotIdx = buildSlotIndex(briefingDays);
  const tideIdx = buildTideAlignmentIndex(briefingDays);
  const cards = [];
  for (const pick of picks) {
    if (!pick || !Number.isInteger(pick.rank) || pick.locationId == null
        || typeof pick.date !== 'string') continue;
    if (isNightType(pick.targetType)) {
      const night = nights ? nightCard(pick, nights, homeReachById) : null;
      if (night) cards.push(night);
      continue;
    }
    if (!meridiemOf(pick.targetType)) continue;
    const slot = slotFor(briefingDays, pick);
    if (!slot) continue;
    const name = slot.locationName || pick.locationName;
    if (!name) continue;
    const rating = boundedRating(slot.claudeRating);
    // A SLOT's verdict, so the slot-level words (SLOT_VERDICT_LABEL: UNRATED reads "Not rated", never
    // the region's "Too few rated"). UNRATED passes through as itself — it is a known value, and the
    // chip's tier colour (`.wf-ask-sc[data-tier]`) has no rule for it, which is the neutral AWAITING
    // ink by design (unrated-verdict-plan.md U2): a place nothing has rated gets no verdict hue. A
    // value this map does not know still collapses to AWAITING, never to a recommending word.
    const verdict = SLOT_VERDICT_LABEL[slot.displayVerdict] ? slot.displayVerdict : 'AWAITING';
    const timed = lookupForWindow(slotIdx, pick.locationId, name, pick.date, pick.targetType);
    const fact = lookupForWindow(tideIdx, pick.locationId, name, pick.date, pick.targetType);
    const tier = tierOf(fact);
    const driveMinutes = homeDriveMinutes(homeReachById, pick.locationId);
    cards.push({
      rank: pick.rank,
      locationId: pick.locationId,
      name,
      regionName: pick.regionName ?? null,
      date: pick.date,
      targetType: pick.targetType,
      windowId: pick.windowId ?? null,
      windowKey: windowKey(pick.date, pick.targetType),
      why: typeof pick.why === 'string' ? pick.why : '',
      rating,
      verdict,
      verdictLabel: SLOT_VERDICT_LABEL[verdict],
      eventTime: formatEventTimeUk(timed?.eventTime) ?? null,
      eventInstant: timed?.eventTime ?? null,
      summary: typeof slot.claudeSummary === 'string' && slot.claudeSummary.trim() !== ''
        ? slot.claudeSummary.trim()
        : null,
      dayWord: shortDow(pick.date),
      shortWindow: shortWindow(pick),
      driveMinutes,
      driveLabel: formatDriveDuration(driveMinutes),
      tide: tier
        ? {
          tier,
          state: fact.state ?? null,
          shortfall: fact.shortfall ?? null,
          clause: tideAccessibleClause(tier, fact.shortfall, fact.state),
        }
        : null,
      label: pickLabel({ rank: pick.rank, name, date: pick.date, targetType: pick.targetType, rating }),
    });
  }
  return cards;
}

/**
 * The Ready questions offered on a tab, in the order the server sent them.
 *
 * @param {Array<{tabs: Array<string>}>} questions the Ready list
 * @param {string} view {@code plan}, {@code map} or {@code coming-up}
 * @returns {Array<object>} the questions whose {@code tabs} name the view
 */
export function questionsForView(questions, view) {
  return (Array.isArray(questions) ? questions : [])
    .filter((q) => q && Array.isArray(q.tabs) && q.tabs.includes(view));
}

/**
 * The run the "Ready from the HH:MM run" line names: the NEWEST among the listed questions.
 *
 * <p>Each Ready question carries the run its own answer was built from, and they can differ (a
 * question whose answer could not be rebuilt in a later cycle keeps its earlier row, until it goes
 * stale). The line is honest about the freshest, and the label is read off the payload — never a
 * hard-coded "06:00". {@code generatedAt} is a bare ISO local date-time, so string order is time
 * order.
 *
 * @param {Array<{generatedAt: ?string, runLabel: ?string}>} questions
 * @returns {?string} {@code HH:mm}, or null when no listed question carries one
 */
export function newestRunLabel(questions) {
  let best = null;
  for (const q of Array.isArray(questions) ? questions : []) {
    if (typeof q?.runLabel !== 'string' || q.runLabel === '') continue;
    if (best === null || String(q.generatedAt ?? '') > String(best.generatedAt ?? '')) best = q;
  }
  return best ? best.runLabel : null;
}

/**
 * Resolves a `cant` answer's {@code try} suggestions ({@code {id, text}}) to the Ready questions
 * they name, so a tap opens the answer already in hand.
 *
 * <p>A suggestion whose id is not in the list is left out: the server chose it from the question's
 * own scope, but the list the reader holds can be a different scope's or a refetch behind, and a
 * button that cannot open anything is worse than a shorter list.
 *
 * @param {?Array<{id: string}>} suggestions the answer's {@code try}
 * @param {Array<{id: string}>} questions the Ready list
 * @returns {Array<object>} the matching Ready questions, in suggestion order
 */
export function resolveSuggestions(suggestions, questions) {
  const list = Array.isArray(questions) ? questions : [];
  return (Array.isArray(suggestions) ? suggestions : [])
    .map((s) => list.find((q) => q?.id === s?.id))
    .filter(Boolean);
}

/**
 * The kicker over an event card: the served type, spelled for reading ({@code LUNAR_ECLIPSE} →
 * "LUNAR ECLIPSE"). A lexical map, not a catalogue — a new type needs no change here.
 *
 * @param {?string} type the event's served type
 * @returns {string} the type with underscores read as spaces, or ''
 */
export function eventKicker(type) {
  return String(type ?? '').replace(/_/g, ' ').trim();
}

/**
 * The line shown while a Ready answer opens, from the run it belongs to.
 *
 * <p>The design's copy is "Opening this morning's answer", written for the 06:00 run; the 18:00 run
 * has answers too, and "this morning" over an evening answer would be a false claim. A lexical map
 * off the served {@code HH:mm}: before noon, morning; otherwise, evening; an unreadable label says
 * neither.
 *
 * @param {?string} runLabel the Ready question's {@code runLabel}
 * @returns {string}
 */
export function readyBusyLine(runLabel) {
  const hour = /^(\d{1,2}):\d{2}$/.exec(String(runLabel ?? ''));
  if (!hour) return 'Opening the answer';
  return Number(hour[1]) < 12 ? 'Opening this morning’s answer' : 'Opening this evening’s answer';
}

/** The busy line for a typed question. */
export const TYPED_BUSY_LINE = 'Reading forecasts and hot topics';
