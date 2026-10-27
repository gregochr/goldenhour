/**
 * The Map tab's hot-topic chips and rings, as pure view-model helpers
 * (docs/engineering/hot-topics-by-day-plan.md §3 M1).
 *
 * <p><b>A filter/map module, never a derivation.</b> Which topics exist, which window they belong to
 * and which places made them fire are all SERVED: the window comes from {@code topicWindowKeys}
 * (via {@code buildTopicIndex}), the places from {@code HotTopic.locationNames}, and the scope rule
 * is {@link topicInScope}. This module joins and groups; it tests no weather and re-derives no
 * verdict (CLAUDE.md, "Backend-heavy").
 *
 * <p>⚠️ <b>Never {@code row.badges}.</b> Plan renders six windows and the Map's served rows are the
 * same six; filler and past rows carry {@code badges: []}, and a night row carries none at all. A
 * chip list read from badges would say "no hot topics" on a window a topic fires on, which is the
 * two-question rule broken (hot topics are never silenced) and would make the Map contradict the
 * Coming up lanes. Topics come from the index.
 */
import { bigSeasWindowAhead } from './bigSeasWindow.js';
import { formatEventTimeUk, parseUtcInstant } from './conversions.js';
import { isNightOver, shiftDateStr } from './mapDates.js';
import { spotsNamed } from './mapDoor.js';
import { appNow } from './rewind.js';
import { topicStyle } from './topicPalette.js';
import { isWholeSkyTopic, topicInScope } from './windowFirstTopics.js';
import { windowKey } from './windowKeys.js';

/** The pseudo-type of the Big seas chip. Not a HotTopic type: big seas is a served day, not a topic. */
export const BIG_SEAS_TYPE = 'BIG_SEAS';

/**
 * Big seas' chip style. Tide-teal, the colour the big-seas line, its chips and the week menu's bolt
 * already use ({@code --color-tide-light} in {@code index.css}); the glyph is the {@code BoltIcon}
 * component, which the chip draws itself, so {@code glyph} is null.
 */
export const BIG_SEAS_STYLE = Object.freeze({
  colour: '#8FC0C7', glyph: null, name: 'Big seas', shortName: 'Big seas',
});

/** The word a whole-sky chip carries in place of a count. */
export const SKY_WIDE = 'sky-wide';

/**
 * The topic types whose rows and chips carry the tide-at-the-light treatment (marks, detail line,
 * chip clause): the spring and king tides and the storm surge. Big seas is deliberately absent: its
 * HW ticks are a different event (daylight high water plus swell).
 */
export const TIDE_LIGHT_TYPES = new Set(['SPRING_TIDE', 'KING_TIDE', 'STORM_SURGE']);

/** The tidal-run types: they alone carry a served run row ({@code tideRun}) for the sub-line. */
const TIDE_RUN_TYPES = new Set(['SPRING_TIDE', 'KING_TIDE']);

/**
 * The served tide-at-the-light fact for one window, or null when there is none to read: the row's
 * {@code tide} carries {@code lightAlignment} only for a Pro or Admin reader (the server strips it
 * for LITE), and only when the rollup could be built. {@code align} is {@code 'HW'}, {@code 'LW'} or
 * null for mid tide (the server's {@code "NONE"}). Nothing is computed here: the 60-minute rule (the tide run's own) is
 * the server's, and every string is served.
 *
 * @param {?object} tide the window's served {@code BriefingWindowTide} (an EV row's {@code tide})
 * @returns {?{align: ?string, kind: string, time: ?string, offset: ?string, height: ?string,
 *   eventTime: ?string, station: ?string}} the fact, or null
 */
export function tideLightFact(tide) {
  const a = tide?.lightAlignment;
  if (a !== 'HW' && a !== 'LW' && a !== 'NONE') return null;
  return {
    align: a === 'NONE' ? null : a,
    kind: tide.nearestType === 'LW' ? 'LW' : 'HW',
    time: tide.nearestTime ?? null,
    offset: tide.nearestOffset ?? null,
    height: tide.nearestHeight ?? null,
    eventTime: tide.eventTime ?? null,
    station: tide.locationName ?? null,
  };
}

/** "high" / "low" for an HW / LW kind, the prose form. */
const KIND_WORD = Object.freeze({ HW: 'high', LW: 'low' });

