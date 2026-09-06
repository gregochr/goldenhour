import { byMeanThenName } from './windowFirstRegions.js';
import { VERDICT_LABEL } from './windowFirstCards.js';
import { resolveRegionDisplay } from './tierUtils.js';
import { regionDriveMinutes } from './planningArea.js';
import { formatDriveDuration } from './briefingDisplay.js';
import { eventInstantOf, lookupForWindow } from './locationSheet.js';
import { calloutLeaveBy } from './mapCallout.js';

/**
 * The Map tab's drilldown — window → regions (map-landing-plan.md §3 L5,
 * `docs/design/map-landing/README.md` §5). Pure logic only.
 *
 * <p>The map draws one window at a time. The pill says which window and how good it is; this is the
 * level that says <em>where</em>, by naming every region in the reader's scope with the same served
 * figures the Plan tab ranks on. L6 adds the level below it (one region's own locations), which is
 * why this module is named for the drilldown rather than for one panel.
 *
 * <h2>Nothing here re-derives a served figure</h2>
 *
 * <p>Every rating on a row is served: the tier is {@code BriefingRegion.displayVerdict} through the
 * app's own {@link resolveRegionDisplay}, and the mean and the ceiling are
 * {@code BriefingRegion.meanRating}/{@code bestRating} copied through. ⚠️ <b>Do not take a client
 * max over the drawn spots instead</b> — `windowFirstRegions.js`'s module doc records why at length:
 * {@code bestRating}'s canopy fallback is computed on the backend over a population the client
 * cannot reconstruct.
 *
 * <p>⚠️ <b>The three per-window region indexes share a key SHAPE and not a key SET</b>, so a row's
 * ceiling must not be joined onto its verdict by key: {@code buildRegionVerdictIndex} is
 * canopy-filtered and {@code buildRegionBestIndex} is not, so a canopy-only region has a
 * {@code bestRating} and no verdict entry (plan §3 L5 step 7). This module makes that impossible
 * rather than merely avoiding it — it reads <b>one</b> index, and takes the mean, the ceiling and
 * the tier off the <em>same served record</em>. {@code buildRegionVerdictIndex} happens to store
 * whole records — for its OWN stated reason, that a projection is one more place for a field to go
 * stale, not for this one — which is what makes that possible; and its canopy filter has already
 * run at build time, where it belongs (a filter applied after a scope narrowed the candidates would let a window that is
 * all-canopy outside your area but mixed inside it answer the canopy question differently on the
 * two tabs).
 *
 * <h2>The one client aggregation, and it is licensed</h2>
 *
 * <p>{@link buildPanelRegionRows}' {@code atFourPlus}/{@code placeCount} pair is counted in the
 * browser, over the reader's own scope pool. That is the same per-user-join shape as
 * {@code mapVerdict}'s tally, and ✅ both are <b>named members</b> of CLAUDE.md's licensed class —
 * its sixth, the Map tab's scope class, entry (d) — added at L7 with the other three. ⚠️ That
 * bullet states the population explicitly, because L6 had to fix it: the places <b>this window
 * could rate</b>, never every place in scope. {@link buildRegionLocationRows} below is
 * deliberately NOT a member (plan §4 #32)
 *
 * <p>It is nonetheless the honest population: <b>scope only</b>, before every reader filter, exactly
 * as the pill's verdict is. A reader hiding 3★ locations must not change how many places a region
 * has at 4★+, any more than they can turn a Maybe into a Worth it.
 *
 * <h2>⚠️ A NIGHT window has no rows at all, and cannot have</h2>
 *
 * <p>{@link buildPanelRegionRows} keys on {@code date|targetType|regionName}, and the index is
 * folded from {@code eventSummaries}, whose {@code targetType} is SUNRISE/SUNSET only — so an ASTRO
 * or AURORA row misses every key and the panel renders its note and an empty line. That is not a
 * gap in the lookup: <b>nothing serves a per-region night rollup at all</b> (map-tab-v2-plan.md
 * <b>O-16</b>), the same fact that made §6 Q1 render the pill's night cell empty.
 *
 * <p>⚠️ So the spec's "verdict word (or an em dash for night events)" presumes rows that cannot
 * exist here. An earlier cut shipped an {@code isSolar} branch to draw that dash — reachable only by
 * handing this function a night flag with a SOLAR target type, which {@code MapView} never does, and
 * two review lenses found the branch dead with its test pinning an impossible input. It is gone
 * rather than left as scenery (map-landing-plan.md §4 #27). Building night rows from
 * {@code regionsJump.buildNightRegionBest} — the already-licensed per-region night max — is possible
 * and was deliberately not done: it has no mean to rank on, so it needs a ranking rule by CEILING
 * that contradicts step 3's "ranked by mean" and that the design never specified. An owner call.
 */

