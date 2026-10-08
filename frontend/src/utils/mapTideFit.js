import { lookupForWindow } from './locationSheet.js';
import { EVENT_KIND } from './mapEvents.js';
import { windowKey } from './windowKeys.js';
import { STATE_WORD } from './windowFirstRows.js';

/**
 * The Map tab's tide-fit derivations — pure logic only, the client's licensed slice of the
 * tide-window increment (`docs/engineering/tide-window-plan.md` T3, §1 #12).
 *
 * <p>CLAUDE.md's Backend-heavy bullet licenses two classes of client derivation: per-user joins,
 * and filter/map/select over served facts. Everything below is the second class — a viewport
 * filter for the strip's visibility and counts (the same shape `MapLabels`' own in-view set
 * already uses), a tally of served {@code tideType} values over the dimmed in-view locations, a
 * forward scan over served per-window {@code tideAligned} booleans (and, for the strip, the served
 * {@code tideState} beside them), and {@code tier = aligned ?
 * 'match' : 'miss'} — a served boolean read. ⚠️ <b>Nothing here reads a height, a minute offset or
 * a threshold to decide anything</b> — {@code TideCurveCalculator} and {@code TideWording} own
 * that maths, server-side; a change here that starts comparing a number against a threshold has
 * drifted onto the server's side of the line.
 */

/**
 * The coastal, in-view pool a padded viewport holds — the SAME {@code bounds.pad(0.12).contains(...)}
 * filter {@link stripModel} has always run internally, lifted out to its own export
 * (map-mobile-sheet-plan.md §3 M5 task 1) so there is exactly one definition: {@code stripModel}
 * itself now calls this rather than repeating the filter, and the Tide-visibility rule
 * (`mapPeek.js#tideVisible`) calls it a SECOND time on the map's UNDECORATED spot list (before
 * {@code tideTier} exists) to answer "is there a named coastal spot in view at all", independent of
 * the strip's own decorated pool. ⚠️ Returns the filtered ARRAY, not a boolean — an empty array is
 * truthy in JavaScript, so a caller that passes the array straight into a boolean-shaped gate (rather
 * than its `.length > 0`) would keep tide cues on after a pan inland (a Codex finding on the plan's
 * M0). {@code coastalInView(spots, bounds).length > 0} is the caller's job, not this function's.
 *
 * @param {Array<{lat: number, lng: number, coastal: boolean}>} spots
 * @param {?{pad: function(number): object}} bounds a Leaflet {@code LatLngBounds} (or any object
 *   exposing the same {@code pad}/{@code contains} pair) — null/undefined (no bounds report yet)
 *   answers the empty array, never throws
 * @returns {Array<object>} the coastal spots inside the padded viewport
 */
export function coastalInView(spots, bounds) {
  if (!bounds || typeof bounds.pad !== 'function') return [];
  const padded = bounds.pad(0.12);
  return (Array.isArray(spots) ? spots : []).filter((s) => s.coastal && padded.contains([s.lat, s.lng]));
}

/**
 * Every served window's {@code BriefingWindowTide}, keyed {@code "date:TARGETTYPE"} — over ALL of
 * the briefing's event summaries, not just the six the Plan tab renders.
 *
 * <p>Why this exists: {@code PlanWindowProjector} attaches a tide rollup to every event summary of
 * the briefing's four owned days (eight events), but the Map tab's window list is built from the
 * <em>rendered</em> six ({@code heat.windows}). The remaining events were turned into D-13 filler
 * rows with {@code tide: null}, so the strip vanished for them — the Sunday sunrise, seen from
 * Thursday morning, is the seventh event. Tides are deterministic and the rollup was already on the
 * wire; this just stops the fold discarding it. A lookup over served data, never a derivation —
 * nothing here reads a height, an offset or a level.
 *
 * @param {?Array<{date: string, eventSummaries: ?Array<{targetType: string, window: ?object}>}>} days
 *   the briefing's days
 * @param {(iso: string) => ?string} [formatTime] formats the window's event instant to the clock
 *   string a served row shows (`briefingDisplay.formatTime`); the client does no time maths itself
 * @returns {Map<string, {tide: object, eventTime: ?string, time: ?string}>} per {@link windowKey};
 *   a window the server could not draw a tide for (no coastal representative, no high and low
 *   water that day) has no entry
 */