/** "1h09 after sunrise" to "1h09 after" (a trailing event word dropped; "at sunrise" is kept whole). */
function gapOf(offset) {
  if (typeof offset !== 'string' || offset === '') return null;
  return offset.replace(/ (before|after) (sunrise|sunset)$/, ' $1');
}

/** Night row kinds: the astro and aurora rows ({@code EVENT_KIND.ASTRO}/{@code AURORA}). */
const NIGHT_ROW_KINDS = new Set(['astro', 'aur']);

/**
 * The region names the Map is scoped to: the Plan tab's own scope list (what
 * {@code scopeRegions(heat.spots, reachById, origin)} in {@code planOrigin.js} returns, the source
 * the Plan heat strip and shell filter topics by) while the tab's area scope is on, and {@code []}
 * otherwise (an empty list filters nothing, the {@code windowTopics} convention).
 * hot-topics-by-day-plan §1.5.
 *
 * <p>⚠️ <b>Not derived from {@code heat.areaSpots}</b>, which an earlier cut did: away from home, an
 * origin region with no heat spots made {@code areaSpots} empty, so the list was empty and every
 * region-scoped topic showed (no filter), where Plan filters to the origin's region. A region with
 * no spots was likewise dropped. The caller passes the names {@code scopeRegions} computed.
 *
 * @param {?Array<string>} regionNames {@code scopeRegions(...)}'s result
 * @param {boolean} on whether the tab's area scope is on
 * @returns {string[]} the unique non-empty region names, in first-seen order
 */
export function mapScopeNames(regionNames, on) {
  if (!on || !Array.isArray(regionNames)) return [];
  const names = [];
  const seen = new Set();
  for (const name of regionNames) {
    if (typeof name === 'string' && name !== '' && !seen.has(name)) {
      seen.add(name);
      names.push(name);
    }
  }
  return names;
}

/**
 * The places a chip rings.
 *
 * <ul>
 *   <li>A whole-sky type ({@link isWholeSkyTopic}, fail-open) returns {@code null}: no rings, the coverage list it carries is not an
 *       eligibility roster (§1.2).</li>
 *   <li>Otherwise the union of the topics' {@code locationNames}, joined byte-identically to the
 *       SCOPED ROSTER through the shared {@link spotsNamed} joiner. The roster is the scoped
 *       catalogue, <b>not</b> the label spots: a stood-down or star-floored place is still ringed
 *       (the big-seas F2 lesson), and a name with no roster entry (a disabled place) does not join.</li>
 * </ul>
 *
 * @param {string} type the topic type
 * @param {Array<{locationNames?: string[]}>} topics the chip's served topics
 * @param {?Array<{name: string, regionName?: string}>} roster the scoped roster
 * @param {?Array<string>} scopeNames the region scope, applied to the roster once more
 *        (idempotent for a roster that is already scoped)
 * @returns {?Array<object>} the roster spots to ring, or null for a whole-sky type
 */
export function placesFor(type, topics, roster, scopeNames) {
  // The same fail-open rule `topicInScope` uses: an unrecognised type is sky-wide, never ringed.
  if (isWholeSkyTopic(type)) return null;
  const names = [];
  for (const topic of Array.isArray(topics) ? topics : []) {
    if (Array.isArray(topic?.locationNames)) names.push(...topic.locationNames);
  }
  const scope = new Set(Array.isArray(scopeNames) ? scopeNames : []);
  const scoped = (Array.isArray(roster) ? roster : [])
    .filter((spot) => scope.size === 0 || !spot?.regionName || scope.has(spot.regionName));
  return spotsNamed(scoped, names);
}

/**
 * The Big seas day's ringed places, joined by id: {@code structures[].locationId} to the full
 * roster, never to names.
 *
 * @param {?{structures?: Array<{locationId: *}>}} day the served big-seas day
 * @param {?Map<*, object>} locationsById the full roster by id
 * @returns {Array<object>} the structures' locations that joined
 */
export function bigSeasPlaces(day, locationsById) {
  const out = [];
  for (const structure of Array.isArray(day?.structures) ? day.structures : []) {
    const loc = locationsById?.get?.(structure?.locationId);
    if (loc) out.push(loc);
  }
  return out;
}

/** The chip's tide-at-the-light clause: "HW on the light" / "LW on the light" / "mid tide", or null. */
function chipLight(tide) {
  const fact = tideLightFact(tide);
  if (!fact) return null;
  if (!fact.align) return { align: null, text: 'mid tide' };
  return { align: fact.align, text: `${fact.align} on the light` };
}

