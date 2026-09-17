import { lookupForWindow } from './locationSheet.js';
import { EVENT_KIND } from './mapEvents.js';

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
 * @returns {object|-1} the row object of the first match, or {@code -1} when none exists
 */
export function nextAlignedRow(evRows, idx, locationKey, fromIndex, want = null) {
  if (!Array.isArray(evRows)) return -1;
  for (let i = fromIndex + 1; i < evRows.length; i += 1) {
    const row = evRows[i];
    if (row?.kind !== EVENT_KIND.SOLAR) continue;
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
 * @returns {{visible: boolean, representative: ?string, namedCoastal: Array, dimmed: Array,
 *   matched: Array, dominantWant: ?string, nextFitRow: (object|-1)}}
 */
export function stripModel({
  row, spots = [], bounds = null, evRows = [], evIndex = -1, idx = null,
}) {
  const coastalInView = bounds && typeof bounds.pad === 'function'
    ? spots.filter((s) => s.coastal && bounds.pad(0.12).contains([s.lat, s.lng]))
    : [];
  const visible = row?.kind === EVENT_KIND.SOLAR && row?.tide != null && coastalInView.length > 0;
  if (!visible) {
    return {
      visible: false,
      representative: null,
      namedCoastal: [],
      dimmed: [],
      matched: [],
      dominantWant: null,
      nextFitRow: -1,
    };
  }

  const namedCoastal = coastalInView;
  const dimmed = namedCoastal.filter((s) => s.tideTier === 'miss');
  const matched = namedCoastal.filter((s) => s.tideTier === 'match');
  const dominantWant = dominantWantOf(dimmed);

  let nextFitRow = -1;
  let nextFitRowIdx = Infinity;
  if (dominantWant && Array.isArray(evRows) && evIndex >= 0) {
    const wanting = dimmed.filter((s) => (s.tideTypes ?? []).includes(dominantWant));
    for (const spot of wanting) {
      // The scan must fit the DOMINANT want, not any of the spot's wants: the footer's sentence
      // names one water, and a {HIGH, LOW} spot aligned via LOW is not "next high water".
      const candidate = nextAlignedRow(
        evRows, idx, { id: spot.id ?? null, name: spot.name }, evIndex, dominantWant,
      );
      if (candidate === -1) continue;
      const candidateIdx = evRows.indexOf(candidate);
      if (candidateIdx !== -1 && candidateIdx < nextFitRowIdx) {
        nextFitRowIdx = candidateIdx;
        nextFitRow = candidate;
      }
    }
  }

  return {
    visible: true,
    representative: row.tide?.locationName ?? null,
    namedCoastal,
    dimmed,
    matched,
    dominantWant,
    nextFitRow,
  };
}
