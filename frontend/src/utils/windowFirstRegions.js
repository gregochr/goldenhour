import { DISPLAY_ORDER, formatDriveDuration } from './briefingDisplay.js';
import { resolveRegionDisplay } from './tierUtils.js';
import { VERDICT_LABEL, eligibleRegions } from './windowFirstCards.js';
import { gateSpotsByRating } from './ratingLens.js';
import { gateSpotsByReach } from './reachLens.js';

/**
 * The region row order the heatmap is drawn in — best verdict first, then A–Z.
 *
 * <p><b>A copy of {@code DailyBriefing}'s module-private {@code getSortedRegions}, not an
 * extraction.</b> The rule plan §5a set for P6 applies unchanged: an extraction edits the v1 arm,
 * and §4's whole method rests on the v1 arm staying as it is while both layouts are judged against
 * the same night's data. This one is a pure fold over the payload with no state and no rendering, so
 * a copy cannot drift in behaviour without one of the two suites noticing — and the shared parts
 * that <em>do</em> carry the judgement ({@code DISPLAY_ORDER}, {@code resolveRegionDisplay}) are
 * imported rather than copied, so the two orderings cannot disagree about what "best" means.
 * Reconverge after the flag default flips.
 *
 * @param {Array} upcomingEvents [{date, targetType}]
 * @param {Array} briefingDays   briefing.days
 * @returns {string[]} region names, best verdict first
 */
export function sortRegionsByBestVerdict(upcomingEvents, briefingDays) {
  const regionBest = new Map();
  const regionSeen = [];

  for (const { date, targetType } of upcomingEvents || []) {
    const day = (briefingDays || []).find((d) => d.date === date);
    if (!day) continue;
    const es = (day.eventSummaries || []).find((e) => e.targetType === targetType);
    if (!es) continue;
    for (const region of es.regions || []) {
      const name = region.regionName;
      const v = DISPLAY_ORDER[resolveRegionDisplay(region)] ?? 4;
      if (!regionBest.has(name)) {
        regionBest.set(name, v);
        regionSeen.push(name);
      } else if (v < regionBest.get(name)) {
        regionBest.set(name, v);
      }
    }
  }

  return regionSeen.sort((a, b) => {
    const diff = (regionBest.get(a) ?? 4) - (regionBest.get(b) ?? 4);
    return diff !== 0 ? diff : a.localeCompare(b);
  });
}

/**
 * The open row's region data — the rail's cells, the band's figures, and the six-dot series.
 *
 * <p>Sharing a module with {@link sortRegionsByBestVerdict} above, which orders the regional
 * panel's heatmap rows: both are "how this arm turns served regions into a list", and the panel's
 * ordering is the one place a second answer to that would be visible on the same screen.
 *
 * <h2>Every star on these surfaces is SERVED, never a client-side maximum</h2>
 *
 * <p>{@code BriefingRegion.meanRating} and {@code BriefingRegion.bestRating} arrive on the briefing
 * payload and are copied through untouched. That is not a style preference: {@code bestRating} is
 * {@code votingStats.maxRating()}, which falls back to canopy slots when a region has no sky slot at
 * all — a rule that lives on the backend and cannot be reconstructed here, because the client cannot
 * see which fallback fired. A {@code Math.max} over this window's drawn spots would be a third
 * answer to a question two surfaces already agree on, and it would disagree in exactly the seasons
 * the fallback exists for.
 *
 * <h2>The two levels of "best", and why the rail may not blur them</h2>
 *
 * <p>P1 recorded the trap: {@code BriefingRegion.bestRating}'s canopy fallback is per <b>region</b>
 * while {@code BriefingWindow.bestRating}'s is per <b>window</b>, so an all-wood region inside a
 * mixed window publishes its wood (5) where the window publishes the sky's best (4). Both are true
 * and neither is wrong — but a rail printing 5★ under a header printing 4★ presents two populations
 * as one number.
 *
 * <p>The resolution is the projector's own: {@code PlanWindowProjector.rank} drops a region holding
 * no non-canopy slot before it ranks anything, so {@link buildRegionRows} applies the same filter
 * through {@link eligibleRegions} and the divergent region is simply not on the rail. What is left
 * is that the {@code All N regions} cell states the WINDOW's best (the same number the card header
 * prints, from the same field) while each region cell states its OWN — two labelled cells, never one
 * number doing both jobs.
 *
 * <p>That filter is also the fix for the defect P2's review caught one surface along: ranking on the
 * raw region list let an all-canopy region's inverted-polarity mean (a canopy GO means heavy cloud
 * and mist) take rank 1 under a header reading "Poor", beside a field painting no heat there — the
 * field is sky-gated (`heatSpots.js`) and would have contradicted the rail on screen.
 *
 * <h2>Region names are joined byte-identically</h2>
 *
 * <p>Nothing here trims or normalises {@code regionName}. It is the field's focus key
 * (`heatSpots.js` sets {@code rid} from the same string), and the kernel answers a focus matching
 * no point by multiplying <em>every</em> weight by 1e-4 — the whole canvas fades to transparent on
 * click, with nothing in the console.
 */

