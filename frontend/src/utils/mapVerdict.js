import { eligibleRegions, pickTopEligibleRegion } from './windowFirstCards.js';
import { resolveRegionDisplay } from './tierUtils.js';

/**
 * The Map tab's window verdict and its region tally — pure logic only
 * (`docs/engineering/map-landing-plan.md` §3 L1, `docs/design/map-landing/README.md` §1).
 *
 * <p>The map draws one window at a time and colours it by per-location score, so it can say
 * neither the verdict the Plan tab states on every card nor which region that verdict is true of.
 * This module answers both, for any window, over any scope.
 *
 * <h2>Nothing here derives a verdict — it selects one</h2>
 *
 * <p>Every tier this module returns is a served {@code BriefingRegion.displayVerdict}, read through
 * the app's own {@link resolveRegionDisplay}. There is <b>no threshold function</b>, and adding one
 * would be a defect rather than a fallback: the design bundle asks for a client rule of
 * {@code >= 3.7 Worth it / >= 2.8 Maybe} "so the fallback lands in the same place" as the API, and
 * it does not — {@code BriefingRatingStats} bands a region <em>mean</em> at {@code >= 3.5} and
 * {@code >= 2.5}, so a region averaging 3.6 would read Worth it on the Plan tab and Maybe on the
 * map. Since {@code displayVerdict} is never null on a served region, the fallback those thresholds
 * were meant to reconcile is unreachable anyway. See map-landing-plan.md §4 #1 and §5 D-1.
 *
 * <p>⚠️ {@code utils/mapLabels.js#verdictWord} is <b>not</b> the function to reuse here, and this
 * module deliberately does not import it. Its 3.7/2.8 constants are correct for the quantity it
 * serves — a per-location whole star, where they collapse to {@code >= 4} / {@code >= 3} and match
 * {@code DisplayVerdict.resolve}'s per-location bands exactly. A region mean is a different
 * quantity with different bands.
 *
 * <h2>Why the tally is computed in the browser at all</h2>
 *
 * <p>The count beside the verdict ("+2", "everywhere in your area") is taken over the regions
 * <em>in the reader's current scope</em>, and scope is per-user: it is the ≤3h planning area
 * measured from a home postcode, or a region base the reader is planning from. So "how many regions
 * in <em>your</em> area are Worth it" has no servable answer on the shared, ETag-revalidated
 * {@code GET /api/briefing} payload — the same reasoning that keeps reach on its own never-cached
 * contract (map-landing-plan.md §5 D-4; the exit is §6 Q4, a never-cached per-user endpoint).
 *
 * <p>⚠️ <b>It is not yet a named member of CLAUDE.md's licensed per-user-join class</b> — that
 * bullet closes each of its sub-lists with "Members: those N, nothing else", and adding this one is
 * L7's job (map-landing-plan.md §3 L7 step 2). Stating the licence here before the list grants it
 * would be the self-authorising move that ⚠️ exists to stop, so this comment claims a case, not a
 * permission.
 *
 * <p><b>What it actually folds over.</b> The tier and the tally are served
 * {@code displayVerdict}s — nothing here bands a number into a word. But the region whose tier is
 * taken is chosen by an argmax over served {@code meanRating}, so this is not a ratings-free
 * computation and must not be described as one: it is a <em>selection</em> among served verdicts,
 * ordered by a served rating, which is a different and cheaper thing than deriving either.
 */

/**
 * The EV row kind this module answers for. Mirrors {@code mapEvents.EVENT_KIND.SOLAR} rather than
 * importing it: `mapEvents.js` already imports from `windowFirstCards.js` as this module does, and
 * a shared literal is cheaper than widening either module's import graph for one string. Pinned by
 * a test that asserts the two agree.
 */
const SOLAR_KIND = 'solar';

/**
 * Composite key for the per-window region index — {@code date|targetType|regionName}, the same
 * shape and the same join key {@code utils/regionsJump.js#buildRegionBestIndex} and
 * {@code utils/mapCallout.js#buildRegionGlossIndex} already use for this arm's per-window region
 * lookups.
 *
 * <p>⚠️ <b>Same key SHAPE, deliberately NOT the same key SET.</b> This index is canopy-filtered
 * ({@link eligibleRegions}) and the other two are not, so a canopy-only region has a
 * {@code bestRating} entry and a gloss entry and no verdict entry. Joining the three on one key to
 * put a "ceiling" beside a ranked mean — which is exactly what the region panel wants to draw —
 * would print a ceiling for a region this index deliberately dropped, re-entering the rated-wood
 * defect from the other side. Look a region up in each index separately, and treat a miss here as
 * "no sky answer", never as "no data".
 *
 * @param {string} date
 * @param {string} targetType
 * @param {string} regionName
 * @returns {string}
 */
function keyOf(date, targetType, regionName) {
  return `${date}|${targetType}|${regionName}`;
}