export function buildWindowTideIndex(days, formatTime = () => '') {
  const index = new Map();
  for (const day of Array.isArray(days) ? days : []) {
    if (!day?.date) continue;
    for (const summary of day.eventSummaries ?? []) {
      const tide = summary?.window?.tide;
      if (!summary?.targetType || !tide) continue;
      const eventTime = summary.window.eventTime ?? null;
      index.set(windowKey(day.date, summary.targetType), {
        tide,
        // The window's own served instant (UTC-naive) — what the elapsed test reads — and its
        // clock time formatted by the caller with the formatter served rows use. Null stays null.
        eventTime,
        time: eventTime ? (formatTime(eventTime) || null) : null,
      });
    }
  }
  return index;
}

/**
 * The tier a served tide-alignment fact reads as, for the chip/ring/tiebreak/callout-row/strip.
 *
 * <p>{@code fact} is one entry from {@link buildTideAlignmentIndex} in {@code locationSheet.js}
 * (or {@code null}/{@code undefined} when the lookup found nothing for this location at this
 * window) — never a raw {@code BriefingSlot}, so this function never reads {@code onTheLight} and
 * cannot answer the wrong axis (CLAUDE.md's two-tide-axes rule). A missing entry means "not a
 * coastal slot with a served tide state" (inland, or no stored extremes near this event) — a
 * different claim from a served miss, so it reads {@code null} rather than guessing {@code 'miss'}.
 *
 * @param {?{aligned: boolean}} fact
 * @returns {'match'|'miss'|null}
 */
export function tierOf(fact) {
  if (!fact) return null;
  return fact.aligned ? 'match' : 'miss';
}

/**
 * The one canonical heading per tier (tide-window-plan.md §4 #8: "the spec's headings … are taken
 * verbatim"), shared by every surface that prefixes a heading to a served phrase — the chip's
 * tooltip (T4) and, by the same rule, the callout/sheet block (T5). A FIXED string per tier, never
 * text built from the location's own wanted set: the served {@code fitPhrase} already opens a miss
 * with its own "wants …" clause (`TideWording`, T1 item 4), so a heading that restated the want
 * from {@code tideTypes} would print it twice on one line — the client is licensed to concatenate a
 * heading to a phrase (§5 #5), never to format the want set itself.
 *
 * <p>⚠️ <b>Deliberate deviation, recorded rather than silent (adversarial review, T4).</b> T4's own
 * task text quotes the chip tooltip's miss heading as "Wants high water —", matching the design
 * bundle's dynamic `bindTip` line (`map-tide-v5.js:434-435`, `'Wants '+BANDW[tf.want]+' — '+…`) —
 * a heading the design COMPUTES from the location's own want, which this file may not do (see
 * this function's own doc above). Rather than invent a client-side want-formatter to match that
 * one example literally, this reuses the SAME fixed miss heading the callout/sheet block (T5) and
 * the design's own callout table (`docs/design/tide-window/README.md` §4) already use — "Wrong
 * water, not wrong light" — so the tooltip and the block agree on one vocabulary rather than the
 * tooltip alone matching a dynamic example the served `fitPhrase` already restates internally
 * (T1 item 4's miss phrase opens "wants low water · …"; a want-specific heading on top of that
 * would print the want twice on one line, which the doc above already forbids for a different
 * reason). If a future phase decides the tooltip should instead name the specific want, that is a
 * product call for the copy owner, not a client-side formatting fix.
 *
 * <p>⚠️ The miss heading is honest about what was assessed (window-tide-facts-plan §4.2): "Wrong
 * water, not wrong light" claims the LIGHT was fine, which is only known when the window was scored
 * for this location. On a window nothing scored (T+3/T+4, a travel day) a miss reads "Tide misses
 * the light here". A match is unchanged. {@code assessed} defaults to true so a caller that does not
 * know keeps the pre-existing wording.
 *
 * @param {?('match'|'miss')} tier
 * @param {boolean} [assessed=true] whether a sky or combined rating exists for this window
 * @returns {?string} null when there is no tier to head (no served tide fact at all)
 */