/**
 * Cells whose count clause may use the word "reach", by the same three conditions the window
 * header's own {@code withinReachCount} applies.
 *
 * <p>The tier has to carry a threshold (under "Any" nothing was gated, so the word describes no
 * act); no rating floor may be active (the drawn set is then gated on two axes and this clause names
 * one of them); and every drawn spot has to carry a drive time, because an unknown one passes every
 * tier and a part-measured set is not a set that is within reach.
 *
 * @param {Array}   spots        the region's drawn spots
 * @param {?number} limitMinutes the active tier's threshold, or null for "Any"
 * @param {?number} minRating    the active floor's threshold, or null for "Any rating"
 * @returns {boolean} whether "N in reach" is a claim the data supports
 */
function reachWordHolds(spots, limitMinutes, minRating) {
  if (limitMinutes == null || minRating != null || spots.length === 0) return false;
  return spots.every((spot) => spot.driveMinutes != null);
}

/** The shortest measured drive among a region's spots, or null when none was measured. */
function nearestDrive(spots) {
  let nearest = null;
  for (const spot of spots) {
    const drive = spot.driveMinutes;
    if (typeof drive !== 'number' || !Number.isFinite(drive)) continue;
    if (nearest === null || drive < nearest) nearest = drive;
  }
  return nearest;
}

/**
 * The rail's cells for one window, ranked by the served {@code meanRating}, best first.
 *
 * <p>The count is of what this window <b>draws</b> for that region — the same array the spot strip
 * renders — so tapping a cell can never reveal a different number from the one on it. A region with
 * nothing drawn states its distance instead, and <b>only when reach is what emptied it</b>: a
 * region emptied by the rating floor is a region you can drive to, and "2h 38min away" over it
 * would name the wrong control. That distinction is the same one {@code WindowSpotSheet}'s
 * {@code openTierId} draws from {@code reachedTotal}, one level down.
 *
 * @param {?object} es       the event summary, for the served region records
 * @param {Array}   spots    the window's DRAWN spots (reach- and rating-gated)
 * @param {Array}   allSpots the window's spots before either gate
 * @param {object}  [lens]   {@code limitMinutes} and {@code minRating}, each null when that control
 *                           gates nothing
 * @returns {Array<object>} rail rows, ranked
 */
