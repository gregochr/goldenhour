/**
 * The planning area — "everywhere you could reasonably go for one of these windows" (plan D6).
 *
 * <p>One module, two surfaces — P2's strip framing and P4's opening bounds will read the same
 * answer, so the two cannot disagree about where your area is. Neither exists yet; this is the
 * module they are specified against.
 *
 * <p><b>It is per-user data and must stay client-side.</b> Drive times come from
 * {@code GET /api/user/settings/reach}, which is deliberately never ETag-revalidated and never
 * joined onto the shared briefing payload (`plan-panel-data-contracts.md`; a revalidated response
 * persists its body to a browser HTTP cache JavaScript cannot evict on logout). Nothing here ever
 * travels the other way either.
 *
 * <p><b>What the area may and may not do to the field.</b> Plan §3's rule is that the reach
 * <em>lens</em> (the 45/90/150 control) does not filter the field — a spot's score is never
 * weighted, ranked or withheld because of how far away it is. The planning area is a different
 * thing and is allowed to choose <em>which regions are framed</em>: that is what D6 and the
 * bundle both specify it for. So {@link areaSpots} is for fitting bounds and for framing copy,
 * and handing its output to the kernel as the field's point set would quietly turn the framing
 * into the filter §3 forbids.
 *
 * <p><b>Memoise the callers.</b> §5.4 records that the prototype's unmemoised equivalent of
 * {@link areaRegions} (its {@code areaRids}) cost seconds per repaint — this module is the one
 * that measurement was about.
 */

/**
 * The glance threshold in minutes, from the bundle (`plan-data.js:190`, README §"Planning area").
 *
 * <p>The recorded reason is about <b>coverage scale, not about a drive anyone would choose</b>:
 * "show everything" and "show everything you could plausibly go to" are the same sentence at
 * northern-England scale and stop being the same once Scotland is in the catalogue. At 180 the
 * Borders (1h52 to Kelso) and the Peak (2h26 to Ladybower) clear it and Skye does not — so
 * adding Skye cannot flatten a thumbnail to three green pixels. You reach it by moving the
 * origin (P7), which is what an away region is for.
 *
 * <p>Hard-coded for the first cut; §9.5 carries the user setting, whose proposed default is the
 * widest drive time the user has actually configured rather than this constant. The bundle's
 * rule has a second clause this cannot implement yet — "or it is a configured home region" —
 * because regions have no home flag until P7 (§2.8).
 */
export const GLANCE_MINUTES = 180;

/**
 * The shortest measured drive to each region, keyed by region name.
 *
 * <p>Only <em>measured</em> drives count. A location with no reach entry, or one whose entry
 * carries a null {@code driveMinutes}, contributes nothing — and a region with no measured
 * location at all is absent from this map entirely, which is what lets the callers below tell
 * "far" apart from "not known".
 *
 * @param {Array<{id: *, regionName: string}>} spots      heat spots (or any {id, regionName})
 * @param {Map<*, {driveMinutes: ?number}>|null} reachById per-user reach, keyed by location id
 * @returns {Map<string, number>} region name → shortest measured drive in minutes
 */
export function regionDriveMinutes(spots, reachById) {
  const minutes = new Map();
  if (!reachById || typeof reachById.get !== 'function') return minutes;
  for (const spot of Array.isArray(spots) ? spots : []) {
    if (!spot || !spot.regionName || spot.id == null) continue;
    const drive = reachById.get(spot.id)?.driveMinutes;
    if (typeof drive !== 'number' || !Number.isFinite(drive)) continue;
    const current = minutes.get(spot.regionName);
    if (current === undefined || drive < current) minutes.set(spot.regionName, drive);
  }
  return minutes;
}

/** Every region present in the spot list, in first-appearance order. */
function allRegions(spots) {
  const names = [];
  const seen = new Set();
  for (const spot of Array.isArray(spots) ? spots : []) {
    if (!spot || !spot.regionName || seen.has(spot.regionName)) continue;
    seen.add(spot.regionName);
    names.push(spot.regionName);
  }
  return names;
}

/** Nearest measured first; unmeasured last; name as the tiebreak, so the order is total. */
function byDriveThenName(minutes) {
  return (a, b) => {
    const da = minutes.has(a) ? minutes.get(a) : Infinity;
    const db = minutes.has(b) ? minutes.get(b) : Infinity;
    return da === db ? a.localeCompare(b) : da - db;
  };
}

/**
 * The regions inside the planning area: those whose nearest location is a {@link GLANCE_MINUTES}
 * drive or less, <b>plus every region whose drive is not known</b>.
 *
 * <p>That second clause is the whole degrade rule, and it is deliberately not a special case.
 * A user with no home postcode has no reach entries, so nothing is measured, so every region
 * qualifies and the planning area is the whole roster — which is D6's requirement ("never
 * synthesise a smaller area") falling out of the general rule rather than being bolted on beside
 * it. A user with a partial matrix gets the same treatment per region: an unmeasured region is
 * not evidence of distance, and shrinking the area on missing data would quietly hide places
 * from someone who can reach them.
 *
 * @param {Array<object>} spots                              heat spots
 * @param {Map<*, {driveMinutes: ?number}>|null} reachById    per-user reach, keyed by location id
 * @returns {string[]} region names, nearest measured first
 */
export function areaRegions(spots, reachById) {
  const minutes = regionDriveMinutes(spots, reachById);
  return allRegions(spots)
    .filter((name) => !minutes.has(name) || minutes.get(name) <= GLANCE_MINUTES)
    .sort(byDriveThenName(minutes));
}

/**
 * The regions outside it — and <b>only the ones measured to be outside it</b>.
 *
 * <p>The beyond line names these regions on screen, so an unmeasured region must never appear
 * here: "beyond your planning area" would be a claim about a drive nobody has computed. That is
 * the same rule {@link areaRegions} states from the other side, so the two lists ARE exact
 * complements over {@code spots}' regions — every region is in one of them, none in both,
 * partial matrix or not. Do not build a third "unknown" bucket out of the difference; it is
 * always empty. What is asymmetric is where the doubt goes, not the arithmetic: an unmeasured
 * region lands in the area, where it widens the framing and names nothing.
 *
 * @param {Array<object>} spots                              heat spots
 * @param {Map<*, {driveMinutes: ?number}>|null} reachById    per-user reach, keyed by location id
 * @returns {string[]} region names, nearest first
 */
export function beyondRegions(spots, reachById) {
  const minutes = regionDriveMinutes(spots, reachById);
  return allRegions(spots)
    .filter((name) => minutes.has(name) && minutes.get(name) > GLANCE_MINUTES)
    .sort(byDriveThenName(minutes));
}

/**
 * The spots inside the planning area — the Map tab's opening bounds and the strip's framing.
 *
 * <p>Region-grained, not location-grained: the area is a set of places you would plan a trip
 * around, and half a region is not one. A region is in or out as a unit (see {@link areaRegions}).
 *
 * @param {Array<object>} spots                              heat spots
 * @param {Map<*, {driveMinutes: ?number}>|null} reachById    per-user reach, keyed by location id
 * @returns {Array<object>} the subset of {@code spots} in the area, in input order
 */
export function areaSpots(spots, reachById) {
  const inArea = new Set(areaRegions(spots, reachById));
  return (Array.isArray(spots) ? spots : []).filter((s) => s && inArea.has(s.regionName));
}