/** "66 coastal" / "12 places" / "1 place": the visible `where`, or null for none. */
function whereText(count, noun) {
  if (count <= 0) return null;
  if (noun) return `${count} ${noun}`;
  return `${count} ${count === 1 ? 'place' : 'places'}`;
}

/** The accessible form of {@link whereText}: "66 coastal places", "1 coastal place", "4 structures". */
function whereLabel(count, noun) {
  if (count <= 0) return null;
  const word = count === 1 ? 'place' : 'places';
  return noun ? `${count} ${noun} ${word}` : `${count} ${word}`;
}

/** The served topics on a row, in served order, before scope. */
function rowTopics(row, topicIndex, hotTopics) {
  if (!row?.date) return [];
  // The daylight High-water row (M3) is Big seas only: it is not a forecast window, so no topic
  // belongs to it (the index has no key for it either; this states the rule).
  if (row.kind === 'hw') return [];
  if (NIGHT_ROW_KINDS.has(row.kind)) {
    // A night row has no served badges (map-tab-v2 §6 O-10); it shows its own night's NIGHT topics
    // (plan §1.6, Q3), never the evening's sunset topics, which would put dust on an astro night.
    return (Array.isArray(hotTopics) ? hotTopics : [])
      .filter((t) => t?.eventType === 'NIGHT' && t.date === row.date);
  }
  return topicIndex?.get?.(windowKey(row.date, row.eventType)) ?? [];
}

/**
 * The chips for the window on screen.
 *
 * <p>Solar rows (past and filler included) read the index by the row's window key; night rows read
 * that night's NIGHT topics; both go through {@link topicInScope}. Topics of one type are one chip,
 * in served order. Big seas is prepended on every solar AND night row of a date whose big-seas line
 * is in its full form, so the chips agree with the line, which shows on that date's night rows too.
 *
 * @param {object} args
 * @param {?object} args.row the EV row on screen
 * @param {?Map<string, Array<object>>} args.topicIndex {@code buildTopicIndex(hotTopics)}
 * @param {?Array<object>} args.hotTopics the served {@code briefing.hotTopics}
 * @param {?Array<string>} args.scopeNames the region scope ({@link mapScopeNames})
 * @param {?object} [args.bigSeasDay] the served day when the line is in its full form, else null
 * @param {?Array<object>} [args.roster] the scoped roster rings join to
 * @param {?Map<*, object>} [args.locationsById] the full roster by id, for Big seas' structures
 * @param {boolean} [args.tideLight] draw the tide-at-the-light clause on the tide and surge chips of a
 *        solar row (Pro and Admin only: the caller passes {@code !tideLocked}); the clause is the
 *        window's served {@code lightAlignment}, never computed here
 * @returns {Array<{type: string, name: string, shortName: string, glyph: ?string, colour: string,
 *          where: ?string, label: string, wholeSky: boolean, bigSeas: boolean, topics: object[],
 *          places: ?object[]}>} one entry per chip; empty when the window has none
 */
export function topicsOnRow({
  row, topicIndex, hotTopics, scopeNames, bigSeasDay = null, roster = null, locationsById = null,
  tideLight = false,
}) {
  const chips = [];
  if (bigSeasDay && row) {
    const places = bigSeasPlaces(bigSeasDay, locationsById);
    const where = places.length > 0 ? `${places.length} ${places.length === 1 ? 'structure' : 'structures'}` : null;
    // On a daylight High-water day the chip carries the time ("Big seas · HW 12:40", plan §1.14).
    const hwTime = bigSeasDay.daylightHighWater === true ? formatEventTimeUk(bigSeasDay.hwTime) : null;
    const name = hwTime ? `${BIG_SEAS_STYLE.name} · HW ${hwTime}` : BIG_SEAS_STYLE.name;
    chips.push({
      type: BIG_SEAS_TYPE,
      ...BIG_SEAS_STYLE,
      name,
      where,
      label: where ? `${name}, ${where}` : name,
      wholeSky: false,
      bigSeas: true,
      topics: [],
      places,
    });
  }
  const byType = new Map();
  for (const topic of rowTopics(row, topicIndex, hotTopics)) {
    if (!topic || !topicInScope(topic, scopeNames)) continue;
    const type = String(topic.type || '').toUpperCase();
    const group = byType.get(type);
    if (group) group.push(topic);
    else byType.set(type, [topic]);
  }
  for (const [type, topics] of byType) {
    const style = topicStyle(type, topics[0].label);
    const places = placesFor(type, topics, roster, scopeNames);
    const wholeSky = places === null;
    const count = places?.length ?? 0;
    const where = wholeSky ? SKY_WIDE : whereText(count, style.placeNoun);
    const detail = wholeSky ? SKY_WIDE : whereLabel(count, style.placeNoun);
    const light = tideLight && row?.kind === 'solar' && TIDE_LIGHT_TYPES.has(type)
      ? chipLight(row.tide) : null;
    const baseLabel = detail ? `${style.name}, ${detail}` : style.name;
    chips.push({
      type,
      name: style.name,
      shortName: style.shortName,
      glyph: style.glyph,
      colour: style.colour,
      where,
      // The visible words lead the name (WCAG 2.5.3: the name contains the label).
      label: light ? `${style.name}, ${light.text}${detail ? `, ${detail}` : ''}` : baseLabel,
      light,
      wholeSky,
      bigSeas: false,
      topics,
      places,
    });
  }
  return chips;
}