export function tideTierHeading(tier, assessed = true) {
  if (tier === 'match') return 'Tide lands on the light';
  if (tier === 'miss') return assessed ? 'Wrong water, not wrong light' : 'Tide misses the light here';
  return null;
}

/**
 * The accessible-name clause for a served preference-axis tier — shared by the chip's aria-label
 * (`MapLabels.jsx`, T4 item 2) and the region panel row's `sr-only` span (`MapRegionPanel.jsx`, T4
 * item 6), so the one glyph means the same thing in words wherever it is read aloud. Keyed on tier
 * and (for a miss) the served shortfall direction, and now (for a match) the served state — never a
 * phrase built from the location's own wanted set, for the same reason {@link tideTierHeading}
 * gives.
 *
 * <p>{@code state}, added alongside the match glyph's own letter (tide-window-plan.md §4 #21),
 * reuses {@code STATE_WORD} — the SAME lexical table {@code windowFirstRows.js}'s strip header and
 * this file's own {@link wantPhrase} already read — rather than a third copy of "high water"/"mid
 * tide"/"low water". A match with no state (should not happen for a served match, but this stays
 * defensive) reads the old, state-free clause.
 *
 * @param {?('match'|'miss')} tier
 * @param {?('HIGHER'|'LOWER')} [shortfall] only read when `tier === 'miss'`
 * @param {?('HIGH'|'MID'|'LOW')} [state] only read when `tier === 'match'`
 * @returns {?string} null when there is no served tide fact at all (inland, or no stored extremes)
 */
export function tideAccessibleClause(tier, shortfall = null, state = null) {
  if (tier === 'match') {
    const word = state ? STATE_WORD[state] : null;
    return word ? `${word}, right here` : 'tide right here';
  }
  if (tier === 'miss') {
    if (shortfall === 'HIGHER') return 'wants the water higher';
    if (shortfall === 'LOWER') return 'wants the water lower';
    return 'wrong water';
  }
  return null;
}

/**
 * The order the backend's {@code TideWording#joinOr} reads a location's wanted set in — the
 * {@code TideType} enum's OWN declaration order ({@code entity/TideType.java}: HIGH, MID, LOW) —
 * reproduced here so a two-value want joins identically on the client and on the server: the gate
 * sentence ("needs low water, mid tide instead") and the fit phrase's own "wants" clause both come
 * from the server already in this order, and {@link wantPhrase} states the SAME set for the
 * block's jump/denial line, which sits on the same card. ⚠️ Unrelated to {@link WANT_TIE_ORDER}
 * below, which breaks a tie for a DIFFERENT question (which want is the strip's dominant one) —
 * this one never picks a winner, it orders every member of a set that is printed in full.
 */
const WANT_PHRASE_ORDER = ['HIGH', 'MID', 'LOW'];

