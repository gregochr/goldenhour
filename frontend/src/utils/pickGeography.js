/**
 * The shape of a Lite reader's picks across the map's regions (sub-regions-plan S5a-2): which regions
 * hold a pick, how many sub-regions of each, and the places of a region that lie outside the picks.
 *
 * <p>Every figure is a count over two <b>served</b> facts and nothing else: the briefing's
 * {@code allowedLocationIds} (the list the server decided) and each place's served
 * {@code subRegionId}/{@code subRegionName} ({@code LocationDto}). A place is allowed because that list
 * names it, never because of a rule the client holds, and the role is never read. The module is read only
 * for a shaped body; an unshaped reader gets {@code null} from it, so nothing downstream can draw.
 */

/**
 * Case-insensitive code-unit order, the one every shaped list on the Map sorts names by (the server's
 * lead pick uses the same one).
 *
 * @param {*} a a name
 * @param {*} b a name
 * @returns {number}
 */
export function compareNames(a, b) {
  const x = String(a ?? '').toLowerCase();
  const y = String(b ?? '').toLowerCase();
  if (x < y) return -1;
  return x > y ? 1 : 0;
}

/**
 * Per-region facts about the picks, for a shaped body.
 *
 * @param {Array<{id: *, name: string, regionName: string, subRegionId: ?*, subRegionName: ?string}>} spots
 *        the roster's heat spots (every enabled place with a region and coordinates)
 * @param {?Array<*>} allowedIds the briefing's served {@code allowedLocationIds}; not an array = unshaped
 * @returns {?Map<string, {places: number, allowedPlaces: number, outsidePlaces: Array<object>,
 *   subRegions: Array<{key: string, name: string, places: number, picked: boolean}>, pickedNames:
 *   Array<string>}>} region name to its facts; {@code null} for an unshaped body
 */
export function regionPickSummaries(spots, allowedIds) {
  if (!Array.isArray(allowedIds)) return null;
  const allowed = new Set(allowedIds);
  const regions = new Map();
  for (const spot of Array.isArray(spots) ? spots : []) {
    const regionName = spot?.regionName;
    if (!regionName) continue;
    let region = regions.get(regionName);
    if (!region) {
      region = {
        places: 0, allowedPlaces: 0, skyPlaces: 0, skyAllowed: 0, outsidePlaces: [], bySub: new Map(),
      };
      regions.set(regionName, region);
    }
    const isAllowed = spot.id != null && allowed.has(spot.id);
    region.places += 1;
    if (spot.skySubject !== false) {
      region.skyPlaces += 1;
      if (isAllowed) region.skyAllowed += 1;
    }
    if (isAllowed) {
      region.allowedPlaces += 1;
    } else {
      region.outsidePlaces.push({
        id: spot.id,
        name: spot.name,
        subRegionId: spot.subRegionId ?? null,
        subRegionName: spot.subRegionName ?? null,
      });
    }
    if (spot.subRegionName) {
      const key = String(spot.subRegionId ?? spot.subRegionName);
      const sub = region.bySub.get(key) ?? {
        key, name: spot.subRegionName, places: 0, picked: false,
      };
      sub.places += 1;
      if (isAllowed) sub.picked = true;
      region.bySub.set(key, sub);
    }
  }
  const out = new Map();
  for (const [name, region] of regions) {
    const subRegions = [...region.bySub.values()].sort((a, b) => compareNames(a.name, b.name));
    out.set(name, {
      places: region.places,
      allowedPlaces: region.allowedPlaces,
      // Outside places among the sky subjects only: the group a picked row's "k of m" counts over.
      skyOutside: region.skyPlaces - region.skyAllowed,
      outsidePlaces: region.outsidePlaces.sort((a, b) => compareNames(a.name, b.name)),
      subRegions,
      pickedNames: subRegions.filter((s) => s.picked).map((s) => s.name),
    });
  }
  return out;
}

/**
 * The third line of a region label (zoomed out), singular where it is one.
 *
 * @param {number} picked the sub-regions of that region the reader holds
 * @returns {string}
 */
export function pickedLineText(picked) {
  if (!(picked > 0)) return 'outside your sub-regions';
  return `${picked} ${picked === 1 ? 'sub-region' : 'sub-regions'} picked`;
}

/**
 * A count with its noun, singular at one.
 *
 * @param {number} n the count
 * @param {string} singular the noun at one
 * @param {string} plural the noun otherwise
 * @returns {string}
 */
export function countWord(n, singular, plural) {
  return `${n} ${n === 1 ? singular : plural}`;
}

/**
 * The legend's note for a shaped body: what the colour covers, or, for a reader with no pick yet, where to
 * choose. Shared by the desktop legend panel and the phone's Layers section, so the two cannot drift.
 *
 * @param {{allowed: number, total: number}} picks the places the picks cover, and the roster
 * @returns {string}
 */
export function picksNoteText({ allowed, total }) {
  return allowed > 0
    ? `Colour shows the ${countWord(allowed, 'place', 'places')} in your sub-regions. Pro rates all ${total}.`
    : 'Choose your sub-regions in Settings to see ratings.';
}