/**
 * What the tab's status region says when a chip is toggled (the existing {@code map-status}; plan
 * §3 M1 forbids a new live region).
 *
 * <p>The "N ... ringed" clause is spoken ONLY when rings are actually drawn ({@code ringed}): a
 * selection that draws nothing (heat not offered, no place joined) must not claim places on the map.
 * It uses the chip's own noun: Big seas rings structures, every other located topic rings places.
 *
 * @param {?{name: string, wholeSky: boolean, bigSeas?: boolean, places: ?Array}} chip the chip toggled
 * @param {boolean} selected whether it is now selected
 * @param {boolean} [ringed] whether rings are drawn for it
 * @returns {string} the sentence
 */
export function topicAnnouncement(chip, selected, ringed = false) {
  if (!chip) return '';
  if (!selected) return `${chip.name} cleared.`;
  if (chip.wholeSky) {
    return `${chip.name} selected. Sky-wide: every location in range sees it or none does.`;
  }
  const count = ringed ? (chip.places?.length ?? 0) : 0;
  if (count === 0) return `${chip.name} selected.`;
  const noun = chip.bigSeas ? 'structure' : 'place';
  return `${chip.name} selected. ${count} ${count === 1 ? noun : `${noun}s`} ringed on the map.`;
}

/**
 * The one-line notice a whole-sky selection prints in the chips row instead of rings (plan §3 M1,
 * Q4c): "Aurora possible \u00B7 sky-wide \u2014 every location in range sees it or none does".
 * Aurora is the one type whose name wants "possible" after it; the others read as their own name.
 *
 * @param {?{type: string, name: string, wholeSky: boolean}} chip the selected chip
 * @returns {?string} the sentence, or null when the chip is not whole-sky
 */
export function skyWideNotice(chip) {
  if (!chip?.wholeSky) return null;
  const subject = chip.type === 'AURORA' ? `${chip.name} possible` : chip.name;
  return `${subject} \u00B7 sky-wide \u2014 every location in range sees it or none does`;
}

// \u2500\u2500 The four-day strip (hot-topics-by-day-plan \u00A73 M2) \u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500

/** Days the strip shows: today and the next three, the Plan horizon. */
export const GRID_DAYS = 4;

/** Columns: {@link GRID_DAYS} days times (sunrise, sunset). */
export const GRID_COLUMNS = GRID_DAYS * 2;

const DOW_FORMAT = new Intl.DateTimeFormat('en-GB', { weekday: 'long', timeZone: 'UTC' });

/** "Friday" for a calendar date string: the date has no zone, so UTC noon names it exactly. */
function weekdayOf(dateStr) {
  return DOW_FORMAT.format(new Date(`${dateStr}T12:00:00Z`));
}

/** The column a solar window sits in, by date, or -1 when its date is not one of the strip's days. */
function solarColumn(dates, date, eventType) {
  const day = dates.indexOf(date);
  if (day < 0) return -1;
  return day * 2 + (eventType === 'SUNRISE' ? 0 : 1);
}