/**
 * A location's wanted tide-types joined with "or" — {@code [a] → "a"}, {@code [a, b] → "a or b"},
 * {@code [a, b, c] → "a, b or c"} — mirroring the backend's {@code TideWording#joinOr} exactly, so
 * the same two-value want reads identically in the block's jump/denial line as it does in the
 * gate sentence and the fit phrase's own "wants" clause sitting beside it on the same card
 * (tide-window-plan.md T5 task 1: "{@code <want>} is the location's wanted set joined with 'or'").
 *
 * <p>Reuses {@code windowFirstRows.js}'s {@code STATE_WORD} vocabulary rather than a second copy
 * of "high water"/"mid tide"/"low water" — the same table T6's strip header reads for the served
 * {@code state}/{@code direction} phrase, so there is exactly one place that spells a tide state
 * out in words on this tab.
 *
 * <p>This is a filter/map/select over an already-served, static field
 * ({@code location.tideType}) — CLAUDE.md's Backend-heavy licence's second class — never a
 * decision about WHETHER the tide fits, which stays server-owned (§5 #5's "every string is
 * server-formatted" governs the FIT PHRASE and the gate sentence; joining a configuration set into
 * words is the same lexical mapping {@code STATE_WORD} already performs client-side elsewhere).
 *
 * @param {?Array<string>} tideTypes e.g. {@code location.tideType}
 * @returns {?string} null when given no wants at all (should not happen for a coastal location —
 *          {@code isCoastal()} is exactly a non-empty set — but this stays defensive rather than
 *          throwing on a malformed fixture)
 */
export function wantPhrase(tideTypes) {
  const words = WANT_PHRASE_ORDER
    .filter((type) => Array.isArray(tideTypes) && tideTypes.includes(type))
    .map((type) => STATE_WORD[type]);
  if (words.length === 0) return null;
  if (words.length === 1) return words[0];
  return `${words.slice(0, -1).join(', ')} or ${words[words.length - 1]}`;
}

/**
 * The first later SOLAR row in the EV list where the given location is served {@code tideAligned},
 * scanning forward from {@code fromIndex + 1} — a lookup over the served per-window facts index,
 * never a formula over a single anchor's curve (tide-window-plan.md §4 #7: the spec's own
 * {@code nextFitEv} scans one station's cosine, which for a coast 40–60 minutes wide can name a
 * window no actual spot fits).
 *
 * @param {Array<{kind: string, date: string, eventType: string}>} evRows the map's chronological
 *   EV list (`utils/mapEvents.buildMapEvents`)
 * @param {?{byId: Map, byName: Map}} idx `locationSheet.buildTideAlignmentIndex`'s result — named
 *   {@code idx} rather than {@code index}, matching {@link lookupForWindow}'s own parameter name,
 *   so it can never be misread as an array position beside this function's own {@code fromIndex}
 * @param {{id: ?(number|string), name: ?string}} locationKey the location to test, id-first exactly
 *   like {@link lookupForWindow}
 * @param {number} fromIndex the scan starts at {@code fromIndex + 1} — the row at this position is
 *   never itself a candidate, current or past
 * @param {?('HIGH'|'MID'|'LOW')} [want] when given, the alignment must be to THIS water: the fact's
 *   served {@code state} must equal it. ⚠️ Without it, {@code aligned} alone answers "does the
 *   tide fit ANY of this spot's wants" — right for the callout's own jump, wrong for the strip's,
 *   whose sentence names one water. A spot wanting {@code {HIGH, LOW}} is {@code aligned} in a LOW
 *   window too, so "Next high water on the light" scanned on the bare flag jumped to low water
 *   (a Codex P1 on #878). Null or omitted keeps the any-want reading.
 * @param {boolean} [requireTide] when true, rows carrying no served window tide are skipped. The
 *   strip passes it: a jump to a row the strip would refuse to show unmounts the strip and drops
 *   focus to {@code <body>}. Off by default because the callout and the location sheet are about ONE
 *   location's fit and legitimately jump to a window with no rollup (the sheet's adapter rows carry
 *   no {@code tide} field at all)
 * @returns {object|-1} the row object of the first match, or {@code -1} when none exists
 */
