import { laneDates, lastPlanDateStr, PLAN_OWNED_DAYS } from './comingUpHandoff.js';
import { shiftDateStr } from './mapDates.js';
import { topicStyle } from './topicPalette.js';

/**
 * The "Next four days" topic tiles' model — now the source of At a Glance's "The days ahead" lanes, which
 * {@link buildGlanceLanes} re-buckets and {@code WindowGlanceLanes} draws through {@code WindowFirstHeatStrip}
 * (days-ahead-lanes-plan.md; it began as the stacked day-header pills of glance-topic-strip-plan.md). It began as
 * the Coming up tab's block of the same name (coming-up-glance-plan §3 T1), which that plan moved here, and
 * it replaced the hot-topics-by-day C1 lanes ({@code fourDayLanes.js}) before that.
 *
 * <p><b>Pure, client-side, and licensed as a filter/map member</b> (architecture-decisions
 * "Backend-heavy"): it only re-buckets {@code briefing.hotTopics} by date (through the shared
 * {@link laneDates}), copies the served {@code glance} fields onto a chip, and reads the served
 * almanac entries to name a jump target. It prints no fact the server did not state — the chip's
 * value, short value and badge are the served {@code HotTopic.glance} (B1), never derived here.
 *
 * <p><b>One chip per type per day.</b> The chip is the first topic of that type in served order;
 * its {@code glance} is the first non-null one among that type's topics that day, and its
 * {@code events} list every SUNRISE/SUNSET window those topics land on (chronological order). Within
 * a tile the non-weather chips come first, then the weather chips, each group in served order
 * (§1.9: the order is the server's, never re-ranked here).
 *
 * <p><b>The jump rule (§2.3).</b> A chip jumps only to a RENDERED almanac entry whose OWN span covers
 * the chip's date. For a tide that span is the tide's own days (see {@link tideSpan}): an unmerged
 * entry's {@code startDate..endDate}, or a merged entry's {@code dayRanges}, because a merged entry's
 * own dates are the UNION of every topic it carries. For every other type it is
 * {@code startDate <= date <= endDate}. There is no "next run in the list" fallback: a run
 * whose entry is not rendered (filtered out, or not yet loaded) gets no jump, and aiming its chip
 * at next month's run would be a lie.
 *
 * <p><b>The Map door</b> belongs to weather chips only, and only for a date the reader holds a colour
 * forecast for. {@code forecastDates} is three-valued: null is "not known yet" and withholds nothing;
 * an array, even an empty one, is known (the shell passes {@code []} when it has no Map door).
 *
 * <p><b>The tap target is a window key</b>, never "the date + sunset": the first key
 * {@code topicWindowKeys} produced on that date that Plan actually renders ({@code openableKeys}). With
 * none, it is null and the caller falls back to plain {@code goToPlan()}.
 */

/**
 * HotTopic type → the almanac {@code ComingUpEntry.type} values that describe the same event.
 *
 * <p>The vocabularies differ (hot topics are {@code SPRING_TIDE}/{@code NLC}, the almanac
 * {@code spring-tide}/{@code nlc-season}), so this is an explicit map rather than a case
 * conversion. Types with no entry (aurora, dust, inversion …) have no almanac counterpart, so their
 * jump is always null.
 */
export const ALMANAC_TYPES_BY_TOPIC = {
  // Either tide type for either topic: the topic and the almanac can disagree on king vs spring.
  KING_TIDE: ['king-tide', 'spring-tide'],
  SPRING_TIDE: ['king-tide', 'spring-tide'],
  SUPERMOON: ['supermoon'],
  METEOR: ['meteor'],
  NLC: ['nlc-season'],
  EQUINOX: ['equinox'],
  ECLIPSE: ['eclipse'],
  LUNAR_ECLIPSE: ['lunar-eclipse'],
};

const SUNRISE = 'SUNRISE';
const SUNSET = 'SUNSET';
const NIGHT = 'NIGHT';
const TIDE_TOPICS = new Set(['KING_TIDE', 'SPRING_TIDE']);