export function buildRegionRows(es, spots, allSpots, lens) {
  const limitMinutes = lens?.limitMinutes ?? null;
  const minRating = lens?.minRating ?? null;
  const drawnBy = groupByRegion(spots);
  const allBy = groupByRegion(allSpots);

  const rows = eligibleRegions(es).map((region) => {
    const name = region.regionName;
    const drawn = drawnBy.get(name) || [];
    const all = allBy.get(name) || [];
    // Re-derived from `allSpots` rather than taken as a fourth argument, and it is the SAME gate the
    // card ran: `gateSpotsByReach` is pure and the threshold is the one that produced `spots`, so
    // there is no second answer to disagree with — only a second application of one rule.
    const survivedReach = gateSpotsByReach(all, limitMinutes).length;
    // Only when REACH is what emptied it — see the method comment.
    const away = drawn.length === 0 && survivedReach === 0 ? nearestDrive(all) : null;
    const reachHolds = reachWordHolds(drawn, limitMinutes, minRating);
    return {
      name,
      verdict: region.displayVerdict || 'AWAITING',
      verdictLabel: VERDICT_LABEL[region.displayVerdict] || VERDICT_LABEL.AWAITING,
      // Served, both of them. See the module comment for why neither is re-derived.
      meanRating: finite(region.meanRating),
      bestRating: finite(region.bestRating),
      // Served too, and for the same reason twice over: the client has no previous build to
      // subtract from, and could not reconstruct one if it had — the comparand is a mean over the
      // backend's own voting-slot rule, taken from a payload that has since been overwritten.
      // Null passes straight through as null: the band renders nothing rather than a `—`, which
      // would claim a measured stillness where there is simply no basis (see `movement.js`).
      meanRatingDelta: finite(region.meanRatingDelta),
      summary: region.summary || null,
      // Claude's own headline and explanation for this region on this night, served on
      // {@code BriefingRegion} (GO/MARGINAL only, so both are routinely null) and carried through
      // untouched. The popup's prose slot prefers them to {@code summary} — plan-matrix §5 — and
      // {@code HeatmapGrid} already prefers the same pair in its hover tip, so the two arms read one
      // payload the same way round. Null passes straight through: the slot has an explicit null
      // path and degrade is silence.
      glossHeadline: region.glossHeadline || null,
      glossDetail: region.glossDetail || null,
      drawnCount: drawn.length,
      // The word the cell may use for its count, decided here so the component renders a string
      // rather than re-answering an honesty question three files from the rule.
      countNoun: reachHolds ? 'in reach' : 'spots',
      // The band's `within <tier>` figure, and it takes a NARROWER test than the count noun above.
      //
      // Seen in the browser on 2026-08-19: with a 45 min tier and no home postcode, every drive is
      // unknown, every spot passes the gate, and the figure read `within 45 min — 6` about six
      // locations nobody had measured a drive to. So the tier having a threshold is not enough — the
      // drives have to exist.
      //
      // But the rating floor, which DOES disqualify the word "in reach" on a cell (a count gated on
      // two axes named after one), does not disqualify this figure: it sits beside `at 4★+ · M of N`,
      // and the two figures together are the funnel the design draws. Naming one axis is misleading
      // where the other is invisible; here it is on screen, eight pixels away.
      reachFigureHolds: limitMinutes != null && drawn.every((s) => s.driveMinutes != null),
      awayLabel: away == null ? null : `${formatDriveDuration(away)} away`,
      // The band's two lens figures, and they are deliberately measured on DIFFERENT populations:
      // the rating floor's denominator is everything this region holds in this window, because that
      // is the set the floor chose from; the tier's numerator is what survived BOTH, because that is
      // what is drawn. Prototype `regionPanel`'s `nTot`/`nRate`/`nReach`, in this arm's gate order.
      totalCount: all.length,
      atFloorCount: gateSpotsByRating(all, minRating).length,
    };
  });

  // Ranked on the served mean, best first. Name is the tiebreak so the order is TOTAL — without it
  // two regions on the same mean can swap places between renders, which reads as the rail flickering
  // for no reason. A region with no mean ranks last rather than as a zero: "not scored" and "scored
  // badly" are different statements, and the same rule `windowFirstCards.js` applies to windows.
  return rows.sort((a, b) => {
    const ma = a.meanRating ?? -Infinity;
    const mb = b.meanRating ?? -Infinity;
    return mb === ma ? a.name.localeCompare(b.name) : mb - ma;
  });
}

/** A number the payload actually carried, or null — never a coercion. */
function finite(value) {
  return typeof value === 'number' && Number.isFinite(value) ? value : null;
}

/** Drawn spots bucketed by their region name, byte-identically keyed. */
function groupByRegion(spots) {
  const byRegion = new Map();
  for (const spot of Array.isArray(spots) ? spots : []) {
    if (!spot || !spot.regionName) continue;
    const list = byRegion.get(spot.regionName);
    if (list) list.push(spot);
    else byRegion.set(spot.regionName, [spot]);
  }
  return byRegion;
}