/**
 * The column of the window on screen.
 *
 * <p>A solar row sits in its own date's column. A night row sits under its date's sunset (down), the
 * evening it starts on: EXCEPT the night in progress before dawn, which D-14 dates as YESTERDAY, so a
 * date before {@code todayStr}. That night's NIGHT topic also sits under today's sunrise (up), and the
 * reader is in the small hours of it, so it maps to column 0 (Codex P1 on #1319).
 *
 * @param {?object} activeRow the EV row on screen
 * @param {string[]} dates the strip's four dates
 * @param {string} todayStr the UK civil today
 * @param {?string} [nightDate] the night in progress ({@code resolveAuroraNight}), if known
 * @returns {number} 0 to 7, or -1 when the row is outside the strip
 */
export function currentColumnOf(activeRow, dates, todayStr, nightDate = null) {
  if (!activeRow?.date) return -1;
  if (activeRow.kind === 'solar') return solarColumn(dates, activeRow.date, activeRow.eventType);
  if (!NIGHT_ROW_KINDS.has(activeRow.kind)) return -1;
  if (activeRow.date < todayStr) {
    // Only YESTERDAY's night, and only while it is still running (before dawn: `nightDate` says so, the
    // calendar cannot). An ended night, or any older one, is not under today's sunrise.
    return activeRow.date === shiftDateStr(todayStr, -1) && !isNightOver(activeRow.date, { todayStr, nightDate })
      ? 0 : -1;
  }
  return solarColumn(dates, activeRow.date, 'SUNSET');
}

/** Contiguous runs of true in {@code on} as {@code [{start, end}]} (inclusive column indexes). */
function runsOf(on) {
  const runs = [];
  let c = 0;
  while (c < on.length) {
    if (!on[c]) {
      c += 1;
    } else {
      let end = c;
      while (end + 1 < on.length && on[end + 1]) end += 1;
      runs.push({ start: c, end });
      c = end + 1;
    }
  }
  return runs;
}

/**
 * The model of the four-day strip: eight columns (today to +3, each a sunrise and a sunset, BY DATE),
 * and one row per hot topic type that is on any of them.
 *
 * <ul>
 *   <li><b>Columns</b> carry their EV row when the list has one; a column without a row is drawn but
 *       not interactive. A PAST row (today's elapsed window) is drawn but not interactive either.</li>
 *   <li><b>Rows</b> are the union, over the eight windows, of the per-window topics from
 *       {@code topicIndex} (never {@code row.badges}: a filler or day-4 row carries none), scoped
 *       through {@link topicInScope}, in the served order. Big seas leads, "on" in both columns of
 *       each served big-seas day whose window is still ahead.</li>
 *   <li><b>Bars</b>: contiguous "on" columns of a row merge into one bar, so a NIGHT topic (that
 *       evening's sunset and the next sunrise) is one bar.</li>
 *   <li><b>Current column</b>: {@link currentColumnOf}.</li>
 * </ul>
 *
 * A filter/map over served facts: nothing here tests weather or re-derives a verdict.
 *
 * @param {object} args
 * @param {?Array<object>} args.events the built EV list ({@code buildMapEvents})
 * @param {?Map<string, Array<object>>} args.topicIndex {@code buildTopicIndex(hotTopics)}
 * @param {?Array<object>} args.hotTopics the served {@code briefing.hotTopics} (for the order)
 * @param {?Array<string>} args.scopeNames the region scope ({@link mapScopeNames})
 * @param {?Array<object>} [args.bigSeas] the served {@code briefing.bigSeas}
 * @param {string} args.todayStr the UK civil today
 * @param {?object} [args.activeRow] the EV row on screen
 * @param {?string} [args.nightDate] the night in progress, for the pre-dawn night's column
 * @param {Date} [args.now] the app clock
 * @param {boolean} [args.tideMarks] draw the tide-at-the-light treatment on the tide and surge rows
 *        (marks and the sub-line) and the grid's {@code tideDetail}: Pro and Admin only, so the
 *        caller passes {@code !tideLocked}. Every figure is served ({@code tide.lightAlignment},
 *        {@code tideRun.peak}/{@code range}); this only filters, maps and selects
 * @returns {{days: Array<{date: string, dow: string, dayNum: string, today: boolean}>,
 *   columns: Array<{index: number, date: string, eventType: string, row: ?object,
 *   interactive: boolean, current: boolean}>, currentColumn: number,
 *   rows: Array<{type: string, name: string, shortName: string, glyph: ?string, colour: string,
 *   bigSeas: boolean, on: boolean[], bars: Array<{start: number, end: number}>}>}}
 */