export function nextAlignedRow(
  evRows, idx, locationKey, fromIndex, want = null, requireTide = false,
) {
  if (!Array.isArray(evRows)) return -1;
  for (let i = fromIndex + 1; i < evRows.length; i += 1) {
    const row = evRows[i];
    if (row?.kind !== EVENT_KIND.SOLAR) continue;
    if (requireTide && row.tide == null) continue;
    const fact = lookupForWindow(
      idx, locationKey?.id ?? null, locationKey?.name ?? null, row.date, row.eventType,
    );
    if (!fact?.aligned) continue;
    if (want != null && fact.state !== want) continue;
    return row;
  }
  return -1;
}

/**
 * Tie-break order for the strip's dominant-want tally (tide-window-plan.md §5 #8): HIGH beats LOW
 * beats MID when their dimmed-spot counts are equal.
 */
const WANT_TIE_ORDER = ['HIGH', 'LOW', 'MID'];

/**
 * The mode {@code TideType} across a set of dimmed spots' own wanted sets, ties broken
 * HIGH > LOW > MID (tide-window-plan.md §5 #8) — a spot wanting {@code {HIGH, LOW}} counts once in
 * each bucket, so a two-value want on its own cannot make a tie.
 *
 * @param {Array<{tideTypes: ?Array<string>}>} dimmedSpots
 * @returns {'HIGH'|'MID'|'LOW'|null} null when nothing is dimmed, or no dimmed spot carries a want
 */
function dominantWantOf(dimmedSpots) {
  const counts = new Map();
  for (const spot of dimmedSpots) {
    for (const want of spot.tideTypes ?? []) {
      counts.set(want, (counts.get(want) ?? 0) + 1);
    }
  }
  let best = null;
  let bestCount = 0;
  for (const want of WANT_TIE_ORDER) {
    const count = counts.get(want) ?? 0;
    if (count > bestCount) {
      bestCount = count;
      best = want;
    }
  }
  return best;
}

