import { canBeOrigin } from './planOrigin.js';

/**
 * The Plan tab's search — one box over three kinds of thing (plan §4.8).
 *
 * <h2>The resting list is windows only, and that is a decision rather than a gap</h2>
 *
 * <p>The prototype rests on a "Recent locations" group; this app has no recency store and none is
 * planned (§9.11, shipped windows-only). Regions are absent at rest on the prototype's own
 * reasoning — the map is the region picker now — but match the moment they are typed. So the
 * resting list is the six windows, which are the one group that is both small and complete: every
 * row is somewhere the reader can already see, so the list is a shortcut rather than a catalogue.
 *
 * <h2>Matching is a plain substring over a folded string, on purpose</h2>
 *
 * <p>The catalogue is ~200 locations and ~10 regions and the box re-filters on every keystroke, so
 * anything cleverer would be optimising a list that fits on one screen. What it does buy is stated
 * in each builder: a window is searchable by every word a reader might use for it (the weekday, the
 * event, and the relative day words the card itself prints), because "thursday sunset", "tonight"
 * and "tomorrow" are all things the design's own copy says out loud.
 *
 * <p>Folding is {@code toLowerCase} plus whitespace collapse and nothing else — deliberately not
 * accent-stripping or punctuation-stripping. A UK location roster has hyphens and apostrophes in
 * it ("Bamburgh", "St Mary's"), and a reader typing one gets a match either way; stripping them
 * would make "stmarys" match, which nobody types, at the cost of a rule that has to be kept in step
 * with the roster.
 */

/** How many rows of one kind the dropdown will offer. Enough to choose from, not to browse. */
export const MAX_RESULTS_PER_GROUP = 8;

/** Lower-cased, whitespace-collapsed, trimmed. The one normalisation both sides run through. */
export function fold(value) {
  return String(value ?? '').toLowerCase().replace(/\s+/g, ' ').trim();
}

/** Every word a window answers to: its printed label, its weekday, and its event. */
function windowTerms(card) {
  const terms = [card.label, card.dow, card.time];
  terms.push(card.targetType === 'SUNRISE' ? 'sunrise' : 'sunset');
  // The full weekday as well as the strip's three-letter abbreviation: the design's own example
  // query is "thursday sunset", and `card.dow` is "Thu".
  if (card.date) {
    terms.push(new Date(`${card.date}T12:00:00Z`)
      .toLocaleDateString('en-GB', { weekday: 'long', timeZone: 'UTC' }));
    terms.push(card.date);
  }
  // "tonight" is the card's own kicker word and is already inside `label` when it applies; it is
  // NOT added here for every sunset, because a sunset three days out is not tonight and a search
  // that says otherwise is the kind of small lie this arm has removed elsewhere.
  return fold(terms.filter(Boolean).join(' '));
}

/**
 * The three result groups for a query.
 *
 * <p>Every row carries {@code kind}, a stable {@code key}, the {@code label} it renders, and a
 * {@code sub} line. Region rows additionally carry {@code disabled} and a {@code reason} — a
 * region with no base town is <b>found but not choosable</b>, because hiding it would make the
 * search look broken for a region the reader can see on the map.
 *
 * @param {string} query        what the reader typed
 * @param {object} sources
 * @param {Array}  [sources.windows]   heat strip descriptors (key, label, dow, time, date, ...)
 * @param {Array}  [sources.regions]   region records from {@code GET /api/regions}
 * @param {Array}  [sources.locations] heat spots (id, name, regionName)
 * @param {*}      [sources.originId]  the current origin's region id, marked as "planning from"
 * @returns {Array<{id: string, title: string, rows: Array}>} groups, empty ones omitted
 */
