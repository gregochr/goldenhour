/**
 * Your places (find-and-favourites-plan.md F4): the pure helpers every list that shows a star shares.
 *
 * <p><b>The honesty rule.</b> A favourite never changes a rating, a verdict or a Best pick, and nothing here
 * derives one. The only reordering a favourite may cause is {@link favouriteFirst}: a stable tie-break inside one
 * tier of a Find group, over rows that ALREADY matched the query, below every exact match. Everything else is a
 * separate, labelled section (Find's resting group, the pickers' {@code My places}, the Coming up watchlist) or a
 * mark.
 *
 * <p>Membership of a bookmarked area is never walked here: the server serves the merged {@code locationIds}.
 */

/** The label every surface prints for the reader's own set, one spelling. */
export const YOUR_PLACES = 'Your places';
export const MY_PLACES = 'My places';

/** What a failed star or un-star says when the server gave no sentence of its own. */
export const FAVOURITE_FAILED = "Couldn't update your places. Try again.";

/** An empty, frozen set so an unbookmarked reader costs no allocation per render. */
const NONE = Object.freeze(new Set());

/** The empty sets, for a reader with nothing bookmarked or no favourites at all. */
export const NO_FAVOURITES = Object.freeze({
  placeIds: NONE, areaIds: NONE, locationIds: NONE, dateKeys: NONE, dates: Object.freeze([]),
});

/**
 * The reader's favourites as the sets the lists read. {@code dates} are the watched almanac dates (one-screen-plan.md
 * D16), kept as served ({@code {key, createdAt}}, oldest watch first) beside their key set; they are not places, so
 * nothing that reads {@code locationIds} sees them.
 *
 * @param {?{places?: Array<{locationId: number}>, areas?: Array<{areaId: number}>, locationIds?: number[],
 *        dates?: Array<{key: string}>}} body the served body
 * @returns {{placeIds: Set<number>, areaIds: Set<number>, locationIds: Set<number>, dateKeys: Set<string>,
 *          dates: Array<{key: string}>}}
 */
export function favouriteSets(body) {
  const dates = body?.dates ?? [];
  return {
    placeIds: new Set((body?.places ?? []).map((p) => p.locationId)),
    areaIds: new Set((body?.areas ?? []).map((a) => a.areaId)),
    locationIds: new Set(body?.locationIds ?? []),
    dateKeys: new Set(dates.map((d) => d.key)),
    dates,
  };
}

/**
 * One star or un-star applied to a served body, ahead of the server's answer (the optimistic step). Only the two
 * lists the toggle itself reads move; {@code locationIds} is left for the server to re-expand, because a bookmarked
 * area's members are the server's to name.
 *
 * @param {object} body the current body
 * @param {'place'|'area'|'date'} kind
 * @param {number|string} id the location or area id, or a watched date's key
 * @param {boolean} on
 * @returns {object} a new body
 */
export function applyStar(body, kind, id, on) {
  const key = { area: 'areas', date: 'dates' }[kind] ?? 'places';
  const idKey = { area: 'areaId', date: 'key' }[kind] ?? 'locationId';
  const list = (body?.[key] ?? []).filter((row) => row[idKey] !== id);
  return { ...body, [key]: on ? [...list, { [idKey]: id, createdAt: null }] : list };
}

/**
 * Stable partition: the favourites first, every other row after, each half in its own order.
 *
 * <p>Used only inside one tier of an already matched Find group, so a bookmarked row never outranks a better match and
 * a row that did not match is never here at all (the matched list is what is passed in).
 *
 * @template T
 * @param {T[]} items rows that already matched
 * @param {?function(T): boolean} isFavourite whether a row is one of the reader's; null leaves the order alone
 * @returns {T[]}
 */
export function favouriteFirst(items, isFavourite) {
  if (!isFavourite) return items;
  const bookmarked = [];
  const rest = [];
  for (const item of items) (isFavourite(item) ? bookmarked : rest).push(item);
  return bookmarked.length === 0 ? items : [...bookmarked, ...rest];
}

/**
 * The region pickers' extra entries (find-and-favourites-plan.md D4): {@code My places} (the served merged set)
 * and one per bookmarked area, directly under it. Sections, not rank positions. {@code mark} says the renderer draws the
 * bookmark mark before the name; the name itself carries no glyph.
 *
 * @param {{locationIds: Set<number>}} sets the reader's sets
 * @param {Array<{id: number, name: string, locationIds: number[]}>} bookmarkedAreas the directory entries of the
 *        bookmarked areas
 * @returns {Array<{key: string, kind: 'places'|'area', mark: boolean, name: string, ids: number[], count: number}>}
 *          empty when nothing is bookmarked
 */
export function pickerEntries(sets, bookmarkedAreas) {
  const ids = [...(sets?.locationIds ?? [])];
  const areas = bookmarkedAreas ?? [];
  if (ids.length === 0 && areas.length === 0) return [];
  const entries = [{
    key: 'places', kind: 'places', mark: true, name: MY_PLACES, ids, count: ids.length,
  }];
  for (const area of areas) {
    const members = area.locationIds ?? [];
    entries.push({
      key: `area:${area.id}`, kind: 'area', mark: true, name: area.name, ids: members, count: members.length,
    });
  }
  return entries;
}

/**
 * The words the toast says after a change.
 *
 * @param {'place'|'area'|'date'} kind
 * @param {string} name
 * @param {boolean} on
 * @param {?{dow: string}} [home] a watched date only: the day it becomes a lane on in This week, when it does (the
 *        strip's own answer, so the toast names where the date really went)
 * @returns {string}
 */
export function starToastText(kind, name, on, home = null) {
  if (kind === 'date') {
    if (!on) return `Stopped watching ${name}.`;
    return home ? `Watching ${name}. It's on ${home.dow} in This week.` : `Watching ${name}. It's at the top of Further ahead.`;
  }
  const what = kind === 'area' ? `${name} (area)` : name;
  return on ? `Bookmarked ${what}.` : `Removed the bookmark from ${what}.`;
}
