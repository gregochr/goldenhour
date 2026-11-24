import { ALMANAC_TYPES_BY_TOPIC, holdsType, tideSpan } from './fourDayTiles.js';
import { buildDateRail, formatArrivalDate } from './comingUpFeed.js';
import { entryGlyph } from './comingUpGlyphs.js';
import { shiftDateStr } from './mapDates.js';
import { topicWindowKeys } from './windowFirstTopics.js';

/**
 * Watch a date (one-screen-plan.md D16, O2b): the pure helpers behind the "Watch this date" toggle in Further ahead and
 * the date chips in Your places.
 *
 * <p><b>A named, licensed client derivation</b> (architecture-decisions.md, "Backend-heavy"): matching a stored watch to
 * a served almanac entry, and choosing which served window a watched date's chip opens, are done here. Both are joins
 * over fields the server already stated (the entry's type and dates, each window's served tide alignment and rating,
 * the served hot topics' own windows). Nothing here computes a rating, a verdict or a pick, and no fact the server did
 * not print is printed.
 *
 * <p><b>The key.</b> {@code <lower-kebab type>:<start date>} ({@link watchKeyOf}); the server validates it against the
 * almanac sources' types. It is the type and the START date, never the entry id (which carries the end date too), so a
 * tide run whose end moves as the forecast firms up still matches.
 */

/**
 * The almanac types a reader may watch: the same list the server accepts ({@code WatchDateKey.TYPES}). Not the NLC
 * season: the feed clips its served start to the feed's first day, so the key would slide forward daily and the 14-day
 * expiry would end the watch two weeks into an eleven-week season.
 */
export const WATCHABLE_TYPES = [
  'spring-tide', 'king-tide', 'supermoon', 'meteor', 'eclipse', 'lunar-eclipse', 'equinox', 'solstice',
];

/** The two tide types are one family for a watch: a run reclassified spring to king (or back) is the same watch. */
export const TIDE_FAMILY = ['spring-tide', 'king-tide'];

/**
 * How many days either side of a watched tide start the match tolerates. A tide run's start can drift by a few days as
 * the tide model firms up; a watch that stopped matching on the first drift would silently orphan.
 */
export const TIDE_DRIFT_DAYS = 3;

/** What a watch on an entry the feed no longer lists is called, from its key's type alone. */
const TYPE_NAMES = {
  'spring-tide': 'Spring tide', 'king-tide': 'King tide', supermoon: 'Supermoon', meteor: 'Meteor shower',
  eclipse: 'Solar eclipse', 'lunar-eclipse': 'Lunar eclipse', equinox: 'Equinox', solstice: 'Solstice',
};

/** What a watched chip says when its date has no window of its own to open (a meteor peak). */
export const WATCH_NO_WINDOW_LINE = 'clear-sky check on the night';

/** The two served {@code lightAlignment} values that mean the water's extreme lands on the light. */
const ON_THE_LIGHT = ['HW', 'LW'];

/**
 * The key to watch for an entry, or null when the entry cannot be watched (a Met Office warning, an unknown type, no
 * start date).
 *
 * @param {?{type?: string, startDate?: string, warning?: object}} entry a served or view entry
 * @returns {?string} {@code type:startDate}
 */
export function watchKeyOf(entry) {
  if (!entry || entry.warning || !entry.startDate || !WATCHABLE_TYPES.includes(entry.type)) return null;
  return `${entry.type}:${entry.startDate}`;
}

/**
 * A stored key's two parts.
 *
 * @param {?string} key {@code type:startDate}
 * @returns {?{type: string, date: string}} null for anything that is not a watchable type and a date
 */
export function parseWatchKey(key) {
  if (typeof key !== 'string') return null;
  const cut = key.indexOf(':');
  if (cut < 0) return null;
  const type = key.slice(0, cut);
  const date = key.slice(cut + 1);
  return WATCHABLE_TYPES.includes(type) && /^\d{4}-\d{2}-\d{2}$/.test(date) ? { type, date } : null;
}

/** The days an entry's relevant span covers: a tide's own days for a tide, else its dates. Null when none are known. */
function spanOf(entry, tide) {
  if (tide) return tideSpan(entry);
  return { first: entry.startDate, last: entry.endDate };
}

/**
 * Whether a stored watch is this served entry: the same type family (a tide matches either tide type, and an entry that
 * merges a tide into a supermoon still counts as holding it) with overlapping dates. A tide watch covers
 * {@link TIDE_DRIFT_DAYS} either side of its start; every other type matches when the entry covers the watched day.
 *
 * @param {string} key the stored watch
 * @param {object} entry a served almanac entry
 * @returns {boolean}
 */
