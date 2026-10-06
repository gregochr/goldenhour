import { dayLabelFor, eventWord, VERDICT_LABEL } from './windowFirstCards.js';
import {
  buildSlotIndex, buildTideAlignmentIndex, lookupForWindow, shortDow, slotsOf,
} from './locationSheet.js';
import { tideAccessibleClause, tierOf } from './mapTideFit.js';
import { formatDriveDuration } from './briefingDisplay.js';
import { formatEventTimeUk } from './conversions.js';

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

/** The input's placeholder once typed questions are off for the day (design State 7). */
export const READY_ONLY_PLACEHOLDER = 'Ready questions only today';

/** The Pro allowance quoted in the upsell line. The design's figure — `photocast.ask.limit-pro`'s default. */
export const PRO_DAILY_LIMIT = 30;

/** The wire's `kind` values. */
export const KIND = { READY: 'ready', OWN: 'own', CANT: 'cant' };

/** AM for a sunrise, PM for a sunset — the map's short window word. */
const MERIDIEM = { SUNRISE: 'AM', SUNSET: 'PM' };

/** The lowest and highest star a rating can be: Claude's own 1–5 scale. */
const MIN_RATING = 1;
const MAX_RATING = 5;

/**
 * A window's short name, "Sat AM" / "Sun PM".
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
  const meridiem = MERIDIEM[pick?.targetType];
  if (!meridiem || typeof pick?.date !== 'string' || pick.date === '') return null;
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
  const stars = rating == null ? null : `${rating} ${rating === 1 ? 'star' : 'stars'}`;
  return [`Pick ${rank}`, name, `${longDow(date)} ${event}`, stars].filter(Boolean).join(', ');
}

/**
 * The cards for an answer's picks, each joined to the briefing the reader is looking at.
 *
 * <p>A pick whose slot is not in the client's briefing is <b>dropped</b>, never rendered with the
 * fields it cannot fill: the server validated it against a briefing the client may not yet have (or
 * no longer have), and a card with a name and no figures reads as a recommendation nobody can
 * check. A slot that is there but carries no usable rating keeps its card — the verdict word reads
 * "Not scored" and no number is printed, which is what the Plan tab says of that slot.
 *
 * <p>Ranks are the server's and are never renumbered, so the number on a card is the number on its
 * map marker (F3) whatever was dropped.
 *
 * @param {Array<object>} picks the answer's picks: {@code {rank, locationId, locationName,
 *        regionName, date, targetType, windowId, why}}
 * @param {Array<object>} briefingDays {@code briefing.days}
 * @param {?Map<number, {driveMinutes: ?number}>} homeReachById the reader's HOME reach map
 *        (`WindowFirstBriefingContext`'s {@code reachById}) — never {@code effectiveReachById},
 *        which the Plan origin replaces with a region base's drive times
 * @returns {Array<{rank: number, locationId: number, name: string, regionName: ?string,
 *   date: string, targetType: string, windowId: ?string, why: string, rating: ?number,
 *   verdict: string, verdictLabel: string, eventTime: ?string, eventInstant: ?string,
 *   summary: ?string, dayWord: string, shortWindow: string, driveMinutes: ?number,
 *   driveLabel: ?string,
 *   tide: ?{tier: 'match'|'miss', state: ?string, shortfall: ?string, clause: ?string},
 *   label: string}>} {@code eventInstant} is the slot's served UTC instant (F5's leave-by reads it
 *   raw) and {@code summary} the slot's served one-line reading (F5's note); neither is rendered
 *   by the card.
 */
export function buildPickCards(picks, briefingDays, homeReachById) {
  if (!Array.isArray(picks) || picks.length === 0) return [];
  const slotIdx = buildSlotIndex(briefingDays);
  const tideIdx = buildTideAlignmentIndex(briefingDays);
  const cards = [];
  for (const pick of picks) {
    if (!pick || !Number.isInteger(pick.rank) || pick.locationId == null
        || typeof pick.date !== 'string' || !MERIDIEM[pick.targetType]) continue;
    const slot = slotFor(briefingDays, pick);
    if (!slot) continue;
    const name = slot.locationName || pick.locationName;
    if (!name) continue;
    const rating = boundedRating(slot.claudeRating);
    const verdict = VERDICT_LABEL[slot.displayVerdict] ? slot.displayVerdict : 'AWAITING';
    const timed = lookupForWindow(slotIdx, pick.locationId, name, pick.date, pick.targetType);
    const fact = lookupForWindow(tideIdx, pick.locationId, name, pick.date, pick.targetType);
    const tier = tierOf(fact);
    const reach = homeReachById instanceof Map ? homeReachById.get(pick.locationId) : null;
    const driveMinutes = Number.isFinite(reach?.driveMinutes) && reach.driveMinutes >= 0
      ? Math.round(reach.driveMinutes)
      : null;
    cards.push({
      rank: pick.rank,
      locationId: pick.locationId,
      name,
      regionName: pick.regionName ?? null,
      date: pick.date,
      targetType: pick.targetType,
      windowId: pick.windowId ?? null,
      why: typeof pick.why === 'string' ? pick.why : '',
      rating,
      verdict,
      verdictLabel: VERDICT_LABEL[verdict],
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