/**
 * The dates a served topic covers FOR THE LANES. A NIGHT topic (aurora, NLC) covers its own dusk date(s) only
 * ({@code date}…{@code endDate}) — not the next morning its second window falls on — so a one-night aurora is a
 * one-column lane and an NLC dated on the last day does not "continue after". Every other topic covers the dates
 * its windows land on ({@link laneDates}).
 */
function laneCoveredDates(topic) {
  if (topic.eventType === NIGHT) {
    const dates = [];
    const last = topic.endDate ?? topic.date;
    for (let d = topic.date; d <= last; d = shiftDateStr(d, 1)) dates.push(d);
    return dates;
  }
  return [...new Set(laneDates(topic).map((item) => item.date))];
}

/**
 * The identity of the EVENT a served topic is one day of: the topic itself ({@code type} and {@code date}), or,
 * for a tide, its whole run — the served {@code tideRun} states which day of how many this is, so the run's first
 * date is {@code date - (dayNumber - 1)}. Two separate MIST topics on adjacent days are two events; a four-day
 * spring run is one.
 */
function eventIdOf(topic) {
  const run = topic.tideRun;
  if (TIDE_TOPICS.has(topic.type) && run?.dayNumber >= 1 && run?.dayCount >= 1) {
    return `TIDE:${run.runLabel ?? ''}:${run.dayCount}:${shiftDateStr(topic.date, 1 - run.dayNumber)}`;
  }
  return `${topic.type}:${topic.date}`;
}

const RELATIVE = ['today', 'tomorrow', 'in 2 days', 'in 3 days'];
const RELATIVE_SHORT = ['today', 'tmrw', '+2', '+3'];

/** `Fri` — short weekday, en-GB, noon UTC so no zone moves the date. */
function dow(dateStr) {
  return new Date(`${dateStr}T12:00:00Z`)
    .toLocaleDateString('en-GB', { weekday: 'short', timeZone: 'UTC' });
}

/** `12` — day of the month. */
function dayNum(dateStr) {
  return new Date(`${dateStr}T12:00:00Z`)
    .toLocaleDateString('en-GB', { day: 'numeric', timeZone: 'UTC' });
}

/** Whether an almanac entry is, or carries (in a coincidence line), one of these almanac types. */
function holdsType(entry, wanted) {
  return wanted.includes(entry?.type)
    || (entry?.coincidence ?? []).some((line) => wanted.includes(line?.type));
}

/**
 * The tide's OWN first and last day inside an almanac entry — decided from the tide's own span, never
 * from a span that may belong to something else.
 *
 * <p>An unmerged tide entry ({@code coincidence} empty) is the tide's own span: its
 * {@code startDate..endDate}. A MERGED entry's {@code startDate}/{@code endDate} are the UNION of every
 * merged topic's span ({@code ComingUpAssembler.mergeEntries}) — a tide that ends on day 4 merged with
 * a supermoon that runs to day 7 has an {@code endDate} on day 7 — so those dates are NEVER read. The
 * tide's own days ride {@code dayRanges}, "carried onto a merged entry whichever topic hosts". A merged
 * entry with no {@code dayRanges} offers no evidence, so it has no span.
 *
 * @param {object} entry a served almanac entry that holds a tide
 * @returns {?{first: string, last: string}} the tide's days, or null when the entry offers no evidence
 */
function tideSpan(entry) {
  if ((entry.coincidence ?? []).length === 0) {
    return entry.startDate && entry.endDate ? { first: entry.startDate, last: entry.endDate } : null;
  }
  const days = (entry.dayRanges ?? []).filter((d) => d?.shoulder !== true).map((d) => d?.date).filter(Boolean).sort();
  return days.length > 0 ? { first: days[0], last: days[days.length - 1] } : null;
}

/** Whether an entry's own span covers {@code date}, for a chip of this topic type (§2.3). */
function entryCovers(entry, type, date) {
  if (TIDE_TOPICS.has(type)) {
    const span = tideSpan(entry);
    return span !== null && span.first <= date && date <= span.last;
  }
  return Boolean(entry.startDate) && entry.startDate <= date && date <= (entry.endDate ?? entry.startDate);
}