/**
 * Every eligible region's served record for every rendered window, keyed
 * {@code date|targetType|regionName}.
 *
 * <p><b>Folded over {@link eligibleRegions}, not over {@code summary.regions}.</b> That filter is
 * the canopy rule {@code PlanWindowProjector.rank} applies before it ranks anything — a region
 * holding no non-canopy slot is dropped, with an all-canopy <em>window</em> keeping its woods. It
 * has to run here, at index-build time, because it is a property of the whole window: applying it
 * after a scope narrowed the candidates would let a window that is all-canopy outside your area but
 * mixed inside it answer the canopy question differently on the two tabs. Without it a rated wood
 * could win the map's argmax on a misty dawn where the backend, the Plan strip and the heat field
 * had all already dropped it — canopy scores run on inverted polarity.
 *
 * <p><b>The values are the served records themselves, not copies.</b> A projection would be one
 * more place for a field to be mis-copied or to go stale, and {@link pickTopEligibleRegion} and
 * {@link resolveRegionDisplay} both read the record directly. Map values are references, so this
 * costs no memory beyond the index itself.
 *
 * <p>First-inserted wins on a repeated key, the same collision rule {@code buildRegionBestIndex}
 * applies. ⚠️ That makes the two indexes consistent in RULE, not in outcome: they fold different
 * region lists (see {@code keyOf} above), so on a duplicated key whose first row is canopy-only in
 * a mixed window they genuinely keep different rows. Neither is wrong; they are answering about
 * different populations.
 *
 * @param {Array<{date: string, eventSummaries: Array<object>}>} days {@code briefing.days}
 * @returns {Map<string, object>} composite key to that region's served record for that window
 */
export function buildRegionVerdictIndex(days) {
  const index = new Map();
  for (const day of Array.isArray(days) ? days : []) {
    if (!day?.date) continue;
    for (const summary of day.eventSummaries ?? []) {
      if (!summary?.targetType) continue;
      for (const region of eligibleRegions(summary)) {
        if (!region?.regionName) continue;
        const key = keyOf(day.date, summary.targetType, region.regionName);
        if (!index.has(key)) index.set(key, region);
      }
    }
  }
  return index;
}

/**
 * One window's verdict, the region it is true of, and how many other in-scope regions share it.
 *
 * <p><b>The three label cases the design specifies fall out of the return value</b>, and the third
 * is the one that regressed while the design was being made:
 * <ul>
 *   <li>{@code sharingCount === 0} → name the region alone ("WORTH IT · the Lakes")</li>
 *   <li>{@code sharingCount > 0 && !allInScope} → name it and count the rest
 *       ("WORTH IT · Northumberland +2")</li>
 *   <li>{@code allInScope} → name NO region ("POOR · everywhere in your area"). Naming the top one
 *       when they all share the tier presents the least-bad region as a destination, which is the
 *       overclaim of case 1 inverted.</li>
 * </ul>
 *
 * <p><b>What counts in the denominator.</b> {@code scopedRegionCount} is every distinct region name
 * in scope, including ones this index has no entry for. That is deliberate and it is the safe
 * direction: a region with no entry is not evidence that everywhere agrees, so its presence makes
 * {@code allInScope} false and the label falls back to naming a region and counting — which
 * understates agreement rather than asserting it. Above the one-region guard,
 * {@code allInScope === (sharingCount + 1 === scopedRegionCount)}.
 *
 * <p>⚠️ <b>The common cause of a missing entry is the canopy filter, not a thin payload.</b> A
 * woodland-only region inside the reader's area is dropped from the index on every mixed window
 * (see {@link buildRegionVerdictIndex}), so it sits in the denominator and never in
 * {@code sharingCount} — which makes the all-in-scope case rarer for that reader than the design
 * assumes. That is the honest answer as far as this module goes: a region with no sky answer cannot
 * be said to agree. Whether "everywhere in your area" should instead count only the regions this
 * window <em>could</em> answer for is a copy decision, and it belongs to the phase that renders the
 * words (map-landing-plan.md §6 Q8), not to the phase that counts.
 *
 * <p><b>A one-region scope never says "everywhere".</b> With a single region in scope the word
 * would be true and useless — it is the region, named. Hence the {@code > 1} guard, which is the
 * design's own {@code n > 1} condition.
 *
 * <p><b>Returns null when no in-scope region carries a finite mean</b> — an unrated window, where
 * the honest answer is to render nothing rather than a word. This is also why the returned
 * {@code tier} is almost never {@code AWAITING}: a finite {@code meanRating} means the region was
 * scored, and a scored region's {@code displayVerdict} is one of the three real bands. The
 * exception is a legacy cached payload carrying the triage {@code verdict} and no
 * {@code displayVerdict}, which {@link resolveRegionDisplay} maps rather than dropping.
 *
 * <p>⚠️ <b>{@code null} is overloaded and is NOT a "this is a night event" signal.</b> It means any
 * of: no index, no date, no target type, an empty scope, no scoped region present in the index, or
 * nothing scored. A caller that needs to tell a night window from an unrated solar one must read
 * the EV row's own {@code kind} ({@code EVENT_KIND.SOLAR}) — that is the discriminator, and it is
 * the one the region panel's four-way note will need. Do not lean on {@code undefined}-versus-
 * {@code null} anywhere for this; it is an artefact of which object a field was never set on.
 *
 * @param {object} args
 * @param {?Map<string, object>} args.index from {@link buildRegionVerdictIndex}
 * @param {?string} args.date the window's date
 * @param {?string} args.targetType SUNRISE or SUNSET — a night window has no per-region rollup and
 *        must never be passed one (map-tab-v2-plan.md O-16)
 * @param {Array<string>} args.regionsInScope region names currently in scope, deduplicated here
 * @returns {?{tier: string, regionName: ?string, sharingCount: number, allInScope: boolean,
 *          scopedRegionCount: number}}
 */