/**
 * Each region's served mean across every rendered window, keyed by region name then by window key.
 *
 * <p><b>Keyed rather than positional</b>, for the reason {@code heatSpots.js} gives about its point
 * sets: the card list is not the event list, {@code selectUpcomingEvents} re-indexes when a window
 * passes mid-session, and an integer index into six windows looks exactly like the kernel's
 * {@code win} argument. The band looks its series up by the strip card's own key.
 *
 * <p>A region that is ineligible in one window is simply ABSENT from that window's entry, and the
 * band reads a miss as null — which is the same rendering an explicit null gets. The dots still
 * number six because the band iterates the WINDOW list and looks each one up, never the series'
 * own keys: a region that is all-canopy on one misty morning must not silently shorten its strip.
 *
 * @param {Array} upcomingEvents [{date, targetType}] — the rendered windows, in order
 * @param {Array} briefingDays   {@code briefing.days}
 * @returns {Map<string, Map<string, ?number>>} region name → window key → mean rating
 */
export function buildRegionSeries(upcomingEvents, briefingDays) {
  const series = new Map();
  for (const { date, targetType } of Array.isArray(upcomingEvents) ? upcomingEvents : []) {
    if (!date || !targetType) continue;
    const key = `${date}:${targetType}`;
    const day = (briefingDays || []).find((d) => d.date === date);
    const es = (day?.eventSummaries || []).find((e) => e.targetType === targetType);
    for (const region of eligibleRegions(es)) {
      const name = region.regionName;
      let windows = series.get(name);
      if (!windows) {
        windows = new Map();
        series.set(name, windows);
      }
      windows.set(key, finite(region.meanRating));
    }
  }
  return series;
}

/**
 * The filters actually in force, worded once for every surface that names them.
 *
 * <p>Two render sites read this — the band's header and the spot strip's footer — and they must say
 * the same thing in the same words, because they are eleven pixels apart on an open row. One helper
 * rather than two literals is what makes that structural instead of a convention.
 *
 * <p><b>Only what is in force.</b> A clause for an axis gating nothing ("any rating") describes a
 * control the reader has left alone, and §6's sweep removed exactly that kind of copy from four
 * other surfaces on this arm. An empty array is therefore a real answer — no filter is in force —
 * and both callers withhold the line entirely rather than printing a bare label above nothing.
 *
 * @param {object}  args
 * @param {?string} args.regionName  the selected region, or null
 * @param {?number} args.minRating   the active floor's threshold, or null when it gates nothing
 * @param {?string} args.ratingLabel the active floor's own word
 * @param {?number} args.limitMinutes the active tier's threshold, or null when it gates nothing
 * @param {?string} args.tierLabel   the active tier's own word
 * @returns {string[]} the clauses, region first
 */
export function activeFilterClauses({
  regionName, minRating, ratingLabel, limitMinutes, tierLabel,
}) {
  const clauses = [];
  if (regionName) clauses.push(regionName);
  if (minRating != null && ratingLabel) clauses.push(ratingLabel);
  // "within 2h 30min" rather than the bare label, because a drive time on its own reads as a fact
  // about a place — the tier is a ceiling, and the preposition is what says so. The band's own
  // figure uses the same form for the same reason.
  if (limitMinutes != null && tierLabel) clauses.push(`within ${tierLabel.toLowerCase()}`);
  return clauses;
}

/**
 * The spots one region leaves on screen — the open row's third gate.
 *
 * <p>Composes with {@code gateSpotsByReach} and {@code gateSpotsByRating} rather than replacing
 * either: a region selection narrows <em>where</em>, the lens narrows <em>how far</em> and
 * <em>how good</em>, and all three are the reader's own choices held at once. It runs LAST, on the
 * already-gated pool, so the strip below the rail can never show a spot the rail's own count did
 * not include — the two read one array.
 *
 * <p>A null region returns the input untouched, which is what makes "All regions" a peer choice
 * rather than a branch. Names are compared byte-identically, for the reason {@code heatSpots.js}
 * gives at length: nothing normalises {@code regionName} anywhere in this join.
 *
 * @param {Array}   spots      the drawn spots
 * @param {?string} regionName the selected region, or null for all
 * @returns {Array} the spots that pass, in the order they arrived
 */
export function gateSpotsByRegion(spots, regionName) {
  if (!regionName) return spots;
  return (Array.isArray(spots) ? spots : []).filter((spot) => spot.regionName === regionName);
}