/**
 * The star a location must reach to be counted on a row. **Fixed, not the reader's rating floor** —
 * the spec's copy is the literal `N of M at 4 stars+`, and the figure is a claim about the region
 * rather than about the reader's lens. Gating it on `minStars` would make the same region read
 * `2 of 9` and `9 of 9` depending on a control that is about what the map DRAWS.
 */
export const PANEL_STAR_FLOOR = 4;

/** A number the payload actually carried, or null — never a coercion. */
function finite(value) {
  return typeof value === 'number' && Number.isFinite(value) ? value : null;
}

/**
 * One region row per in-scope region that has a sky answer, ranked best-mean first.
 *
 * <p><b>Read out of the pill's own index</b>, so the panel and the pill can never disagree about
 * which regions have an answer or what it is — the canopy rule already ran when the index was
 * built. Then narrowed to the reader's scope, so the rows describe the population the counts footer
 * already reports.
 *
 * @param {object} args
 * @param {?Map<string, object>} args.index from `mapVerdict.buildRegionVerdictIndex` — the served
 *        region records, already canopy-filtered
 * @param {?string} args.date the window's date
 * @param {?string} args.targetType SUNRISE or SUNSET
 * @param {Array<string>} args.regionsInScope region names in the reader's scope, from
 *        `mapVerdict.regionNamesOf` — the SAME list the pill's tally counts
 * @param {Array<{id: *, rid: ?string, r: Array<number>}>} args.points the window's served per-location
 *        scores (`heat.pointsByKey`), scope-narrowed by the caller
 * @param {?Map<*, {driveMinutes: ?number}>} args.driveMap the drive map in force — home reach OR the
 *        region-base matrix, never both (`MapView`'s `activeDriveMap`)
 * @param {Array<{id: *, regionName: ?string}>} args.spots the reader's scope pool — the drive map's
 *        join, AND the denominator: how many PLACES each region holds here
 * @returns {Array<object>} ranked rows — always empty for a night window, see the module doc
 */