export function entryMatchesWatch(key, entry) {
  const watch = parseWatchKey(key);
  if (!watch || !entry || entry.warning) return false;
  const tide = TIDE_FAMILY.includes(watch.type);
  if (!holdsType(entry, tide ? TIDE_FAMILY : [watch.type])) return false;
  const span = spanOf(entry, tide);
  if (!span) return false;
  const from = tide ? shiftDateStr(watch.date, -TIDE_DRIFT_DAYS) : watch.date;
  const to = tide ? shiftDateStr(watch.date, TIDE_DRIFT_DAYS) : watch.date;
  return from <= span.last && to >= span.first;
}

/**
 * The served entry a stored watch is: of those that match, the one that starts earliest (two matches can arise when
 * two runs sit within the tide tolerance of one another).
 *
 * @param {string} key the stored watch
 * @param {?Array<object>} entries the served almanac entries
 * @returns {?object} the entry, or null when none matches (or the feed has not arrived)
 */
export function matchWatchToEntry(key, entries) {
  if (!Array.isArray(entries)) return null;
  const hits = entries.filter((entry) => entryMatchesWatch(key, entry));
  if (hits.length === 0) return null;
  return hits.reduce((first, entry) => (entry.startDate < first.startDate ? entry : first), hits[0]);
}

/**
 * The stored watches that are this entry, so the toggle can read as pressed, and un-watch exactly the rows that make it
 * so (a stored key may differ from the entry's own key after a drift).
 *
 * @param {Array<{key: string}|string>} dates the reader's watched dates
 * @param {object} entry a served or view entry
 * @returns {string[]} their keys
 */
export function watchKeysMatching(dates, entry) {
  return (dates ?? []).map((d) => (typeof d === 'string' ? d : d.key))
    .filter((key) => matchWatchToEntry(key, [entry]) !== null);
}

/** The score a window is ranked on when two share the light: the reader's own best, else the window's served best. */
function ratingOf(card) {
  return card.bestReach?.rating ?? card.bestRating ?? null;
}

/** The live (not away) windows inside a span, in the strip's order. */
function windowsIn(windows, span) {
  return windows.filter((card) => !card.away && card.date >= span.first && card.date <= span.last);
}

/**
 * The window a tide watch opens: of the windows inside the run's own days, those the served tide data marks as on the
 * light ({@code tide.lightAlignment} HW or LW); the better-rated wins, the earlier when they tie. Null when none is
 * marked.
 */
function tideWindow(cards) {
  let best = null;
  for (const card of cards) {
    if (!ON_THE_LIGHT.includes(card.tide?.lightAlignment)) continue;
    const rating = ratingOf(card) ?? -1;
    if (best === null || rating > best.rating) best = { card, rating };
  }
  return best?.card ?? null;
}

/** The hot-topic types that describe an almanac type (the inverse of {@code ALMANAC_TYPES_BY_TOPIC}). */
function topicTypesFor(almanacType) {
  return Object.entries(ALMANAC_TYPES_BY_TOPIC)
    .filter(([, almanac]) => almanac.includes(almanacType)).map(([topicType]) => topicType);
}

/**
 * The window a topic's own anchor names: of the served hot topics that describe this entry and fall inside its span,
 * the earliest one's first window key that has a live card (a dusk moonrise is a {@code NIGHT} topic, whose first key is
 * that evening's sunset). Null when the served topics name none.
 */
function topicWindow(type, cards, hotTopics, span) {
  const wanted = topicTypesFor(type);
  const byKey = new Map(cards.map((card) => [card.key, card]));
  return (hotTopics ?? [])
    .filter((topic) => wanted.includes(topic.type) && topic.date >= span.first && topic.date <= span.last)
    .sort((a, b) => a.date.localeCompare(b.date))
    .flatMap((topic) => topicWindowKeys(topic))
    .map((key) => byKey.get(key))
    .find(Boolean) ?? null;
}

/**
 * The plan for a watched entry once its dates reach the forecast.
 *
 * @param {object} entry the matched served entry
 * @param {{type: string}} watch the parsed stored watch (its type decides the rule, not the host entry's)
 * @returns {{state: 'window', card: object, tideOnLight: boolean}|{state: 'static'}|null} the window to open; a chip
 *          that opens nothing (a meteor peak has no window of its own); or null to fall back to the beyond-forecast chip
 *          (the entry is not in the forecast yet, or a served field the choice needs is missing)
 */