export function buildSearchGroups(query, {
  windows = [], regions = [], locations = [], originId = null,
} = {}) {
  const q = fold(query);
  const groups = [];

  // Windows — the resting list, and matched when typed.
  const windowRows = (windows || [])
    .filter((card) => card && (q === '' || windowTerms(card).includes(q)))
    .slice(0, MAX_RESULTS_PER_GROUP)
    .map((card) => ({
      kind: 'window',
      key: `window:${card.key}`,
      windowKey: card.key,
      label: card.label,
      sub: [card.time, card.away ? 'Not forecast' : card.verdictLabel].filter(Boolean).join(' · '),
      // ⚠️ An away window is SHOWN and not choosable, the same treatment a baseless region gets.
      // It has no card — `buildWindowCards` drops travel days, because a "Poor" card under a tile
      // reading "Not forecast" is a contradiction — so choosing one would close the dialog having
      // silently done nothing. The strip already draws it as a non-interactive cell; hiding it here
      // instead would make the search's six windows a different six from the strip's.
      disabled: Boolean(card.away),
      reason: card.away ? 'Not forecast — you are away this day' : null,
    }));
  if (windowRows.length > 0) groups.push({ id: 'windows', title: 'Windows', rows: windowRows });

  // Regions — never at rest. An empty query returns none, which is the whole of that rule.
  const regionRows = q === '' ? [] : (regions || [])
    .filter((region) => region && fold(region.name).includes(q))
    .slice(0, MAX_RESULTS_PER_GROUP)
    .map((region) => {
      const based = canBeOrigin(region);
      const current = originId != null && region.id === originId;
      // A disabled region is switched off across the whole app, so the briefing carries no event
      // summaries for it: made an origin, every window would land on the away empty state. Shown
      // rather than hidden, for the same reason a baseless one is — the reason is the useful part.
      const off = region.enabled === false;
      let reason = null;
      if (off) reason = 'This region is switched off';
      else if (!based) reason = 'This region has no base town, so it cannot be an origin';
      else if (current) reason = 'You are already planning from here';
      return {
        kind: 'region',
        key: `region:${region.id}`,
        region,
        label: region.name,
        sub: current
          ? 'Planning from here'
          : (based && !off ? `Plan from ${region.baseName.trim()}` : (off ? 'Switched off' : 'No base town set')),
        disabled: off || !based || current,
        reason,
      };
    });
  if (regionRows.length > 0) groups.push({ id: 'regions', title: 'Plan from here', rows: regionRows });

  // Locations — never at rest either; the strip and the map are how a reader browses.
  const locationRows = q === '' ? [] : (locations || [])
    .filter((spot) => spot && fold(spot.name).includes(q))
    .slice(0, MAX_RESULTS_PER_GROUP)
    .map((spot) => ({
      kind: 'location',
      key: `location:${spot.id}`,
      spot,
      label: spot.name,
      sub: spot.regionName || '',
    }));
  if (locationRows.length > 0) {
    groups.push({ id: 'locations', title: 'Locations', rows: locationRows });
  }

  return groups;
}

/**
 * The groups flattened to the order the arrow keys move through.
 *
 * <p>One flat list rather than a per-group cursor: {@code ↑↓} crosses group boundaries in every
 * search box a reader has used, and a cursor that stopped at a heading would be a control that
 * appears stuck.
 *
 * @param {Array} groups from {@link buildSearchGroups}
 * @returns {Array} every row, in visual order
 */
export function flattenRows(groups) {
  return (groups || []).flatMap((group) => group.rows || []);
}

/**
 * The next selectable index for an arrow key — skipping rows that cannot be chosen.
 *
 * <p>Wraps at both ends, and returns the current index unchanged when <b>nothing</b> in the list is
 * selectable, so the loop cannot spin on a list of disabled region rows. An empty list answers
 * {@code -1}, matching {@link firstSelectable}'s "no active row" sentinel.
 *
 * @param {Array}  rows    the flattened rows
 * @param {number} current the active index
 * @param {number} step    +1 for down, -1 for up
 * @returns {number} the next index
 */
export function nextSelectable(rows, current, step) {
  const list = rows || [];
  if (list.length === 0) return -1;
  // From -1 ("nothing active"), a step forward has to land on 0 rather than on the modulo of a
  // negative — which is why the walk starts from a normalised base rather than from `current`.
  const from = current < 0 ? (step > 0 ? -1 : 0) : current;
  for (let i = 1; i <= list.length; i += 1) {
    const index = ((from + step * i) % list.length + list.length) % list.length;
    if (!list[index]?.disabled) return index;
  }
  return current;
}

/**
 * The first selectable index, for a fresh query — or {@code -1} when nothing can be chosen.
 *
 * <p>⚠️ {@code -1} rather than 0, and the difference is a claim rather than a convention. Collapsing
 * "none" onto index 0 put the cursor on a <em>disabled</em> row whenever every match was one — a
 * query matching only baseless regions — so the row read {@code aria-selected="true"}, Enter
 * silently refused it and both arrow keys refused to move. Three controls doing nothing with no
 * announcement is indistinguishable from a hung dialog. At {@code -1} the component renders no
 * active row and no {@code aria-activedescendant}, which is the honest picture of a result set with
 * nothing in it to open.
 *
 * @param {Array} rows the flattened rows
 * @returns {number} the index to start on, or -1
 */
export function firstSelectable(rows) {
  return (rows || []).findIndex((row) => !row.disabled);
}
