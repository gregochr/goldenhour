/**
 * View-model helpers for the Regions tab (data-tabs-redesign-plan.md §5.4). Pure functions over the three payloads
 * the tab loads: regions, locations and areas. Nothing here is a served verdict; it counts rows for a list.
 */
import { formatDataDate, formatPlainDate } from './dataDates.js';

/** The sort accessors the list's header and sort chip use. */
export const REGION_SORT_ACCESSORS = {
  name: (r) => r.name,
  subRegions: (r) => r.subRegionCount,
  locations: (r) => r.locationCount,
  created: (r) => r.createdAt ?? null,
};

/**
 * Each region with its counts: the sub-regions that partition it, every location in it, and how many of those
 * are switched on (the number that decides whether it can be turned off).
 *
 * @param {Array<object>} regions GET /api/regions
 * @param {Array<object>} locations GET /api/locations (an empty array when it failed to load)
 * @param {Array<object>} areas GET /api/areas (an empty array when it failed to load)
 * @returns {Array<object>} the regions, each with {@code subRegions} (id, name and location count, by name),
 *   {@code subRegionCount}, {@code locationCount} and {@code enabledLocationCount}
 */
export function buildRegionRows(regions, locations, areas) {
  return regions.map((region) => {
    const inRegion = locations.filter((loc) => loc.region?.id === region.id);
    const subRegions = areas
      .filter((a) => a.kind === 'SUB_REGION' && a.regionId === region.id)
      .map((a) => ({ id: a.id, name: a.name, locationCount: (a.locationIds ?? []).length }))
      .sort((a, b) => a.name.localeCompare(b.name, undefined, { sensitivity: 'base' }));
    return {
      ...region,
      subRegions,
      subRegionCount: subRegions.length,
      locationCount: inRegion.length,
      enabledLocationCount: inRegion.filter((loc) => loc.enabled).length,
    };
  });
}

/** "1 location" / "3 locations". */
export function locationsText(count) {
  return `${count} ${count === 1 ? 'location' : 'locations'}`;
}

/** "1 sub-region" / "3 sub-regions". */
export function subRegionsText(count) {
  return `${count} ${count === 1 ? 'sub-region' : 'sub-regions'}`;
}

/** The second card line: "3 sub-regions · 14 locations". */
export function regionSummary(row) {
  return `${subRegionsText(row.subRegionCount)} · ${locationsText(row.locationCount)}`;
}

/** The panel's meta line: "On · created 1 Mar 2026". */
export function regionMeta(row) {
  return `${row.enabled ? 'On' : 'Off'} · created ${formatPlainDate(row.createdAt)}`;
}

/** The accessible name of a row: everything the row shows. */
export function regionRowLabel(row) {
  return `${row.name}, ${row.enabled ? 'enabled' : 'disabled'}, ${regionSummary(row)}, `
    + `created ${formatDataDate(row.createdAt)}`;
}