/**
 * The tide strip's own per-render model — visibility, the in-view coastal pool split by tier, the
 * dominant unmet want, and the next window that fits it. Every field the strip (T6) and its footer
 * need, computed once so the component itself stays a pure render of this shape.
 *
 * <p>{@code spots} is the map's full label-spot pool (`MapView`'s {@code labelSpots}, inland spots
 * included) — this function does the coastal-and-in-view narrowing itself, via
 * {@code bounds.pad(0.12)} (the design's own figure — no {@code bounds.pad()} existed anywhere in
 * this codebase before this increment, tide-window-plan.md §1 #8). {@code bounds} is a Leaflet
 * {@code LatLngBounds} in production, or any object exposing the same {@code pad}/{@code contains}
 * pair — the tests use a plain stand-in so this module stays dependency-free.
 *
 * <p>The counts and pools below are over the <b>chip pool</b> — named coastal spots in the padded
 * viewport (tide-window-plan.md §5 #8) — never the unfiltered roster, so "here" stays true and a
 * count the reader cannot see or nearly see never appears in the footer.
 *
 * <p>⚠️ <b>Three params beyond the plan's own illustrative {@code stripModel({row, spots, bounds})}
 * call</b> — {@code evRows}, {@code evIndex} and {@code idx} — because {@code nextFitRow} cannot be
 * answered from {@code row}/{@code spots}/{@code bounds} alone: it needs the future EV rows to scan
 * and the served facts index to test them against (tide-window-plan.md §3 T3 task 4's own next
 * sentence names exactly these two — {@code evIndex} and {@code index} — as what `MapView` will
 * memoise the "per-window part" on, which only makes sense if a function downstream of that memo
 * receives them). This is the one place in the file's own history a reviewer asked "is this a
 * defensible call or a deviation" — it is defensible, and is recorded here as one rather than
 * silently matching the plan text.
 *
 * <p>⚠️ <b>This is currently ONE function, not the two the plan's memo split implies.</b> "The
 * per-window part on {@code [evIndex, idx]} and the viewport part on {@code mapBounds}" describes
 * two independent `useMemo` calls `MapView` (T6) will want — but `nextFitRow` genuinely depends on
 * BOTH which spots are dimmed (viewport) and which of them fit again later (window), so there is no
 * value this function returns that is purely one or the other. Nothing here is wired into `MapView`
 * yet (T3 adds no call site), so this tension is inert today. If T6 finds the whole-function
 * recompute on every pan measurably expensive, the fix is splitting the viewport-only narrowing
 * ({@code namedCoastal}/{@code dimmed}/{@code matched}/{@code dominantWant} — computable from
 * {@code spots}/{@code bounds} alone) out of the window-dependent {@code nextFitRow} scan into two
 * exported functions `MapView` composes itself, each behind its own memo — not a change to make
 * speculatively against a component that does not exist yet.
 *
 * @param {object} args
 * @param {?{kind: string, tide: ?object, date: string, eventType: string}} args.row the EV row the
 *   window control currently shows
 * @param {Array<{lat: number, lng: number, coastal: boolean, tideTier: ?('match'|'miss'),
 *   tideTypes: ?Array<string>, name: string}>} [args.spots] the map's full label-spot pool
 * @param {?{pad: function(number): object}} [args.bounds] the current map viewport (its own
 *   {@code pad} result must expose {@code contains([lat, lng])})
 * @param {Array<object>} [args.evRows] the full EV list, for the next-fit scan
 * @param {number} [args.evIndex] {@code row}'s own position in {@code evRows} — the
 *   {@link nextAlignedRow} scan's {@code fromIndex}
 * @param {?{byId: Map, byName: Map}} [args.idx] `locationSheet.buildTideAlignmentIndex`'s
 *   result, for the scan
 * @returns {{visible: boolean, fitKnown: boolean, unserved: boolean, representative: ?string,
 *   namedCoastal: Array, dimmed: Array, matched: Array, dominantWant: ?string,
 *   dominantWantCount: number, nextFitRow: (object|-1), nextFitAny: (object|-1)}}
 *   {@code fitKnown}: at least one in-view coastal spot carries a served tier ('match'/'miss') for
 *   this window. {@code unserved}: the row is a D-13 filler (the pane's rendered list carries no
 *   window for it), which decides only the footer's wording when {@code fitKnown} is false.
 *   {@code nextFitRow}: the earliest later row, among rows that carry a tide, where a dimmed spot
 *   wanting the dominant water fits — the only kind of row the strip can jump to.
 *   {@code nextFitAny}: the same scan over every solar row, so it is the EARLIEST fit overall. It
 *   equals {@code nextFitRow} when that earliest fit carries a tide, and is earlier than it (or set
 *   when {@code nextFitRow} is -1) when the earliest fit is a window the strip cannot show
 */