function planFor(entry, watch, windows, hotTopics) {
  const tide = TIDE_FAMILY.includes(watch.type);
  const span = spanOf(entry, tide);
  const cards = windowsIn(windows, span);
  if (cards.length === 0) return null;
  if (tide) {
    const card = tideWindow(cards);
    return card ? { state: 'window', card, tideOnLight: true } : null;
  }
  if (watch.type === 'meteor') return { state: 'static' };
  const card = topicWindow(watch.type, cards, hotTopics, span);
  return card ? { state: 'window', card, tideOnLight: false } : null;
}

/**
 * The chips for the reader's watched dates, ready to draw in Your places. A watch whose entry is not in the served feed
 * (not loaded, or gone) draws nothing, and so does one whose dates have passed (the row is kept, not shown). Earliest
 * date first.
 *
 * <p>Every field read is served: the entry's {@code id}, {@code type}, {@code title}, {@code family},
 * {@code startDate}, {@code endDate}, {@code coincidence[].type} and {@code dayRanges}; a window's {@code key},
 * {@code date}, {@code targetType}, {@code dow}, {@code sunrise}, {@code time}, {@code away},
 * {@code tide.lightAlignment}, {@code bestReach.{rating, locationId, locationName}} and {@code bestRating}; a hot topic's
 * {@code type}, {@code date} and {@code eventType}.
 *
 * @param {object} args
 * @param {Array<{key: string}>} args.dates the reader's watched dates
 * @param {?Array<object>} args.entries the served almanac entries ({@code comingUp.events.entries})
 * @param {Array<object>} args.windows the strip's window descriptors
 * @param {?Array<object>} args.hotTopics the served {@code briefing.hotTopics}
 * @param {string} args.todayStr the reader's UK today
 * @returns {Array<{key: string, kind: 'beyond'|'window'|'static', entryId: string, glyph: ?string, title: string,
 *   name: string, detail: string, window: ?{date: string, eventType: string}, bestSpot: ?{id: number, name: string},
 *   sortDate: string}>}
 */
export function buildWatchedChips({ dates, entries, windows, hotTopics, todayStr }) {
  const chips = [];
  for (const { key } of dates ?? []) {
    const entry = matchWatchToEntry(key, entries);
    const watch = parseWatchKey(key);
    if (!entry) {
      // The feed has arrived and does not list this watch: kept as a chip of its own, so it can still be taken away.
      if (Array.isArray(entries) && watch && watch.date >= todayStr) {
        chips.push({
          key, kind: 'unlisted', entryId: null, glyph: null, title: TYPE_NAMES[watch.type],
          name: `${TYPE_NAMES[watch.type]} · ${formatArrivalDate(watch.date)}`, detail: 'no longer listed',
          window: null, bestSpot: null, firstWindow: null, sortDate: watch.date,
        });
      }
      continue;
    }
    const span = spanOf(entry, TIDE_FAMILY.includes(watch.type));
    if (span.last < todayStr) continue;
    const glyph = entryGlyph(entry);
    const dateLabel = formatArrivalDate(span.first);
    const base = {
      key, entryId: entry.id, glyph, title: entry.title, sortDate: span.first, window: null, bestSpot: null,
      firstWindow: null,
    };
    const plan = planFor(entry, watch, windows, hotTopics);
    if (plan?.state === 'window') {
      const { card } = plan;
      const best = card.bestReach;
      const event = card.sunrise ? 'sunrise' : 'sunset';
      chips.push({
        ...base,
        kind: 'window',
        name: `${entry.title} · ${card.dow}`,
        detail: [
          card.time ? `${event} ${card.time}` : event,
          best ? `best ${best.locationName} ${best.rating}★` : null,
          plan.tideOnLight ? 'tide on the light' : null,
        ].filter(Boolean).join(' · '),
        window: { date: card.date, eventType: card.targetType },
        bestSpot: best?.locationId != null ? { id: best.locationId, name: best.locationName } : null,
      });
    } else if (plan?.state === 'static') {
      chips.push({
        ...base, kind: 'static', name: `${entry.title} · ${dateLabel}`, detail: WATCH_NO_WINDOW_LINE,
      });
    } else {
      const live = windowsIn(windows, span);
      const forecast = live.length > 0;
      const countdown = buildDateRail(span.first, span.last, todayStr).countdown;
      chips.push({
        ...base,
        kind: 'beyond',
        // The first window this date reaches, for a watch whose entry has graduated to a lane (it is not listed in
        // Further ahead, so a tap goes to the map instead).
        firstWindow: forecast ? { date: live[0].date, eventType: live[0].targetType } : null,
        name: `${entry.title} · ${dateLabel}`,
        detail: [countdown, forecast ? null : "best spots once it's forecast"].filter(Boolean).join(' · '),
      });
    }
  }
  return chips.sort((a, b) => a.sortDate.localeCompare(b.sortDate));
}
