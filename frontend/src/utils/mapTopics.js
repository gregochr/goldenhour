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
import { spotsNamed } from './mapDoor.js';
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
 * @returns {Array<{type: string, name: string, shortName: string, glyph: ?string, colour: string,
 *          where: ?string, label: string, wholeSky: boolean, bigSeas: boolean, topics: object[],
 *          places: ?object[]}>} one entry per chip; empty when the window has none
 */
export function topicsOnRow({
  row, topicIndex, hotTopics, scopeNames, bigSeasDay = null, roster = null, locationsById = null,
}) {
  const chips = [];
  if (bigSeasDay && row) {
    const places = bigSeasPlaces(bigSeasDay, locationsById);
    const where = places.length > 0 ? `${places.length} ${places.length === 1 ? 'structure' : 'structures'}` : null;
    chips.push({
      type: BIG_SEAS_TYPE,
      ...BIG_SEAS_STYLE,
      where,
      label: where ? `${BIG_SEAS_STYLE.name}, ${where}` : BIG_SEAS_STYLE.name,
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
    chips.push({
      type,
      name: style.name,
      shortName: style.shortName,
      glyph: style.glyph,
      colour: style.colour,
      where,
      label: detail ? `${style.name}, ${detail}` : style.name,
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