export function windowVerdict({ index, date, targetType, regionsInScope }) {
  if (!index || !date || !targetType) return null;
  const names = [...new Set((Array.isArray(regionsInScope) ? regionsInScope : []).filter(Boolean))];
  if (names.length === 0) return null;

  const records = [];
  for (const name of names) {
    const record = index.get(keyOf(date, targetType, name));
    if (record) records.push(record);
  }

  const top = pickTopEligibleRegion(records);
  if (!top) return null;

  // Through the app's shared helper, not `top.displayVerdict` raw: it prefers the served field and
  // falls back to mapping a legacy payload's triage `verdict` (GO/MARGINAL/STANDDOWN) rather than
  // reading it as AWAITING. ⚠️ `windowFirstCards` and `windowFirstRegions` both read the raw field
  // today, so on such a payload this surface and the region rows beneath it would disagree — noted
  // rather than silently diverged from, and the convergence belongs to the phase that renders both
  // (map-landing-plan.md §3 L5).
  const tier = resolveRegionDisplay(top);
  let sharingCount = 0;
  for (const record of records) {
    if (record !== top && resolveRegionDisplay(record) === tier) sharingCount += 1;
  }

  return {
    tier,
    regionName: top.regionName ?? null,
    sharingCount,
    allInScope: names.length > 1 && sharingCount + 1 === names.length,
    scopedRegionCount: names.length,
  };
}

/**
 * The distinct region names in a pool of heat spots — the scope-limited region set
 * {@link windowVerdict} takes.
 *
 * <p>Fed from {@code MapView}'s own {@code scopeBasePool} ("My area" or "Everywhere", before every
 * OTHER filter), so the tally counts <b>exactly</b> the population the counts footer already reports
 * as "of K". That is what makes the design's first check meaningful: minimum rating, reach, subject
 * and dark-sky change what the map draws and never reach this list, while the scope segment does.
 *
 * <p>{@code regionName} rather than {@code rid} — {@code heatSpots.js} sets the two from one value
 * and they are the same string, but the named one says what it is. Non-sky subjects are <b>kept</b>:
 * {@code buildHeatSpots} withholds a waterfall's scores from the field and keeps the spot, so a
 * region reachable only for waterfalls is still a region in your area, and the backend's own
 * ranking can name it.
 *
 * @param {Array<{regionName: ?string}>} spots
 * @returns {string[]} distinct region names, in first-seen order
 */
export function regionNamesOf(spots) {
  const names = [];
  const seen = new Set();
  for (const spot of Array.isArray(spots) ? spots : []) {
    const name = spot?.regionName;
    if (!name || seen.has(name)) continue;
    seen.add(name);
    names.push(name);
  }
  return names;
}

/**
 * Every SOLAR EV row's verdict, keyed by row id — the map's whole verdict channel, in one pure
 * function.
 *
 * <p><b>Extracted from `MapView` rather than left inline</b>, on the doors series' own correction:
 * D2 shipped a no-caller-yet code path, review charged that its logic was untestable through any
 * UI that did not exist, and the fix was to lift the logic into a pure module and leave a thin
 * wrapper behind. This is that shape — the guards (overlay, missing index, night rows), the
 * suppression of a null verdict and the {@code row.id} keying are all here, where a test can reach
 * them, and the component keeps one call.
 *
 * <p><b>Night rows are skipped by KIND, not by target type.</b> Astro and aurora carry no
 * per-region rollup at all (map-tab-v2-plan.md O-16), and §6 Q1 decided their verdict cell renders
 * empty. Keying off {@code row.kind} rather than off a `windowVerdict` miss is what makes that a
 * stated rule instead of a coincidence of the index happening to hold no night keys.
 *
 * @param {object} args
 * @param {Array<{id: string, kind: string, date: string, eventType: string}>} args.events the EV list
 * @param {?Map<string, object>} args.index from {@link buildRegionVerdictIndex}
 * @param {Array<string>} args.regionsInScope region names currently in scope
 * @param {boolean} [args.overlayMode] true on the frozen Plan-tab overlay, which draws no verdict
 * @returns {Map<string, object>} row id to that window's verdict, omitting rows that have none
 */
export function buildEvVerdicts({ events, index, regionsInScope, overlayMode = false }) {
  const out = new Map();
  if (overlayMode || !index) return out;
  for (const row of Array.isArray(events) ? events : []) {
    if (row?.kind !== SOLAR_KIND) continue;
    const verdict = windowVerdict({
      index, date: row.date, targetType: row.eventType, regionsInScope,
    });
    if (verdict) out.set(row.id, verdict);
  }
  return out;
}