export function buildPanelRegionRows({
  index, date, targetType, regionsInScope, points, driveMap, spots,
}) {
  const inScope = new Set((Array.isArray(regionsInScope) ? regionsInScope : []).filter(Boolean));

  // ⚠️ **The population of every figure on a row is the places this window COULD rate**, and two
  // adversarial-review lenses found it was not, independently.
  //
  // <p>The denominator counts PLACES, not rows we happen to hold a rating for — an earlier cut had
  // it the other way round, and counting points on both sides made the line literally "N of M
  // scored", which `window-first-redesign-plan.md` §6's fourth bullet and CLAUDE.md's licensed-class
  // bullet ban by name (⚠️ this comment cited `plan-matrix-plan.md` §5 clause 5, which does not
  // exist — §5 there is Data mapping, and its §3 rule 5 is where the ban is restated).
  // But the fix over-corrected: `buildHeatSpots` KEEPS a non-sky location as a spot and withholds
  // only its scores — `isSkyPromptCandidate` decides, and `skySubject` records it — so such a place
  // is in the denominator and can never, by construction, reach the numerator. A region with five
  // sky locations (three at 4★+) and four wildlife hides read `3 of 9`, understating every
  // wood-bearing region uniformly. That is the mirror image of the phrasing the rule bans: not a
  // denominator of rows-we-scored, but one containing places the question does not apply to.
  //
  // ⚠️ **A WATERFALL is a sky subject and is NOT withheld** — `SKY_SUBJECT_TYPES` is LANDSCAPE,
  // SEASCAPE and WATERFALL. The withheld set is WILDLIFE-, WOODLAND- or BLUEBELL-only sites, and an
  // untagged location counts (`isSkyPromptCandidate` returns true for an empty list). The first cut
  // of this comment named a waterfall as an example and a fact-check caught it before the error
  // reached CLAUDE.md's own wording.
  //
  // <p>The same filter feeds the NEAREST drive, for the same reason: `nearest 12 min` over a hide,
  // printed above four rows starting at 1h 20min, describes a journey to somewhere a sky verdict
  // does not apply. ⚠️ A residual remains and is deliberate — "nearest" is the nearest place a sky
  // verdict applies to, not the nearest of the four the panel lists, because those four are chosen
  // by STAR (map-landing-plan.md §4 #33). ⚠️ And `skySubject` is a per-LOCATION flag, set once from
  // `locationType`: it does not vary by window, so neither figure may be described as "what THIS
  // window could rate" — a precision the flag has not got.
  //
  // <p>`=== false` rather than `!skySubject`: `skySubject` is set on every spot `buildHeatSpots`
  // emits, so an absent field means a shape this function has not seen, and counting it keeps the
  // pre-existing behaviour rather than silently emptying a region.
  const scoreable = (Array.isArray(spots) ? spots : []).filter((s) => s?.skySubject !== false);

  // ⚠️ No pre-filter on region. A review lens measured that one was a dead guard: this map is keyed
  // by region name and read only at `minutes.get(name)` for names already in scope, so out-of-scope
  // keys are unreachable by construction. A guard no test can ever assert implies a protection that
  // is not there.
  const minutes = regionDriveMinutes(scoreable, driveMap);

  const places = new Map();
  for (const spot of scoreable) {
    const name = spot?.regionName;
    if (!name || !inScope.has(name)) continue;
    places.set(name, (places.get(name) ?? 0) + 1);
  }
  // The window's own scores, bucketed by the region name the points already carry (`rid` and
  // `regionName` are set from one value in `heatSpots.js` — the points keep only `rid`).
  const strong = new Map();
  for (const point of Array.isArray(points) ? points : []) {
    const name = point?.rid;
    if (!name || !inScope.has(name)) continue;
    const score = finite(point.r?.[0]);
    if (score != null && score >= PANEL_STAR_FLOOR) strong.set(name, (strong.get(name) ?? 0) + 1);
  }

  const rows = [];
  if (!index || !date || !targetType) return rows;
  for (const name of inScope) {
    // ⚠️ A MISS is "no sky answer", never "no data". Two causes, neither rare: a window past the
    // briefing's rendered horizon misses on every key, and the canopy filter drops a woodland-only
    // region from this index on every mixed window. Such a region has a
    // served `bestRating` in a sibling index and no verdict here, and printing it with a ceiling and
    // no word would re-enter the rated-wood defect from the other side.
    const region = index.get(`${date}|${targetType}|${name}`);
    if (!region) continue;
    const tier = resolveRegionDisplay(region);
    const driveMinutes = minutes.has(name) ? minutes.get(name) : null;
    rows.push({
      name,
      tier,
      verdictLabel: VERDICT_LABEL[tier] || VERDICT_LABEL.AWAITING,
      meanRating: finite(region.meanRating),
      bestRating: finite(region.bestRating),
      driveMinutes,
      driveLabel: driveMinutes == null ? null : formatDriveDuration(driveMinutes),
      placeCount: places.get(name) ?? 0,
      atFourPlus: strong.get(name) ?? 0,
    });
  }
  return rows.sort(byMeanThenName);
}

