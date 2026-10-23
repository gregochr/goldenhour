import { buildDateRail } from './comingUpFeed.js';
import { laneDates, lastPlanDateStr, PLAN_OWNED_DAYS } from './comingUpHandoff.js';
import { shiftDateStr } from './mapDates.js';
import { topicStyle } from './topicPalette.js';

/**
 * The Coming up tab's "Next four days" lanes — the model behind {@code WindowComingUpFourDays}
 * (hot-topics-by-day-plan §3 C1; it supersedes the dashed handoff row, coming-up-plan D14).
 *
 * <p><b>Pure, client-side, and licensed as a filter/map member</b> (architecture-decisions
 * "Backend-heavy"): it only re-buckets {@code briefing.hotTopics} by the windows the shared
 * {@code topicWindowKeys} join already puts them on, and reads the served almanac entries to name a
 * jump target. It adds no fact the server did not state — which window a topic belongs to, whether
 * a tide is the peak day and what light it aligns with are all served fields.
 *
 * <p><b>One row per topic type</b>, in served order — a type with any topic whose
 * {@code laneDates} fall inside the four days (the same helper the retired handoff row counted
 * with, so an unanchored topic and a yesterday-dated NIGHT topic still count). A
 * topic spanning consecutive days is a <em>bar</em>, a single day a <em>dot</em>.
 *
 * <p><b>Peak is served for tides only</b> (§1.10): {@code TideRunDay.peak} marks the run's biggest
 * day, {@code alignedEvent} the light it lands in. Nothing else prints a peak.
 *
 * <p><b>"Open" is a served fact, not a count the topic carries.</b> The backend builds a tide topic's
 * {@code TideRunDay} from the days inside Plan's window only ({@code dayCount} is that in-window
 * count), so on day 4 {@code dayNumber == dayCount} always and {@code peak} marks the biggest
 * <em>visible</em> day — neither says whether the run goes on. What does is the almanac: the server
 * keeps a tide entry in {@code /api/almanac} only when it ends after the last Plan day, so a tide
 * row whose segment ends on day 4 is open exactly when the FULL served entry list (not only the
 * rendered ids) holds a spring- or king-tide entry — by {@code type} or any
 * {@code coincidence[].type}, since a merged card takes its host's type — whose TIDE continues past
 * day 4. For an unmerged entry that is its own {@code endDate}; for a merged one the dates are a
 * union with the other topic (a supermoon can run on after the tide ends), so they are never read
 * and the tide's own {@code dayRanges} decide (see {@code tideContinues}).
 * Either tide type counts for either topic, because the hot topic and the almanac can label one
 * physical run king vs spring differently. That is a filter/select over served almanac fields. A
 * NIGHT topic is open by the other served fact: its next-morning key falls after day 4. No peak
 * text is printed on an open segment (the visible peak may not be the run's).
 *
 * <p><b>The tap target is a window key</b>, never "the segment's date + sunset" — the first key
 * {@code topicWindowKeys} produced inside the segment that Plan actually renders
 * ({@code openableKeys}), which is what makes a yesterday-dated NIGHT topic land on this morning's
 * sunrise and a day with two MIST topics land on the first. With none, the target is null and the
 * caller falls back to plain {@code goToPlan()}.
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
const LAST_INDEX = PLAN_OWNED_DAYS - 1;
const TIDE_TOPICS = new Set(['KING_TIDE', 'SPRING_TIDE']);

/** `Friday` — long weekday, for the spoken forms. */
function longDow(dateStr) {
  return new Date(`${dateStr}T12:00:00Z`)
    .toLocaleDateString('en-GB', { weekday: 'long', timeZone: 'UTC' });
}

/**
 * Whether the TIDE in an almanac entry is still running past the last Plan day — decided from the
 * tide's own span, never from a span that may belong to something else.
 *
 * <p>An unmerged tide entry ({@code coincidence} empty) is the tide's own span: it continues iff it
 * started by {@code lastPlan} and its {@code endDate} is after it. A MERGED entry's
 * {@code startDate}/{@code endDate} are the UNION of every merged topic's span
 * ({@code ComingUpAssembler.mergeEntries}) — a tide that ends on day 4 merged with a supermoon that
 * runs to day 7 has an {@code endDate} on day 7 — so that date is NEVER read. The tide's own days
 * ride {@code dayRanges}, "carried onto a merged entry whichever topic hosts"; the tide continues iff
 * those days begin by {@code lastPlan} and the last one is after it. A merged entry with no
 * {@code dayRanges} offers no evidence, so it does not continue: the conservative answer is a closed
 * lane that still shows its peak.
 *
 * @param {object} entry a served almanac entry
 * @param {string} lastPlan the last Plan date, `YYYY-MM-DD`
 * @returns {boolean} true only when the entry holds a tide run that goes past `lastPlan`
 */