export function fourDayGrid({
  events, topicIndex, hotTopics, scopeNames, bigSeas = null, todayStr, activeRow = null,
  nightDate = null, now = appNow(), tideMarks = false,
}) {
  const dates = Array.from({ length: GRID_DAYS }, (_, i) => shiftDateStr(todayStr, i));
  const days = dates.map((date, i) => ({
    date, dow: weekdayOf(date), dayNum: date.slice(8).replace(/^0/, ''), today: i === 0,
  }));
  const rowByColumn = new Array(GRID_COLUMNS).fill(null);
  for (const ev of Array.isArray(events) ? events : []) {
    if (ev?.kind !== 'solar') continue;
    const c = solarColumn(dates, ev.date, ev.eventType);
    if (c >= 0 && !rowByColumn[c]) rowByColumn[c] = ev;
  }
  const currentColumn = currentColumnOf(activeRow, dates, todayStr, nightDate);
  // The span the "you are here" shade covers: one column for a window, and the whole day (both of its
  // columns) for the daylight High-water row, which sits between the lights and belongs to neither.
  let currentSpan = currentColumn >= 0 ? { start: currentColumn, end: currentColumn } : null;
  if (activeRow?.kind === 'hw') {
    const d = dates.indexOf(activeRow.date);
    if (d >= 0) currentSpan = { start: d * 2, end: d * 2 + 1 };
  }
  const columns = rowByColumn.map((row, index) => ({
    index,
    date: dates[Math.floor(index / 2)],
    eventType: index % 2 === 0 ? 'SUNRISE' : 'SUNSET',
    row,
    interactive: row != null && row.past !== true,
    current: index === currentColumn,
  }));

  // type -> { on: boolean[], label } over the eight windows, from the index, scoped.
  const byType = new Map();
  columns.forEach((col) => {
    const topics = topicIndex?.get?.(windowKey(col.date, col.eventType)) ?? [];
    for (const topic of topics) {
      if (!topic || !topicInScope(topic, scopeNames)) continue;
      const type = String(topic.type || '').toUpperCase();
      let entry = byType.get(type);
      if (!entry) {
        entry = { on: new Array(GRID_COLUMNS).fill(false), label: topic.label, topics: [] };
        byType.set(type, entry);
      }
      entry.on[col.index] = true;
      if (!entry.topics.includes(topic)) entry.topics.push(topic);
    }
  });
  // Served order: the first appearance of each type in `hotTopics`.
  const order = [];
  for (const topic of Array.isArray(hotTopics) ? hotTopics : []) {
    const type = String(topic?.type || '').toUpperCase();
    if (byType.has(type) && !order.includes(type)) order.push(type);
  }
  const rows = [];
  const seasOn = new Array(GRID_COLUMNS).fill(false);
  for (const day of Array.isArray(bigSeas) ? bigSeas : []) {
    const d = dates.indexOf(day?.date);
    if (d >= 0 && bigSeasWindowAhead(day, now)) {
      seasOn[d * 2] = true;
      seasOn[d * 2 + 1] = true;
    }
  }
  if (seasOn.some(Boolean)) {
    rows.push({
      type: BIG_SEAS_TYPE, ...BIG_SEAS_STYLE, bigSeas: true, on: seasOn, bars: runsOf(seasOn),
    });
  }
  for (const type of order) {
    const { on, label, topics } = byType.get(type);
    const style = topicStyle(type, label);
    const row = {
      type,
      name: style.name,
      shortName: style.shortName,
      glyph: style.glyph,
      colour: style.colour,
      bigSeas: false,
      on,
      bars: runsOf(on),
    };
    if (tideMarks && TIDE_LIGHT_TYPES.has(type)) addTideLight(row, columns, topics);
    rows.push(row);
  }
  const grid = {
    days, columns, currentColumn, currentSpan, rows, hwTicks: hwTicksOf(events, dates, activeRow),
    // The ring on a tide mark and the detail line speak for a SOLAR window only: a night row's column
    // (its dusk's sunset, or column 0 before dawn) is not a light the tide was measured against.
    currentSolar: activeRow?.kind === 'solar',
  };
  if (tideMarks) grid.tideDetail = tideDetailOf(grid, activeRow);
  return grid;
}