/**
 * The panel's one note, branched four ways — the spec's §5 table.
 *
 * <p>The base sentence is on every solar branch, because it answers the question the ranking itself
 * raises ("why is this region above that one when that one has the 5★?"). The several-regions
 * branch prefixes it; the all-in-scope-and-Poor branch <b>replaces</b> it, because there is no
 * choice to make between N write-offs and telling the reader the ranking rule would be answering a
 * question they no longer have.
 *
 * <p>⚠️ The scope wording follows §4 #7's two forms, not the spec's single string: "in your area"
 * only when the reader HAS an area to scope to. `MapView` passes the same `scopeIsArea` the pill's
 * region line takes, which already folds in `heat.hasHome` — a reader with no postcode is scoped to
 * the whole catalogue, and "all 9 regions in your area" over every region there is was the exact
 * defect L2 and L4 each fixed once.
 *
 * @param {object} args
 * @param {?{tier: string, sharingCount: number, allInScope: boolean, scopedRegionCount: number}}
 *        args.verdict from `mapVerdict.windowVerdict`; null on a night window or an unrated one
 * @param {boolean} args.isSolar
 * @param {boolean} [args.scopeIsArea]
 * @returns {string}
 */
export function windowPanelNote({ verdict, isSolar, scopeIsArea = true }) {
  if (!isSolar) return NIGHT_NOTE;
  if (!verdict) return RANKING_NOTE;
  if (verdict.allInScope && verdict.tier === 'STAND_DOWN') {
    const area = scopeIsArea ? ' in your area' : '';
    return `Poor in all ${verdict.scopedRegionCount} regions${area} — nothing here is worth the `
      + 'drive on this window. Rows are ranked by average, best first.';
  }
  const sharing = verdict.sharingCount + 1;
  if (sharing > 1) {
    // ⚠️ **The drive-time sentence is withheld under a Poor tier even when the branch above did not
    // fire.** `allInScope` counts EVERY name in scope, including regions the verdict index has no
    // entry for — so one woodland-only region in the reader's area (dropped by the canopy filter) is
    // enough to make an all-Poor area fall through to here. It then printed "4 regions are poor for
    // this window, so the choice between them is drive time" — advice to pick between write-offs,
    // the exact sentence the branch above exists to prevent. Two review lenses found it
    // independently. The design's condition was `all AND Poor`; its stated REASON is "there is no
    // choice to make between N write-offs", and the reason is the part to implement.
    if (verdict.tier === 'STAND_DOWN') {
      return `${sharing} regions are poor for this window — nothing there is worth the drive. `
        + 'Rows are ranked by average, best first.';
    }
    const word = (VERDICT_LABEL[verdict.tier] || VERDICT_LABEL.AWAITING).toLowerCase();
    return `${sharing} regions are ${word} for this window, so the choice between them is drive `
      + `time. ${RANKING_NOTE}`;
  }
  return RANKING_NOTE;
}

/** Why the rows are in the order they are in — the answer the ranking itself provokes. */
const RANKING_NOTE = 'Verdict is the strongest region’s average. Regions rank on that average, '
  + 'not on their ceiling — the reach filter only decides what the map draws.';

/** A night window has no solar verdict of its own, and cannot be a pick. */
const NIGHT_NOTE = 'A night event, scored from darkness, clarity and Kp rather than the solar '
  + 'forecast. It has no Worth it / Maybe / Poor of its own and cannot be the week’s Best bet.';

/**
 * How many locations the region panel names (map-landing-plan.md §3 L6 step 1, "top four
 * locations"). A shortlist, not a listing: the panel's job is to end in ONE place worth driving to,
 * and the sheet below it is where a reader who wants the whole region goes.
 */