export function stripModel({
  row, spots = [], bounds = null, evRows = [], evIndex = -1, idx = null,
}) {
  const inView = coastalInView(spots, bounds);
  const visible = row?.kind === EVENT_KIND.SOLAR && row?.tide != null && inView.length > 0;
  if (!visible) {
    return {
      visible: false,
      fitKnown: false,
      unserved: false,
      representative: null,
      namedCoastal: [],
      dimmed: [],
      matched: [],
      dominantWant: null,
      dominantWantCount: 0,
      nextFitRow: -1,
      nextFitAny: -1,
    };
  }

  const namedCoastal = inView;
  const dimmed = namedCoastal.filter((s) => s.tideTier === 'miss');
  const matched = namedCoastal.filter((s) => s.tideTier === 'match');
  // `fitKnown` has ONE meaning: at least one in-view coastal spot carries a served tier for this
  // window. Whether the ROW was served is a different question (the briefing's slots, and so every
  // spot's `tideTier`, cover its unrendered windows too), carried separately as `unserved` so the
  // footer can say "No per-spot tide fit for this window" (a standing gap — those spots have no
  // stored extremes — not a pending one) only for an unserved window with no tier. A served window
  // with no tier keeps the footer's older wording.
  const fitKnown = dimmed.length + matched.length > 0;
  const dominantWant = dominantWantOf(dimmed);
  // The scan must fit the DOMINANT want, not any of the spot's wants: the footer's sentence
  // names one water, and a {HIGH, LOW} spot aligned via LOW is not "next high water". Computed
  // once and reused for BOTH the footer's own count (T6, tide-window-plan.md §3 T6 #4 — "9 of
  // them want high water" is this same population's size) and the next-fit scan below, so the
  // two can never disagree about who is "them".
  const wanting = dominantWant
    ? dimmed.filter((s) => (s.tideTypes ?? []).includes(dominantWant))
    : [];

  // Earliest later row where any wanting spot fits the dominant want. `requireTide` restricts the
  // scan to rows the strip itself could show (a jump to any other unmounts the strip).
  const earliestFit = (requireTide) => {
    let found = -1;
    let foundIdx = Infinity;
    if (wanting.length === 0 || !Array.isArray(evRows) || evIndex < 0) return found;
    for (const spot of wanting) {
      const candidate = nextAlignedRow(
        evRows, idx, { id: spot.id ?? null, name: spot.name }, evIndex, dominantWant, requireTide,
      );
      if (candidate === -1) continue;
      const candidateIdx = evRows.indexOf(candidate);
      if (candidateIdx !== -1 && candidateIdx < foundIdx) {
        foundIdx = candidateIdx;
        found = candidate;
      }
    }
    return found;
  };
  const nextFitRow = earliestFit(true);
  // The same scan without `requireTide`, ALWAYS run independently: the EARLIEST fitting window,
  // whether or not the strip can show it. The callout and the four-day sheet name that one, so the
  // footer must too — it jumps only when the earliest fit is also tide-bearing (`nextFitAny ===
  // nextFitRow`), names it as plain text when it is not, and says "beyond" only when it is -1.
  const nextFitAny = earliestFit(false);

  return {
    visible: true,
    fitKnown,
    unserved: row.served === false,
    representative: row.tide?.locationName ?? null,
    namedCoastal,
    dimmed,
    matched,
    dominantWant,
    dominantWantCount: wanting.length,
    nextFitRow,
    nextFitAny,
  };
}

/**
 * The served clock time for the SUNRISE or SUNSET row sharing {@code date} — a lookup over the
 * already-built EV list, never a formula: {@code BriefingWindowTide} states WHERE the sun rises
 * and sets on the day's tide axis ({@code sunrisePosition}/{@code sunsetPosition}, T2) but not the
 * clock time itself, and the strip's chart (T6) needs both to label the two verticals it draws.
 * Reading the sibling solar row's own already-formatted {@code time} keeps the client from
 * formatting a clock time itself (CLAUDE.md: backend formats all clock/offset prose) — the same
 * shape the design prototype's own {@code tideChart} uses, scanning the day's own EV rows for the
 * "am" and "pm" entries rather than inventing a new field.
 *
 * @param {Array<{kind: string, date: string, eventType: string, time: string}>} evRows
 * @param {?string} date the representative's own local day — {@code row.date}, since the sunrise
 *   and sunset drawn on the strip belong to the WINDOW on screen, not to the tide's own location
 * @param {'SUNRISE'|'SUNSET'} eventType
 * @returns {?string} the served clock time (e.g. {@code "05:44"}), or null when no served row
 *   carries one for this date/type (a D-13 filler row with nothing lent, whose {@code time} is
 *   {@code ''}; a filler that borrowed the briefing's tide also borrowed its clock time)
 */
export function siblingEventTime(evRows, date, eventType) {
  if (!Array.isArray(evRows) || date == null) return null;
  const sibling = evRows.find(
    (r) => r?.kind === EVENT_KIND.SOLAR && r.date === date && r.eventType === eventType,
  );
  return sibling?.time || null;
}