function tideContinues(entry, lastPlan) {
  const tideTypes = ALMANAC_TYPES_BY_TOPIC.KING_TIDE;
  const lines = entry?.coincidence ?? [];
  const holdsTide = tideTypes.includes(entry?.type)
    || lines.some((line) => tideTypes.includes(line?.type));
  if (!holdsTide) return false;
  if (lines.length === 0) {
    return Boolean(entry.startDate) && entry.startDate <= lastPlan && entry.endDate > lastPlan;
  }
  const days = (entry.dayRanges ?? []).map((d) => d?.date).filter(Boolean).sort();
  return days.length > 0 && days[0] <= lastPlan && days[days.length - 1] > lastPlan;
}

/** Whether any served entry holds a tide run that continues past the last Plan day. */
function tideRunStraddles(entries, lastPlan) {
  return Array.isArray(entries) && entries.some((entry) => tideContinues(entry, lastPlan));
}

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

/** `10–15 Sept`, `12 Sept` or `26 Sept–1 Oct` — the date rail's own two slots, joined. */
function entryDates(entry, todayStr) {
  const rail = buildDateRail(entry.startDate, entry.endDate, todayStr);
  return rail.month.startsWith('–') ? `${rail.day}${rail.month}` : `${rail.day} ${rail.month}`;
}

/**
 * The list entry a jump button scrolls to, or null.
 *
 * <p>Matches {@code entry.type} <b>or</b> any {@code coincidence[].type} (a merged card takes its
 * host's type, so a supermoon-hosted card carries the tide only in a coincidence line — the rule
 * {@code matchesFilter} already uses), restricted to the entry ids the month list is actually
 * rendering under the active chip. A straddling run (a tide's own span started by the last Plan day
 * and ends after it) wins; otherwise the next run.
 */
function jumpFor(type, entries, renderedEntryIds, lastPlan, todayStr) {
  const wanted = ALMANAC_TYPES_BY_TOPIC[type];
  if (!wanted || !Array.isArray(entries)) return null;
  const rendered = renderedEntryIds instanceof Set ? renderedEntryIds : new Set(renderedEntryIds ?? []);
  const matches = entries
    .filter((entry) => entry?.id != null && rendered.has(entry.id) && entry.startDate
      && (wanted.includes(entry.type)
        || (entry.coincidence ?? []).some((line) => wanted.includes(line?.type))))
    .sort((a, b) => (a.startDate < b.startDate ? -1 : a.startDate > b.startDate ? 1 : 0));
  if (matches.length === 0) return null;
  // "This run in the list": for a tide, the tide's OWN span straddles (see tideContinues — a merged
  // card's union dates would claim a tide that ended on day 4 as "this run"); otherwise the entry's.
  const isTide = TIDE_TOPICS.has(type);
  const straddling = matches.find((entry) => (isTide
    ? tideContinues(entry, lastPlan) : entry.startDate <= lastPlan));
  const entry = straddling ?? matches[0];
  const dates = entryDates(entry, todayStr);
  return {
    entryId: entry.id,
    label: `${straddling ? 'In the list' : 'Next run in the list'} · ${dates}`,
    dates,
    straddling: Boolean(straddling),
  };
}

/** `↑`, `↓`, `↑↓` or '' from the windows a day carries. */
function windowGlyph(windows) {
  const up = windows.has(SUNRISE);
  const down = windows.has(SUNSET);
  if (up && down) return '↑↓';
  if (up) return '↑';
  if (down) return '↓';
  return '';
}

/** The peak clause of a bar, from the first peak-day tide topic inside it — or ''. */
function peakText(topics) {
  const peakTopic = topics.find((topic) => topic?.tideRun?.peak === true);
  if (!peakTopic) return { text: '', spoken: '' };
  const light = String(peakTopic.tideRun.alignedEvent ?? '').toLowerCase();
  let suffix = '';
  let spokenSuffix = '';
  if (light === 'sunrise') { suffix = ' · ↑ sunrise'; spokenSuffix = ' at sunrise'; }
  else if (light === 'sunset') { suffix = ' · ↓ sunset'; spokenSuffix = ' at sunset'; }
  return {
    text: `peak ${dow(peakTopic.date)}${suffix}`,
    spoken: `peak ${longDow(peakTopic.date)}${spokenSuffix}`,
  };
}

/**
 * Builds the lanes.
 *
 * @param {object} args
 * @param {string} args.todayStr the reader's UK today, `YYYY-MM-DD` (from {@code appNow}, Rewind)
 * @param {?Array} args.hotTopics the live {@code briefing.hotTopics}; null/undefined before it
 *        arrives
 * @param {Set<string>|Array<string>} [args.openableKeys] the `date:TYPE` keys of Plan's window
 *        cards — Plan renders six, so many cells have no card to open
 * @param {?Array} [args.entries] the served almanac entries ({@code feed.entries})
 * @param {Set<string>|Array<string>} [args.renderedEntryIds] ids of the entries the month list is
 *        rendering now (it is chip-filtered); a jump never targets anything else
 * @returns {{days: Array<{date: string, dow: string, dayNum: string, today: boolean}>,
 *          rows: ?Array<object>}} the four day columns, and one row per topic type — null while
 *          {@code hotTopics} has not arrived (distinct from an arrived empty list)
 */