export const REGION_PANEL_LOCATIONS = 4;

/**
 * The region panel's top locations for one window — the second level of the drilldown
 * (map-landing-plan.md §3 L6, `docs/design/map-landing/README.md` §5).
 *
 * <h2>The population is the window's SCORED locations in that region, and nothing else</h2>
 *
 * <p>{@code heatPointsFor} emits a point only where the score is finite, so an unscored location has
 * no star to rank on and no place in a "top four" — the same population the region row's own
 * {@code atFourPlus} numerator counts, one level up. It is scope-only and pre-filter, like every
 * other figure on this drilldown: a reader hiding 3★ locations must not change which four places a
 * region's panel names, any more than they can turn a Maybe into a Worth it.
 *
 * <h2>⚠️ The two per-location facts are LOOKED UP, never re-derived</h2>
 *
 * <p><b>The tide glyph</b> reads {@code BriefingSlot.TideInfo.tideOnTheLight} through
 * {@code locationSheet.buildTideAlignmentIndex} — served since #749, per location, per window. The
 * plan says it in as many words: <em>do not</em> re-derive one representative coastline's geometry.
 * CLAUDE.md's tide-axis rule is why a served answer is the only acceptable one here — the run row's
 * extremes belong to a separately-selected representative and its {@code tideAligned} sibling asks a
 * preference-weighted question this glyph must not answer.
 *
 * <p><b>The leave-by time</b> comes from {@code mapCallout.calloutLeaveBy} over
 * {@code locationSheet.eventInstantOf} — the SAME recovery the callout already makes for its own
 * `Leave by` fact, from the same {@code buildScoreIndex} row. ⚠️ A second recovery would risk
 * disagreeing with that one about which boundary is the event ({@code eventInstantOf}'s own doc
 * records why it exists at all), and the callout and this panel can both be open over one map.
 * {@code heat.windows}' {@code time} is NOT usable for it: that is the window's roster-wide header
 * clock — some other location's sunrise, minutes away — and `leaveBy`'s own doc names per-location
 * event times as the reason it takes an instant rather than a window.
 *
 * <h2>Ranking: stars, then the shorter drive, then the name</h2>
 *
 * <p>The design's own rule ({@code map-tab-v4.js} `regionsFor`: {@code scoreOf(b)-scoreOf(a) ||
 * driveOf(a)-driveOf(b)}), plus a name tiebreak the prototype has no need of and this does — its
 * sort would otherwise leave two equally-rated, equally-far locations in whatever order the point
 * array happened to hold, so the panel could name a different four across two renders of one
 * window. An UNMEASURED drive sorts last rather than first: it is not a nearer journey, it is no
 * journey at all, and `regionDriveMinutes` draws the same distinction one level up.
 *
 * @param {object} args
 * @param {?string} args.regionName the region the panel is about
 * @param {Array<{id: *, name: string, rid: ?string, r: Array<number>}>} args.points the window's
 *        served per-location scores (`heat.pointsByKey`), un-narrowed — this filters by region
 * @param {?Map<*, {driveMinutes: ?number}>} args.driveMap the drive map in force
 *        (`MapView`'s `activeDriveMap`) — home reach OR the region-base matrix, never both
 * @param {?{byId: Map, byName: Map}} args.scoreIndex from `locationSheet.buildScoreIndex`
 * @param {?{byId: Map, byName: Map}} args.tideIndex from `locationSheet.buildTideAlignmentIndex`
 * @param {?string} args.date
 * @param {?string} args.targetType SUNRISE or SUNSET
 * @param {number} [args.limit]
 * @returns {Array<object>} at most `limit` rows, best first
 */