/**
 * The rendered list entry a chip scrolls to, or null.
 *
 * <p>Matches {@code entry.type} <b>or</b> any {@code coincidence[].type} (a merged card takes its
 * host's type, the rule {@code matchesFilter} already uses), restricted to entries the month list is
 * rendering under the active chip, and to those whose own span covers {@code date}. The earliest
 * start wins when several do.
 */
function jumpFor(type, date, entries, rendered) {
  const wanted = ALMANAC_TYPES_BY_TOPIC[type];
  if (!wanted || !Array.isArray(entries)) return null;
  const hits = entries
    .filter((entry) => entry?.id != null && rendered.has(entry.id) && holdsType(entry, wanted)
      && entryCovers(entry, type, date))
    .sort((a, b) => (a.startDate < b.startDate ? -1 : a.startDate > b.startDate ? 1 : 0));
  return hits.length > 0 ? { entryId: hits[0].id } : null;
}

/**
 * Builds the chip for one type on one date.
 *
 * @param {string} type the topic type
 * @param {string} date the tile's date
 * @param {Array<{topic: object, item: object}>} allRecords the type's topics landing on {@code date},
 *        each with the lane item (date, window, key) that put it there, in served order
 */
function buildChip(type, date, allRecords, ctx) {
  // Lane identity: only the records whose topic really covers this date speak for the chip, so a NIGHT topic's
  // spill onto the next morning never lends its glance or routes to another night's topic merged beside it.
  const covering = allRecords.filter(({ topic }) => laneCoveredDates(topic).includes(date));
  const coversDay = covering.length > 0;
  // An event that BEGAN before this date (a night topic read on its next morning) has no covering record: it is
  // built from the records whose topic covers the PREVIOUS date, so the strip can still show it (`buildGlanceLanes`).
  const began = coversDay ? [] : allRecords.filter(({ topic }) => laneCoveredDates(topic).includes(shiftDateStr(date, -1)));
  const records = coversDay ? covering : (began.length > 0 ? began : allRecords);
  const first = records[0].topic;
  const style = topicStyle(type, first.label);
  const glanceRecord = records.find((r) => r.topic?.glance) ?? null;
  const glance = glanceRecord ? glanceRecord.topic.glance : null;
  const weather = glance?.weather === true;

  const windows = new Set(records.map((r) => r.item.window).filter(Boolean));
  const events = [SUNRISE, SUNSET].filter((w) => windows.has(w));

  let target = null;
  for (const { item } of records) {
    if (item.key && ctx.openable.has(item.key)) { target = item.key; break; }
  }

  let mapDoor = null;
  if (ctx.hasMapDoor && weather
      && (ctx.forecastDates == null || ctx.forecastDates.includes(date))) {
    // The window of the topic that SUPPLIED the glance (its text names that light), else the first
    // record's, else the default for an unanchored topic.
    const eventType = glanceRecord?.item.window ?? records[0].item.window
      ?? (first.eventType === NIGHT ? SUNSET : SUNRISE);
    mapDoor = { date, eventType };
  }

  return {
    key: `${date}:${type}`,
    type,
    name: style.name,
    chipName: style.chipName,
    colour: style.colour,
    value: glance?.value ?? null,
    shortValue: glance?.shortValue ?? null,
    badge: glance?.badge ?? null,
    weather,
    eventId: eventIdOf(first),
    coversDay,
    events,
    jump: jumpFor(type, date, ctx.entries, ctx.rendered),
    mapDoor,
    target,
  };
}

