import { useCallback, useEffect, useMemo, useState } from 'react';
import { fetchLocations } from '../api/forecastApi.js';
import { fetchRegions } from '../api/regionApi.js';
import { fetchAreas, fetchLocationAliases } from '../api/areaApi.js';

/**
 * Everything the Locations tab reads: the locations, the regions, the areas (a location's sub-region is the
 * SUB_REGION area listing it) and the other names per location. Each is read once at mount; the refreshers re-read
 * after a write.
 *
 * <p>The areas and the other names are best-effort: a failure leaves the sub-region select out, and leaves the
 * "Also known as" field disabled rather than offering to overwrite names it never saw ({@code aliasesFailed}). The
 * locations and regions are the required read ({@code loadError}).
 *
 * @returns {{locations: Array<object>, setLocations: Function, regions: Array<object>, loading: boolean,
 *   loadError: boolean, areas: Array<object>|null, areasVersion: number, subRegions: Array<object>,
 *   aliasesByLocation: Object|null, setAliasesByLocation: Function, aliasesFailed: boolean,
 *   refreshLocations: () => Promise<void>, refreshAreas: (bump?: boolean) => Promise<void>}}
 */
export default function useLocationsData() {
  const [locations, setLocations] = useState([]);
  const [regions, setRegions] = useState([]);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState(false);
  // Every area and sub-region (null until read), written back through the location's own save (subRegionId).
  const [areas, setAreas] = useState(null);
  const [areasVersion, setAreasVersion] = useState(0);
  // Other names per location id; null until loaded, and stays null if the load fails.
  const [aliasesByLocation, setAliasesByLocation] = useState(null);
  const [aliasesFailed, setAliasesFailed] = useState(false);

  const subRegions = useMemo(() => (areas ?? []).filter((a) => a.kind === 'SUB_REGION'), [areas]);

  const refreshLocations = useCallback(async () => {
    try {
      setLocations(await fetchLocations());
    } catch {
      // Keep existing list on failure
    }
  }, []);

  /** Reads the areas again after a location was saved, and tells the Areas section to do the same. */
  const refreshAreas = useCallback(async (bump = true) => {
    if (bump) setAreasVersion((v) => v + 1);
    try {
      setAreas(await fetchAreas());
    } catch {
      // Keep what was read before
    }
  }, []);

  useEffect(() => {
    fetchAreas()
      .then((data) => setAreas(data))
      .catch(() => { /* no sub-region select; the Areas section reports its own load failure */ });
    Promise.all([fetchLocations(), fetchRegions()])
      .then(([locs, regs]) => { setLocations(locs); setRegions(regs); })
      .catch(() => setLoadError(true))
      .finally(() => setLoading(false));
    fetchLocationAliases()
      .then((aliases) => setAliasesByLocation(aliases ?? {}))
      .catch(() => { setAliasesFailed(true); });
  }, []);

  return {
    locations, setLocations, regions, loading, loadError, areas, areasVersion, subRegions,
    aliasesByLocation, setAliasesByLocation, aliasesFailed, refreshLocations, refreshAreas,
  };
}