/**
 * Adds the tide-at-the-light treatment to one strip row, in place: {@code marks} (a mark per "on"
 * column whose window is served HW or LW on the light; each carries the words for the bar's
 * accessible name, with the water's time and its gap) and {@code sub} (tide runs only: the biggest
 * day ON THE STRIP and its range, from the served {@code tideRun.peak}/{@code range}).
 *
 * <p>⚠️ <b>{@code peak} is the biggest VISIBLE day, not the run's peak</b>: the tide-run fields are
 * bounded by the topic window (hot-topics-by-day-plan §1.10, C1), so a run whose true peak lies past
 * the four days still has a "peak" here. Nothing served says whether a run continues past the strip
 * (that signal is the almanac, which does not reach {@code MapView}), so the sub-line states only the
 * biggest day shown and the bar gets NO open edge: it would assert a continuation this code cannot
 * know.
 *
 * <p>With no served {@code lightAlignment} on any covered window (no tide data, or a Lite reader) the
 * row is left exactly as it was: no marks, no sub-line.
 */
function addTideLight(row, columns, topics) {
  const marks = [];
  let served = false;
  row.on.forEach((on, c) => {
    const fact = on ? tideLightFact(columns[c].row?.tide) : null;
    if (!fact) return;
    served = true;
    if (!fact.align) return;
    const detail = [fact.time, gapOf(fact.offset)].filter(Boolean).join(', ');
    marks.push({
      column: c,
      align: fact.align,
      spoken: `${KIND_WORD[fact.align]} water on the light ${columnWordOf(columns, c)}${detail ? `, ${detail}` : ''}`,
    });
  });
  row.marks = marks;
  if (served && TIDE_RUN_TYPES.has(row.type)) {
    const peak = topics.find((t) => t?.tideRun?.peak === true && t.date);
    if (peak) row.sub = { dow: weekdayOf(peak.date), range: peak.tideRun.range ?? null };
  }
}

/** "Friday sunrise" from a bare columns array (the grid's {@link columnWord} needs the days too). */
function columnWordOf(columns, c) {
  const col = columns[c];
  return `${weekdayOf(col.date)} ${col.eventType === 'SUNRISE' ? 'sunrise' : 'sunset'}`;
}

/**
 * The one full-width line under the grid for the window on the map, or null. Only for a solar
 * window that a tide-at-the-light row covers (the tide runs sort before the surge, so a window both
 * cover is drawn once and the tide's fact is the one read; {@code type} names the row it came from), and only when the window's served fact carries an alignment.
 *
 * @param {object} grid the grid so far
 * @param {?object} activeRow the EV row on screen
 * @returns {?object} the line's served pieces
 */
function tideDetailOf(grid, activeRow) {
  const c = grid.currentColumn;
  if (c < 0 || activeRow?.kind !== 'solar') return null;
  const rows = grid.rows.filter((r) => Array.isArray(r.marks) && r.on[c]);
  rows.sort((a, b) => Number(TIDE_RUN_TYPES.has(b.type)) - Number(TIDE_RUN_TYPES.has(a.type)));
  for (const row of rows) {
    const fact = tideLightFact(grid.columns[c].row?.tide);
    if (!fact) continue;
    return {
      column: c,
      type: row.type,
      sunWord: columnWordOf(grid.columns, c).replace(/^(\w{3})\w*/, '$1'),
      eventTime: fact.eventTime,
      align: fact.align,
      kindWord: KIND_WORD[fact.kind],
      time: fact.time,
      gap: gapOf(fact.offset),
      height: fact.align ? fact.height : null,
      station: fact.station,
    };
  }
  return null;
}

/** A tick never touches the day's edge: clamped to 4-96% of the two columns (plan §3 M3). */
const TICK_MIN_PCT = 4;
const TICK_MAX_PCT = 96;

/**
 * Where high water sits between the day's served sunrise and sunset, as a percentage of the day's two
 * columns, or null when it cannot be placed (a bound is missing or unparseable, or the day has no
 * length). Licensed geometry, like the tide sparkline: the instants are SERVED (B1's
 * {@code daylightStart}/{@code daylightEnd}), the comparison "between the lights" is the server's.
 *
 * @param {{hwTime: ?string, daylightStart: ?string, daylightEnd: ?string}} row the High-water EV row
 * @returns {?number} 4 to 96, or null
 */