/**
 * Builds the four day tiles.
 *
 * @param {object} args
 * @param {string} args.todayStr the reader's UK today, `YYYY-MM-DD` (from {@code appNow}, Rewind)
 * @param {?Array} args.hotTopics the live {@code briefing.hotTopics}; null/undefined before it arrives
 * @param {?Array} [args.entries] the served almanac entries ({@code feed.entries})
 * @param {Set<string>|Array<string>} [args.renderedEntryIds] ids of the entries the month list is
 *        rendering now (it is chip-filtered); a jump never targets anything else
 * @param {Set<string>|Array<string>} [args.openableKeys] the `date:TYPE` keys of Plan's window cards
 * @param {?Array<string>} [args.forecastDates] the dates the reader holds a colour forecast for; null
 *        is "not known" and withholds no Map door, an array (even empty) is known
 * @param {boolean} [args.hasMapDoor] whether the caller can open the Map; false makes every
 *        {@code mapDoor} null so the spoken destination and the tap route cannot disagree
 * @returns {{days: Array<object>, topicCount: ?number}} the four tiles, each with its chips, and the
 *          number of distinct topic types across them — null while {@code hotTopics} has not arrived
 */
export function buildFourDayTiles({
  todayStr, hotTopics, entries = null, renderedEntryIds = null, openableKeys = null,
  forecastDates = null, hasMapDoor = true,
}) {
  if (!todayStr) return { days: [], topicCount: null };

  const dayDates = Array.from({ length: PLAN_OWNED_DAYS }, (_, i) => shiftDateStr(todayStr, i));
  const lastPlan = lastPlanDateStr(todayStr);
  const ctx = {
    entries,
    hasMapDoor,
    forecastDates: Array.isArray(forecastDates) ? forecastDates : null,
    openable: openableKeys instanceof Set ? openableKeys : new Set(openableKeys ?? []),
    rendered: renderedEntryIds instanceof Set ? renderedEntryIds : new Set(renderedEntryIds ?? []),
  };

  const days = dayDates.map((date, i) => ({
    date,
    dow: dow(date),
    dayNum: dayNum(date),
    relative: RELATIVE[i],
    relativeShort: RELATIVE_SHORT[i],
    today: i === 0,
    chips: [],
  }));
  if (!Array.isArray(hotTopics)) return { days, topicCount: null };

  // date → type → [{topic, item}], both levels in first-seen (served) order.
  const byDate = new Map(dayDates.map((date) => [date, new Map()]));
  for (const topic of hotTopics) {
    if (!topic?.type || !topic.date) continue;
    for (const item of laneDates(topic)) {
      if (item.date < todayStr || item.date > lastPlan) continue;
      const types = byDate.get(item.date);
      if (!types.has(topic.type)) types.set(topic.type, []);
      types.get(topic.type).push({ topic, item });
    }
  }

  const seenTypes = new Set();
  for (const day of days) {
    const chips = [...byDate.get(day.date).entries()]
      .map(([type, records]) => buildChip(type, day.date, records, ctx));
    day.chips = [...chips.filter((c) => !c.weather), ...chips.filter((c) => c.weather)];
    for (const chip of day.chips) seenTypes.add(chip.type);
  }

  return { days, topicCount: seenTypes.size };
}

/**
 * The At a Glance "days ahead" lanes (days-ahead-lanes-plan.md §2): the four days' chips re-bucketed into one lane
 * per RUN of an event. It re-buckets served chips and derives no fact — every printed string is a served
 * {@code glance} field, and the edges read only served topic dates (never the almanac, D14).
 *
 * <p><b>A run</b> is a maximal sequence of live matrix columns on consecutive calendar dates where each day holds
 * the same event ({@code eventId}: the same served topic, or the same tide run). A column that is absent from the
 * four days, or all-away, breaks a run.
 *
 * <p>⚠️ {@code startCol}/{@code endCol} are the matrix index + 1 — never a position in the live columns — because
 * the lanes grid has one track per matrix column, away ones included. Liveness only breaks runs.
 *
 * <p>{@code detail} is the PEAK day's served value when the run has a peak (else the first day's), with
 * {@code detailDate} the day it came from. The routes ({@code jump}, {@code mapDoor}, {@code target}) are each
 * the first chip's in the run that has one. Only PEAK is a tag ({@code peak} is its date). {@code startsBefore}
 * / {@code endsAfter} say a SERVED topic of the same event covers the day before / after the run — the briefing
 * serves topics clipped to today…the last plan date, so a tide run that really began yesterday or runs past the
 * last day is not visible here (a backend follow-up, not guessed at).
 *
 * <p><b>Rows are assigned here</b>, first-fit, longest span first then earliest column then chip order, and the
 * output is sorted by (row, startCol) — the DOM order — so Tab follows the visual order (WCAG 2.4.3).
 *
 * @param {Array<object>} days the {@code days} of {@link buildFourDayTiles}
 * @param {Array<{date: string, live: boolean}>} columns the matrix's columns in order; {@code live} is false for
 *        an all-away column
 * @param {?Array<object>} hotTopics the served {@code briefing.hotTopics} (for the edge dates)
 * @returns {Array<object>} the lanes in DOM order, each with a 1-based {@code row}
 */
