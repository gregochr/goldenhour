import React, { useMemo } from 'react';
import PropTypes from 'prop-types';
import { buildLocationTypeMap } from '../utils/locationTypes.js';
import HeatmapGrid from './HeatmapGrid.jsx';
import useLocalStorageState from '../hooks/useLocalStorageState.js';
import { useWindowFirstBriefing } from '../context/WindowFirstBriefingContext.jsx';
import { sortRegionsByBestVerdict } from '../utils/windowFirstRegions.js';

/**
 * What sits behind the regional-planner door: the full heatmap, with its own drill-down.
 *
 * <h2>Why the grid is re-parented rather than rebuilt</h2>
 *
 * <p>Plan §0 settles this — the heatmap is "re-parented behind the two doors, not deleted".
 * {@code HeatmapGrid} is a standalone component with a declared prop contract and no knowledge of
 * its parent, so rendering it from this door changes nothing else that renders it.
 *
 * <h2>Its drill-down is why "regional planner" is not an overstatement</h2>
 *
 * <p>§0 names the door's contents as the heatmap <em>plus the full regional briefing</em>. The grid
 * carries its own {@code drillDown} state over (date, region, event), which is that briefing — the
 * per-region slot detail a reader would otherwise open a region card for. The door's description
 * says "every region, every window" rather than promising a surface it does not open.
 *
 * <h2>Reach comes from this arm's own join, not from a second drive-time fetch</h2>
 *
 * <p>The grid wants {@code driveMap} keyed by location <em>name</em>. This arm already holds per-user reach from
 * {@code GET /api/user/settings/reach}, keyed by location id — the contract plan §2.2 created
 * precisely so drive times never ride the ETagged briefing payload. So the id→name half comes from
 * {@code locations} and the minutes from {@code effectiveReachById}, and this arm keeps <b>one</b>
 * source of truth about how far away a place is. A location the reach response did not mention
 * simply has no entry, which is the same state the grid already handles for a user with no home
 * postcode.
 *
 * <p>⚠️ <b>{@code effectiveReachById}, never {@code reachById}.</b> P7's origin move overwrites the
 * whole reach map when the reader plans from a region base, and this panel is on the same tab as
 * the window cards. Reading the raw per-user map instead put one location at {@code 2h 10m} on a
 * spot card (from Keswick) and {@code 45 min} in this grid (from the reader's house), on one
 * screen, under a lens bar reading "Drive from Keswick", with nothing distinguishing them. Any new
 * consumer that renders a drive wants this one: {@code reachById} stays published only because the
 * planning area and the beyond line are statements about HOME.
 *
 * @param {object} props
 * @param {Array}    props.locations     enabled locations, for the id→name and name→type joins
 * @param {Function} [props.onShowOnMap] the map handoff, passed straight through
 */
export default function WindowFirstRegionalPanel({ locations, onShowOnMap }) {
  const {
    briefing, upcomingEvents, travelDayDates, evaluationScores, effectiveReachById, isPro,
    todayStr, tomorrowStr,
  } = useWindowFirstBriefing();

  const [showAllLocations, setShowAllLocations] = useLocalStorageState('showStanddownLocations', false);

  // Memoised, not a bare `briefing?.days || []`: the fallback allocates a fresh array on every
  // render, which would make `sortedRegions` below re-fold on every render of a component that
  // re-renders on the provider's ten-minute poll and on every map handoff.
  const briefingDays = useMemo(() => briefing?.days || [], [briefing]);

  const sortedRegions = useMemo(
    () => sortRegionsByBestVerdict(upcomingEvents, briefingDays),
    [upcomingEvents, briefingDays],
  );

  /** Location name → drive minutes, joined from this arm's reach contract on location id. */
  const driveMap = useMemo(() => {
    const map = new Map();
    for (const loc of locations || []) {
      const reach = effectiveReachById?.get(loc.id);
      if (reach?.driveMinutes != null) map.set(loc.name, reach.driveMinutes);
    }
    return map;
  }, [locations, effectiveReachById]);

  /** Location name → location type, for the grid's type icons. Shared with the drill-down. */
  const typeMap = useMemo(() => buildLocationTypeMap(locations), [locations]);

  return (
    <HeatmapGrid
      events={upcomingEvents}
      sortedRegions={sortedRegions}
      briefingDays={briefingDays}
      driveMap={driveMap}
      typeMap={typeMap}
      todayStr={todayStr}
      tomorrowStr={tomorrowStr}
      onShowOnMap={onShowOnMap}
      evaluationScores={evaluationScores}
      isPro={isPro}
      showAllLocations={showAllLocations}
      onShowAllLocationsChange={setShowAllLocations}
      travelDayDates={travelDayDates}
    />
  );
}

WindowFirstRegionalPanel.propTypes = {
  locations: PropTypes.arrayOf(PropTypes.shape({
    id: PropTypes.oneOfType([PropTypes.number, PropTypes.string]),
    name: PropTypes.string,
    locationType: PropTypes.string,
  })),
  onShowOnMap: PropTypes.func,
};