export function buildRegionLocationRows({
  regionName, points, driveMap, scoreIndex, tideIndex, date, targetType,
  limit = REGION_PANEL_LOCATIONS,
}) {
  if (!regionName) return [];
  const rows = [];
  for (const point of Array.isArray(points) ? points : []) {
    if (!point || point.rid !== regionName) continue;
    const rating = finite(point.r?.[0]);
    if (rating == null) continue;
    const name = point.name || '';
    const driveMinutes = finite(driveMap?.get?.(point.id)?.driveMinutes);
    const eventTimeIso = eventInstantOf(
      lookupForWindow(scoreIndex, point.id, name, date, targetType), targetType,
    );
    const leave = calloutLeaveBy(eventTimeIso, driveMinutes);
    rows.push({
      id: point.id ?? null,
      name,
      rating,
      driveMinutes,
      driveLabel: driveMinutes == null ? null : formatDriveDuration(driveMinutes),
      leaveTime: leave?.time ?? null,
      leaveDayWord: leave?.dayWord ?? null,
      // ⚠️ A MISSING entry and a `false` one are different claims and only the deriver knows which
      // is true (`buildTideAlignmentIndex` skips rather than indexes an underivable slot), so this
      // is read for TRUTH alone — the glyph is drawn or it is not, and nothing anywhere says
      // "the tide does not land on the light here".
      tideOnLight: Boolean(lookupForWindow(tideIndex, point.id, name, date, targetType)?.onTheLight),
    });
  }
  rows.sort((a, b) => (b.rating - a.rating)
    || ((a.driveMinutes ?? Infinity) - (b.driveMinutes ?? Infinity))
    || a.name.localeCompare(b.name));
  return rows.slice(0, Math.max(0, limit));
}

/**
 * The region panel's stats line, as segments — `N of M at 4★+ · nearest <drive> · average N★`
 * (map-landing-plan.md §3 L6 step 1).
 *
 * <p><b>Segments rather than one string</b>, for two reasons that are both already this file's
 * rules. A `★` needs a hidden glyph and a spoken alternative beside it (NVDA at its default symbol
 * level does not speak U+2605, so `4 of 9 at 4★+` announces as "4 of 9 at 4 plus"), which a flat
 * string cannot carry. And an unmeasured drive must be OMITTED rather than dashed — CLAUDE.md's
 * reach-honesty rule — so the separators are the caller's to place between whatever survives.
 *
 * <p>⚠️ <b>Taken off the window panel's OWN row</b>, never recounted here. The row the reader
 * pressed carries `atFourPlus`, `placeCount`, `driveLabel` and `meanRating` already; a second count
 * over the same pool is a second chance to answer differently about a region whose figures are on
 * screen one press apart.
 *
 * <p>⚠️ <b>No "rated locations".</b> The prototype's copy is `N of M rated locations at 4★+`, which
 * is literally the "N of M scored" the licensed-class rule bans by name — and our M counts PLACES
 * in scope, not rows we hold a rating for, so the prototype's noun would be false about our own
 * number as well as forbidden (map-landing-plan.md §4 #25).
 *
 * @param {?object} row one row from {@link buildPanelRegionRows}
 * @returns {Array<{key: string, text: string, glyph: ?string, spoken: ?string}>}
 */
export function regionStatSegments(row) {
  if (!row) return [];
  const segments = [{
    key: 'hits',
    text: `${row.atFourPlus ?? 0} of ${row.placeCount ?? 0} at ${PANEL_STAR_FLOOR}`,
    glyph: '★+',
    spoken: ' stars or better',
  }];
  if (row.driveLabel) segments.push({ key: 'near', text: `nearest ${row.driveLabel}`, glyph: null, spoken: null });
  const mean = finite(row.meanRating);
  if (mean != null) {
    segments.push({
      key: 'mean',
      // One decimal, and the trailing `.0` trimmed: `average 4★` reads as the figure it is, where
      // `average 4.0★` implies a precision the roll-up does not have.
      text: `average ${Number(mean.toFixed(1))}`,
      glyph: '★',
      spoken: ' stars',
    });
  }
  return segments;
}