export function buildFourDayLanes({
  todayStr, hotTopics, openableKeys = null, entries = null, renderedEntryIds = null,
}) {
  if (!todayStr) return { days: [], rows: null };

  const lastPlan = lastPlanDateStr(todayStr);
  const dayDates = Array.from({ length: PLAN_OWNED_DAYS }, (_, i) => shiftDateStr(todayStr, i));
  const days = dayDates.map((date, i) => ({
    date, dow: dow(date), dayNum: dayNum(date), today: i === 0,
  }));
  if (!Array.isArray(hotTopics)) return { days, rows: null };

  const openable = openableKeys instanceof Set ? openableKeys : new Set(openableKeys ?? []);
  const dayIndex = new Map(dayDates.map((date, i) => [date, i]));

  // Group by type in served order, keeping only topics that land inside the four days.
  const byType = new Map();
  for (const topic of hotTopics) {
    if (!topic?.type || !topic.date) continue;
    const items = laneDates(topic);
    if (!items.some(({ date }) => date >= todayStr && date <= lastPlan)) continue;
    if (!byType.has(topic.type)) byType.set(topic.type, []);
    byType.get(topic.type).push({ topic, items });
  }

  const rows = [...byType.entries()].map(([type, group]) => {
    const style = topicStyle(type, group[0].topic.label);
    const cells = dayDates.map(() => ({ on: false, windows: new Set(), topics: [] }));
    let continuesPastDay4 = false;
    for (const { topic, items } of group) {
      for (const item of items) {
        if (item.date > lastPlan) continuesPastDay4 = true;
        const idx = dayIndex.get(item.date);
        if (idx === undefined) continue;
        cells[idx].on = true;
        if (item.window) cells[idx].windows.add(item.window);
        if (!cells[idx].topics.includes(topic)) cells[idx].topics.push(topic);
      }
    }
    const nightTonight = group.some(({ topic }) => topic.eventType === NIGHT && topic.date === todayStr);

    const segments = [];
    let i = 0;
    while (i < cells.length) {
      if (!cells[i].on) { i += 1; continue; }
      let end = i;
      while (end + 1 < cells.length && cells[end + 1].on) end += 1;
      segments.push(buildSegment({
        type, group, cells, start: i, end, dayDates, todayStr, openable, nightTonight,
        continuesPastDay4, entries, renderedEntryIds, lastPlan,
      }));
      i = end + 1;
    }
    return {
      type,
      name: style.name,
      shortName: style.shortName,
      glyph: style.glyph,
      colour: style.colour,
      segments,
    };
  });

  return { days, rows };
}

/** One bar or dot — see {@link buildFourDayLanes}. */
function buildSegment({
  type, group, cells, start, end, dayDates, todayStr, openable, nightTonight, continuesPastDay4,
  entries, renderedEntryIds, lastPlan,
}) {
  const span = cells.slice(start, end + 1);
  const topics = span.flatMap((cell) => cell.topics);
  const windows = new Set(span.flatMap((cell) => [...cell.windows]));
  const isBar = end > start;
  const open = end === LAST_INDEX && (continuesPastDay4
    || (TIDE_TOPICS.has(type) && tideRunStraddles(entries, lastPlan)));

  let leftText = '';
  let spokenLeft = '';
  if (isBar) {
    let startWord;
    if (start === 0) {
      const onlyViaYesterdayNight = cells[0].topics.every(
        (t) => t.eventType === NIGHT && t.date < todayStr,
      );
      if (nightTonight) startWord = 'tonight';
      else if (onlyViaYesterdayNight) startWord = 'this morning';
      else startWord = 'today';
    } else {
      startWord = dow(dayDates[start]);
    }
    leftText = open ? 'runs on' : `${startWord} → ${dow(dayDates[end])}`;
    const spokenStart = start === 0 ? startWord : longDow(dayDates[start]);
    spokenLeft = open ? `${spokenStart} onwards` : `${spokenStart} to ${longDow(dayDates[end])}`;
  }
  const peak = isBar && !open ? peakText(topics) : { text: '', spoken: '' };

  // The first key `topicWindowKeys` produced inside the segment that Plan renders.
  const firstDate = dayDates[start];
  const lastDate = dayDates[end];
  let target = null;
  for (const { items } of group) {
    const hit = items.find(
      (item) => item.key && item.date >= firstDate && item.date <= lastDate && openable.has(item.key),
    );
    if (hit) { target = hit.key; break; }
  }

  return {
    kind: isBar ? 'bar' : 'dot',
    start,
    end,
    startDate: firstDate,
    endDate: lastDate,
    glyph: isBar ? '' : windowGlyph(windows),
    windows: [SUNRISE, SUNSET].filter((w) => windows.has(w)),
    leftText,
    rightText: peak.text,
    spoken: {
      left: spokenLeft,
      peak: peak.spoken,
      day: isBar ? '' : (start === 0 ? 'today' : longDow(firstDate)),
    },
    open,
    target,
    jump: open ? jumpFor(type, entries, renderedEntryIds, lastPlan, todayStr) : null,
  };
}