export function hwTickPercent(row) {
  const hw = parseUtcInstant(row?.hwTime);
  const start = parseUtcInstant(row?.daylightStart);
  const end = parseUtcInstant(row?.daylightEnd);
  if (!hw || !start || !end) return null;
  const span = end.getTime() - start.getTime();
  if (!(span > 0)) return null;
  const pct = ((hw.getTime() - start.getTime()) / span) * 100;
  return Math.min(TICK_MAX_PCT, Math.max(TICK_MIN_PCT, pct));
}

/**
 * The strip's High-water ticks: one per daylight High-water EV row whose date is one of the strip's
 * days and whose bounds were served. No bounds, no tick.
 *
 * @param {?Array<object>} events the built EV list
 * @param {string[]} dates the strip's four dates
 * @param {?object} activeRow the EV row on screen
 * @returns {Array<{date: string, day: number, percent: number, time: string, dow: string,
 *   row: object, current: boolean}>}
 */
function hwTicksOf(events, dates, activeRow) {
  const ticks = [];
  for (const ev of Array.isArray(events) ? events : []) {
    if (ev?.kind !== 'hw') continue;
    const day = dates.indexOf(ev.date);
    const percent = hwTickPercent(ev);
    if (day < 0 || percent == null) continue;
    ticks.push({
      date: ev.date,
      day,
      percent,
      time: formatEventTimeUk(ev.hwTime) || ev.time || '',
      dow: weekdayOf(ev.date),
      row: ev,
      current: activeRow?.id === ev.id,
    });
  }
  return ticks;
}

/**
 * Where a press on a bar goes. A bar that covers the current column AND whose topic is on the
 * current row selects the topic where the reader already is ({@code stay}); otherwise the target is
 * the current column's window if the bar covers it and it has a row, else the bar's first column
 * that has one. {@code null} when the bar covers no window that can be opened.
 *
 * @param {{columns: Array, currentColumn: number}} grid {@link fourDayGrid}'s result
 * @param {{start: number, end: number}} bar
 * @param {boolean} onCurrentRow whether the bar's topic is a chip on the window on screen
 * @returns {?{stay: boolean, column: number}} what to do
 */
export function barTarget(grid, bar, onCurrentRow) {
  const cur = grid.currentColumn;
  // The daylight High-water row covers its whole day, so `currentSpan` (when the grid has one) decides.
  const span = grid.currentSpan ?? (cur >= 0 ? { start: cur, end: cur } : null);
  const covers = span != null && span.start <= bar.end && span.end >= bar.start;
  if (covers && onCurrentRow) return { stay: true, column: cur >= 0 ? cur : span.start };
  if (covers && grid.columns[cur]?.interactive) return { stay: false, column: cur };
  for (let c = bar.start; c <= bar.end; c += 1) {
    if (grid.columns[c]?.interactive) return { stay: false, column: c };
  }
  return null;
}

/**
 * Where a press on a row's label goes: a toggle when the topic is on the current row, else a jump to
 * the row's first column that has a window to open. {@code null} when it has none.
 *
 * @param {{columns: Array}} grid {@link fourDayGrid}'s result
 * @param {{on: boolean[]}} topicRow the strip row
 * @param {boolean} onCurrentRow whether the topic is a chip on the window on screen
 * @returns {?({toggle: true}|{toggle: false, column: number})}
 */
export function labelTarget(grid, topicRow, onCurrentRow) {
  if (onCurrentRow) return { toggle: true };
  const column = topicRow.on.findIndex((on, c) => on && grid.columns[c]?.interactive);
  return column < 0 ? null : { toggle: false, column };
}

/**
 * The tide-at-the-light words for one bar's accessible name ("high water on the light Friday sunrise,
 * low water on the light Saturday sunset"), from the row's served marks inside the bar; '' for none.
 */
export function markPhrase(topicRow, bar) {
  return (topicRow.marks ?? [])
    .filter((m) => m.column >= bar.start && m.column <= bar.end)
    .map((m) => m.spoken)
    .join('; ');
}

/** "Friday sunrise" / "Saturday sunset" for a column, from the strip's day names. */
export function columnWord(grid, column) {
  const c = grid.columns[column];
  const day = grid.days[Math.floor(column / 2)];
  return `${day.dow} ${c.eventType === 'SUNRISE' ? 'sunrise' : 'sunset'}`;
}