export function buildGlanceLanes(days, columns, hotTopics) {
  const chipsByDate = new Map(days.map((day) => [day.date, day.chips]));
  const servedDates = new Map();
  for (const topic of Array.isArray(hotTopics) ? hotTopics : []) {
    if (!topic?.type || !topic.date) continue;
    const id = eventIdOf(topic);
    if (!servedDates.has(id)) servedDates.set(id, new Set());
    for (const date of laneCoveredDates(topic)) servedDates.get(id).add(date);
  }

  const runs = [];
  const liveDates = new Set(columns.filter((column) => column.live).map((column) => column.date));
  let open = new Map();
  columns.forEach((column, index) => {
    const next = new Map();
    // A chip that does not cover its day (a night topic on its next morning) is kept ONLY when the previous date is
    // not a live column: the event began before what is shown, and tonight is still a live opportunity.
    const beganOffScreen = !liveDates.has(shiftDateStr(column.date, -1));
    const chips = column.live
      ? (chipsByDate.get(column.date) ?? []).filter((chip) => chip.coversDay || beganOffScreen)
      : [];
    for (const chip of chips) {
      const item = { chip, date: column.date };
      const run = open.get(chip.eventId);
      if (run && run.items[run.items.length - 1].date === shiftDateStr(column.date, -1)) {
        run.items.push(item);
        run.endCol = index + 1;
        next.set(chip.eventId, run);
      } else {
        const created = {
          id: chip.eventId, items: [item], startCol: index + 1, endCol: index + 1, order: runs.length,
        };
        runs.push(created);
        next.set(chip.eventId, created);
      }
    }
    open = next;
  });

  const lanes = runs.map((run) => {
    const { items } = run;
    const first = items[0];
    const peakItem = items.find((item) => item.chip.badge === 'PEAK') ?? null;
    const source = peakItem?.chip.value ? peakItem : first;
    const firstWith = (field) => items.find((item) => item.chip[field])?.chip[field] ?? null;
    const dates = items.map((item) => item.date);
    const served = servedDates.get(run.id);
    return {
      key: `${run.id}@${first.date}`,
      type: first.chip.type,
      name: first.chip.name,
      colour: first.chip.colour,
      startCol: run.startCol,
      endCol: run.endCol,
      detail: source.chip.value ?? null,
      detailDate: source.chip.value ? source.date : null,
      peak: peakItem ? peakItem.date : null,
      startsBefore: served?.has(shiftDateStr(first.date, -1)) ?? false,
      endsAfter: served?.has(shiftDateStr(dates[dates.length - 1], 1)) ?? false,
      dates,
      jump: firstWith('jump'),
      mapDoor: firstWith('mapDoor'),
      target: firstWith('target'),
      order: run.order,
      row: 0,
    };
  });

  // First-fit rows: the longest spans take the top rows.
  const rows = [];
  const byPriority = [...lanes].sort((a, b) => (b.endCol - b.startCol) - (a.endCol - a.startCol)
    || a.startCol - b.startCol || a.order - b.order);
  for (const lane of byPriority) {
    let row = rows.findIndex((taken) => {
      for (let c = lane.startCol; c <= lane.endCol; c += 1) if (taken.has(c)) return false;
      return true;
    });
    if (row < 0) { rows.push(new Set()); row = rows.length - 1; }
    for (let c = lane.startCol; c <= lane.endCol; c += 1) rows[row].add(c);
    lane.row = row + 1;
  }
  return lanes.sort((a, b) => a.row - b.row || a.startCol - b.startCol);
}

