import { originAction } from './planOrigin.js';
import { lookupForWindow, outsideLabel } from './locationSheet.js';
import { formatDriveDuration } from './briefingDisplay.js';

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
 * <h2>Folding, and the reversal M3 made to it</h2>
 *
 * <p>This module used to fold with {@code toLowerCase} and whitespace collapse and nothing else, on
 * the argument that "stripping punctuation would make 'stmarys' match, which nobody types". The
 * design bundle disagrees by name — it lists {@code st marys}, {@code stmarys} and the accented and
 * ampersanded forms as queries that must find their places — and the argument's own premise was
 * wrong: a reader typing a place from memory is exactly the reader who leaves the apostrophe out.
 * So {@link fold} now also strips accents, turns {@code &} into {@code and}, folds {@code saint} to
 * {@code st}, and treats {@code ' - . ,} as spaces; a second, whitespace-blind pass catches
 * {@code stmarys}. Aliases ({@code bait island} → St Mary's) are still NOT here: they need a store
 * that does not exist (plan-matrix A13 / §8 O-3), and inventing a client-side list would put a
 * roster fact in the frontend.
 *
 * <p><b>Highlighting runs on a different, narrower fold</b> — {@code foldPlain}, which is 1:1 or
 * 1:0 <em>per source character</em> and therefore can carry an index map back to the original
 * string. (It shortens strings — an apostrophe and a run of spaces both collapse — but it never
 * emits a character that came from more than one source character, which is the property the map
 * needs.) The wide fold has no such property: {@code &} → {@code and} and {@code saint} → {@code st}
 * each emit from nothing and from two characters respectively, so a match position in the folded
 * text names no single span of the label. A row matched only by the wide fold is shown with no
 * {@code <mark>} rather than with a guessed one — the row is still the answer, and a mark in the
 * wrong place is worse than none.
 */

/** How many rows of one kind the dropdown will offer. Enough to choose from, not to browse. */
export const MAX_RESULTS_PER_GROUP = 8;

/**
 * Punctuation this treats as a word break rather than as a character.
 *
 * <p>Apostrophes (both kinds — a roster typed on a Mac has {@code ’}), hyphens (all three widths),
 * full stops, commas and slashes. Everything else survives: a digit or a letter is something a
 * reader meant to type.
 */
const BREAKS = /['’.,/\u2010\u2011\u2012\u2013\u2014-]/;

/**
 * The narrow fold, with a map from each output character back to its source index.
 *
 * <p>Every rule here is 1:1 or 1:0 on characters, which is what makes the map possible: lower-case,
 * strip combining marks (per character, so the index stays aligned — normalising the WHOLE string
 * to NFD first would shift every index after the first accent), turn a break character or run of
 * whitespace into one space, and drop leading and trailing spaces.
 *
 * @param {*} value anything printable
 * @returns {{text: string, map: number[]}} the folded text, and per output char its source index
 */
function foldPlain(value) {
  const src = String(value ?? '');
  const out = [];
  const map = [];
  let pendingSpace = false;
  for (let i = 0; i < src.length; i += 1) {
    // Per character, so one accented letter cannot move every index after it.
    const bare = src[i].normalize('NFD').replace(/[\u0300-\u036f]/g, '').toLowerCase();
    if (bare === '' ) continue;
    if (/\s/.test(bare) || BREAKS.test(bare)) {
      // Never leading: `pendingSpace` is only honoured once something real follows it, which is
      // what makes this a trim as well as a collapse.
      if (out.length > 0) pendingSpace = true;
      continue;
    }
    if (pendingSpace) { out.push(' '); map.push(i); pendingSpace = false; }
    for (const ch of bare) { out.push(ch); map.push(i); }
  }
  return { text: out.join(''), map };
}

/**
 * The wide fold — what MATCHING runs on. Narrow fold, plus the two length-changing rewrites.
 *
 * <p>{@code saint} is bounded by {@code \b} so "Saintfield" is untouched; the narrow fold has
 * already reduced every separator to a single space, so the boundary is unambiguous here in a way
 * it would not be on the raw string.
 *
 * @param {*} value anything printable
 * @returns {string} the folded text
 */
export function fold(value) {
  return foldPlain(value).text
    .replace(/&/g, 'and')
    .replace(/\bsaint\b/g, 'st');
}

/** The same string with no spaces at all — the pass that lets `stmarys` find `St Mary's`. */
const compact = (value) => value.replace(/ /g, '');

/**
 * Whether a folded haystack answers a folded needle, by either pass.
 *
 * @param {string} hay   a {@link fold}ed candidate
 * @param {string} q     a {@link fold}ed query; empty matches everything
 * @returns {boolean} true when the query is found
 */
export function matches(hay, q) {
  if (q === '') return true;
  return hay.includes(q) || compact(hay).includes(compact(q));
}

/**
 * Where a query lands inside a label, as a range into the ORIGINAL string — or null.
 *
 * <p>Null for the two cases a mark would be a lie: an empty query, and a row that only matched
 * under the wide fold's rewrites (see the module comment). The end is exclusive and is taken from
 * the LAST matched character's source index plus one rather than from the next character's,
 * because the next character may be a break the fold dropped — {@code mary} against
 * {@code St Mary's} would otherwise swallow the apostrophe.
 *
 * <p>⚠️ The end then walks FORWARD over any combining marks. {@code foldPlain} strips them, so on a
 * name stored decomposed — {@code e} + {@code U+0301} rather than {@code é}, which is what a name
 * typed on a Mac and round-tripped through some pipelines looks like — the accent occupies its own
 * source index and appears in no map entry. Without the walk the mark would end on the base letter
 * and the acute would render outside the {@code <mark>}, orphaned onto the boundary.
 *
 * @param {string} label the string as rendered
 * @param {string} query what the reader typed, unfolded
 * @returns {?[number, number]} start and end indices into {@code label}
 */
export function matchRange(label, query) {
  const needle = foldPlain(query).text;
  if (needle === '') return null;
  const { text, map } = foldPlain(label);
  const at = text.indexOf(needle);
  if (at < 0) return null;
  let end = map[at + needle.length - 1] + 1;
  while (end < label.length && /[\u0300-\u036f]/.test(label[end])) end += 1;
  return [map[at], end];
}

/**
 * The glyph column, by kind. The bundle's own three characters, and they are decorative: every row
 * announces its kind through its group heading, so these are `aria-hidden` at the render.
 */
const GLYPH = { location: '◇', region: '◎', window: '◷' };

/**
 * The best rated window this ONE location has, and which window it is.
 *
 * <p>⚠️ It aggregates nothing across locations, which is what keeps it inside P8's recorded licence
 * rather than reopening Rule 1: it is a max over one place's own six windows, the same shape
 * {@code buildLocationSheet} computes for the sheet this row opens. Ties break earliest, because
 * the walk is in render order and the comparison is strict.
 *
 * <p>⚠️ <b>The sky-subject gate fronts it</b> (ground rule 12). {@code buildHeatSpots} keeps a
 * waterfall or a wood in the catalogue and nulls only its {@code scores}, carrying
 * {@code skySubject: false} to say why — but this walk reads the RAW score rows, so without the
 * check a waterfall would print `4★ · Thu sunset` in the box beside a field that refuses to paint
 * it. A spot that predates the flag (undefined) is treated as a sky subject, which is what the rest
 * of the arm does with an unset boolean.
 *
 * <p>It deliberately does NOT apply the sheet's "a max over one is not a comparison" rule, and the
 * difference is that the sheet labels its row "best" while this one names the window. With a single
 * rated window "4★ · Thu sunset" is simply true; "best" would claim a ranking that never ran.
 *
 * @param {object}  spot       a heat spot
 * @param {Array}   windows    the strip's descriptors, in render order
 * @param {?object} scoreIndex from {@code buildScoreIndex} — id-first, name-fallback
 * @returns {?{rating: number, label: string}} the figure and its caption, or null
 */
function topWindowFor(spot, windows, scoreIndex) {
  if (!scoreIndex || spot?.skySubject === false) return null;
  let best = null;
  for (const card of windows || []) {
    // An away day's slots are collected and never evaluated, so even a stale row for one must not
    // become a forecast for a night nobody forecast — the sheet's own rule, restated here because
    // this walk does not go through it.
    if (!card || card.away) continue;
    const hit = lookupForWindow(scoreIndex, spot?.id ?? null, spot?.name ?? '', card.date, card.targetType);
    const rating = hit?.rating ?? null;
    if (rating == null) continue;
    if (!best || rating > best.rating) {
      best = { rating, label: card.label || `${card.dow} ${card.sunrise ? 'sunrise' : 'sunset'}` };
    }
  }
  return best;
}

/** A rating as the figure column draws it. Integers only — the pipeline produces no half stars. */
const starFigure = (rating) => (rating == null ? null : `${rating}★`);

/**
 * A sub-line's clauses, dropping the unknown ones and normalising a bare string to a toneless part.
 *
 * @param {Array<?(string|{text: string, tone: string})>} parts the clauses, in the order they read
 * @returns {Array<{text: string, tone: ?string}>} what the renderer draws
 */
const clauses = (parts) => parts
  .filter(Boolean)
  .map((part) => (typeof part === 'string' ? { text: part, tone: null } : { tone: null, ...part }));

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
 * <p>Every row carries {@code kind}, a stable {@code key}, the {@code label} it renders, its
 * sub-line as both a joined {@code sub} string and a {@code subParts} list of
 * {@code {text, tone}} clauses, a decorative {@code glyph}, the {@code marks} range the label
 * highlights, an {@code action} chip naming what Enter does, and — where one exists — a
 * {@code figure}. The clause list exists because the sub-line's last clause is the one that
 * changes what the row MEANS ("outside your 3h area") and a single truncating string is exactly
 * where it dies; the renderer wraps the clauses and tones that one. Region
 * rows additionally carry {@code disabled} and a {@code reason}: a region with no base town is
 * <b>found but not choosable</b>, because hiding it would make the search look broken for a region
 * the reader can see on the map.
 *
 * <h2>The figure column, and why two kinds of row answer it differently</h2>
 *
 * <p>A window row's figure is the descriptor's own {@code bestReach} — the head of its reach-gated
 * pool, already derived at M1 and already drawn on the card behind the dialog, so the box and the
 * matrix cannot disagree. A location row's is that place's own top rated window. A <b>region</b> row has
 * none, deliberately: "the best in this region" is a cross-location max the server owns (Rule 1),
 * and the two figures above are per-user reach joins the server cannot answer (Rule 4, A10/A11) —
 * a region's best is neither, so it would be a new claim rather than a relocated one.
 *
 * @param {string} query        what the reader typed
 * @param {object} sources
 * @param {Array}  [sources.windows]   heat strip descriptors (key, label, dow, time, date, ...)
 * @param {Array}  [sources.regions]   region records from {@code GET /api/regions}
 * @param {Array}  [sources.locations] heat spots (id, name, regionName)
 * @param {*}      [sources.originId]  the current origin's region id, marked as "planning from"
 * @param {?Map}   [sources.reachById] the reach map the PAGE plans from, for the drive sub-clause
 * @param {?object} [sources.scoreIndex] from {@code buildScoreIndex}, for the location figures
 * @param {?Array} [sources.scopeRegionNames] region names in scope; absent OR EMPTY marks nothing
 * @param {?object} [sources.origin]   the origin descriptor, for the "outside" clause's wording
 * @returns {Array<{id: string, title: string, rows: Array}>} groups, empty ones omitted
 */
export function buildSearchGroups(query, {
  windows = [], regions = [], locations = [], originId = null,
  reachById = null, scoreIndex = null, scopeRegionNames = null, origin = null,
} = {}) {
  const q = fold(query);
  const groups = [];
  /**
   * Whether any drive time exists at all — see the window rows' `figure` caption for why.
   *
   * <p>Asked of the reach map the PAGE plans from, which is the same map the sub-lines read, so the
   * box cannot caption a figure "in reach" on a row whose own sub-line has no drive to show.
   */
  const reachMeasured = Boolean(reachById)
    && Array.from(reachById.values()).some((entry) => entry?.driveMinutes != null);

  // Windows — the resting list, and matched when typed.
  const windowRows = (windows || [])
    .filter((card) => card && matches(windowTerms(card), q))
    .slice(0, MAX_RESULTS_PER_GROUP)
    .map((card) => ({
      kind: 'window',
      key: `window:${card.key}`,
      windowKey: card.key,
      label: card.label,
      glyph: GLYPH.window,
      marks: matchRange(card.label, query),
      // ⚠️ The descriptor's OWN `bestReach`, which `buildHeatStripCards` already folds on from the
      // matching card — not a second channel keyed back to `windowCards`. The first cut took a
      // `cards` prop and joined on the window key to get the identical object; one field in hand
      // beats a join that could miss. Null on an away row: there is no card for a travel day, so
      // there is no pool and no head.
      // ⚠️ The caption drops where the reach axis cannot have acted — §6 clause 7, and the fourth
      // surface in this arm to have made the same claim. `bestReach` is the head of a pool gated by
      // a tier that an unknown drive time passes (plan §2.5), so for a reader with no home postcode
      // it is simply the best rated place in scope and "in reach" names a filter that never ran.
      // The FIGURE is unchanged; only the word about how it was chosen goes. Here it matters more
      // than elsewhere, because the caption is the only word explaining the number.
      figure: card.bestReach
        ? {
          value: starFigure(card.bestReach.rating),
          caption: reachMeasured ? 'in reach' : null,
        }
        : null,
      action: card.away ? null : 'Open',
      subParts: clauses([card.time, card.away ? 'Not forecast' : card.verdictLabel]),
      sub: [card.time, card.away ? 'Not forecast' : card.verdictLabel].filter(Boolean).join(' · '),
      // ⚠️ An away window is SHOWN and not choosable, the same treatment a baseless region gets.
      // It has no card — `buildWindowCards` drops travel days, because a "Poor" card under a tile
      // reading "Not forecast" is a contradiction — so choosing one would close the dialog having
      // silently done nothing. The strip already draws it as a non-interactive cell; hiding it here
      // instead would make the search's six windows a different six from the strip's.
      disabled: Boolean(card.away),
      reason: card.away ? 'Not forecast — away this day' : null,
    }));
  if (windowRows.length > 0) {
    groups.push({ id: 'windows', title: 'Sunrises & sunsets', rows: windowRows });
  }

  // Regions — never at rest. An empty query returns none, which is the whole of that rule.
  const regionRows = q === '' ? [] : (regions || [])
    .filter((region) => region && matches(fold(region.name), q))
    .slice(0, MAX_RESULTS_PER_GROUP)
    .map((region) => {
      // ⚠️ ONE eligibility TEST, shared with the location sheet's `Plan from <region>` footer
      // (M4.3); the WORDS are this surface's own, because its subject is a region and the sheet's
      // is a place — "you are already planning from here" means two different things on the two,
      // and `originAction`'s own note records what that cost. A disabled region is switched off
      // across the whole app, so the briefing carries no event summaries for it: made an origin,
      // every window would land on the away empty state. Shown rather than hidden, for the same
      // reason a baseless one is — the reason is the useful part.
      const { based, current, off, can } = originAction(region, originId);
      let reason = null;
      if (off) reason = 'This region is switched off';
      else if (!based) reason = 'This region has no base town to plan from';
      else if (current) reason = "You're already planning from here";
      return {
        kind: 'region',
        key: `region:${region.id}`,
        region,
        label: region.name,
        glyph: GLYPH.region,
        marks: matchRange(region.name, query),
        // No figure — see the class comment on why a region's "best" is a claim nobody owns.
        figure: null,
        action: current ? 'Planning now' : (can ? 'Plan from here' : null),
        subParts: clauses([current
          ? 'Planning from here'
          : (based && !off ? `Plan from ${region.baseName.trim()}` : (off ? 'Switched off' : 'No base town set'))]),
        sub: current
          ? 'Planning from here'
          : (based && !off ? `Plan from ${region.baseName.trim()}` : (off ? 'Switched off' : 'No base town set')),
        // The shared verdict itself, so a fourth disqualifier added to `originAction` reaches this
        // row without an edit here — the drift its extraction exists to make impossible.
        disabled: !can,
        reason,
      };
    });
  if (regionRows.length > 0) groups.push({ id: 'regions', title: 'Plan from here', rows: regionRows });

  // Locations — never at rest either; the strip and the map are how a reader browses.
  // ⚠️ An EMPTY scope array is unknown, not "outside everything" — `buildLocationSheet`'s own rule,
  // and the badge here makes the identical claim. At home `scopeRegions` folds to `areaRegions`,
  // which is empty whenever the catalogue is, and an unloaded planning area is no evidence that a
  // place is out of the plan.
  const scopeKnown = Array.isArray(scopeRegionNames) && scopeRegionNames.length > 0;
  const locationRows = q === '' ? [] : (locations || [])
    .filter((spot) => spot && matches(fold(spot.name), q))
    .slice(0, MAX_RESULTS_PER_GROUP)
    .map((spot) => {
      const top = topWindowFor(spot, windows, scoreIndex);
      const drive = (spot?.id == null ? null : reachById?.get(spot.id)?.driveMinutes) ?? null;
      const outside = scopeKnown && Boolean(spot.regionName)
        && !scopeRegionNames.includes(spot.regionName);
      return {
        kind: 'location',
        key: `location:${spot.id}`,
        spot,
        label: spot.name,
        glyph: GLYPH.location,
        marks: matchRange(spot.name, query),
        figure: top ? { value: starFigure(top.rating), caption: top.label } : null,
        // ⚠️ NOT the bundle's `4 DAYS`. The sheet this opens derives its own span — six rendered
        // events fall across three days whenever today still has both its windows ahead of it — and
        // prints it ("The next 3 days here"), so a fixed 4 in the chip above it would be a number
        // we never measured sitting beside the same number measured. The chip names the surface
        // instead, which is true at every span.
        action: 'Next few days',
        // Region, then the drive, then the out-of-plan note — the bundle's own order, and each
        // clause is omitted rather than filled when its source is unknown (Rule 6). The third one
        // carries a tone: it is the clause that changes what the row means, and the bundle colours
        // it for the same reason.
        subParts: clauses([
          spot.regionName || null,
          drive == null ? null : formatDriveDuration(drive),
          outside ? { text: outsideLabel(origin), tone: 'outside' } : null,
        ]),
        sub: [
          spot.regionName || null,
          drive == null ? null : formatDriveDuration(drive),
          outside ? outsideLabel(origin) : null,
        ].filter(Boolean).join(' · '),
      };
    });
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
