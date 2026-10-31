import { useCallback, useEffect, useMemo, useState } from 'react';
import { DISPLAY_TYPES } from '../utils/locationTypes.js';
import {
  ALL_REGIONS, STATUS_FILTERS, inScope, passesStatus, passesType, regionFromHash,
} from '../utils/locationAdmin.js';

/** Where a consumed {@code ?region=} link leaves the address bar: the bare tab. */
const BARE_HASH = '#manage/locations';

/** Drops the {@code ?region=} query once it has been applied, so a reload does not reapply a stale filter. */
function stripRegionQuery() {
  window.history.replaceState(window.history.state, '', BARE_HASH);
}

/**
 * The Locations list's filters: the search (name or sub-region), the type chip, the status chip (needs attention or
 * disabled) and the region select, which a Regions "Show them" link also sets ({@code #manage/locations?region={id}},
 * read at mount and whenever the hash moves while the tab is open).
 *
 * <p>The chip counts describe what pressing the chip would show: each counts the places that pass the search, the
 * region and the OTHER chip group's current choice.
 *
 * @param {Array<object>} locations all locations
 * @param {Array<object>} regions all regions (a region id the link names that is not among them is ignored)
 * @returns {object} the filter state, its setters, and the derived {@code filtered} set, counts and {@code filterKey}
 */
export default function useLocationFilters(locations, regions) {
  const [query, setQuery] = useState('');
  const [type, setType] = useState('');
  const [status, setStatus] = useState(STATUS_FILTERS.NONE);
  const [requestedRegion, setRegion] = useState(() => regionFromHash(window.location.hash) ?? ALL_REGIONS);

  useEffect(() => {
    if (regionFromHash(window.location.hash) != null) stripRegionQuery();
    const onHashChange = () => {
      const id = regionFromHash(window.location.hash);
      if (id == null) return;
      setRegion(id);
      stripRegionQuery();
    };
    window.addEventListener('hashchange', onHashChange);
    return () => window.removeEventListener('hashchange', onHashChange);
  }, []);

  const region = requestedRegion !== ALL_REGIONS && regions.some((r) => String(r.id) === requestedRegion)
    ? requestedRegion
    : ALL_REGIONS;
  const needle = query.trim().toLowerCase();

  const scoped = useMemo(() => locations.filter((loc) => inScope(loc, needle, region)), [locations, needle, region]);
  const filtered = useMemo(
    () => scoped.filter((loc) => passesType(loc, type) && passesStatus(loc, status)),
    [scoped, type, status],
  );

  const typeCounts = useMemo(() => {
    const counts = {};
    ['', ...DISPLAY_TYPES].forEach((t) => {
      counts[t] = scoped.filter((loc) => passesStatus(loc, status) && passesType(loc, t)).length;
    });
    return counts;
  }, [scoped, status]);

  const statusCounts = useMemo(() => {
    const counts = {};
    [STATUS_FILTERS.ATTENTION, STATUS_FILTERS.DISABLED].forEach((s) => {
      counts[s] = scoped.filter((loc) => passesType(loc, type) && passesStatus(loc, s)).length;
    });
    return counts;
  }, [scoped, type]);

  const anyFilter = needle !== '' || type !== '' || status !== STATUS_FILTERS.NONE || region !== ALL_REGIONS;

  const clear = useCallback(() => {
    setQuery('');
    setType('');
    setStatus(STATUS_FILTERS.NONE);
    setRegion(ALL_REGIONS);
  }, []);

  /** The alert's "Show them": the Needs attention chip over every place (the alert counts them all). */
  const showAttention = useCallback(() => {
    setQuery('');
    setType('');
    setRegion(ALL_REGIONS);
    setStatus(STATUS_FILTERS.ATTENTION);
  }, []);

  /** A status chip is a toggle: pressing the one that is on turns it off. */
  const toggleStatus = useCallback((next) => {
    setStatus((current) => (current === next ? STATUS_FILTERS.NONE : next));
  }, []);

  return {
    query, setQuery, type, setType, status, toggleStatus, region, setRegion, needle,
    scoped, filtered, typeCounts, statusCounts, anyFilter, clear, showAttention,
    filterKey: JSON.stringify([needle, type, status, region]),
  };
}
