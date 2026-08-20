import React, { useEffect, useMemo, useState } from 'react';
import PropTypes from 'prop-types';
import { buildLocationTypeMap } from '../utils/locationTypes.js';
import HeatmapGrid from './HeatmapGrid.jsx';
import useLocalStorageState from '../hooks/useLocalStorageState.js';
import { getAstroConditions } from '../api/astroApi.js';
import { useWindowFirstBriefing } from '../context/WindowFirstBriefingContext.jsx';
import { sortRegionsByBestVerdict } from '../utils/windowFirstRegions.js';

/**
 * The heatmap's quality filter, pinned open.
 *
 * <p>Matches {@code DailyBriefing}'s own {@code SHOW_ALL_TIER}: the quality slider was retired in v1
 * and the tier is a pinned seam rather than a live control. Re-deriving a different value here would
 * make the same grid show a different number of rows in the two arms, which is precisely the
 * comparison plan §4 is trying to run.
 */
const SHOW_ALL_TIER = 5;

/**
 * What sits behind the regional-planner door: the full heatmap, with its own drill-down.
 *
 * <h2>Why the grid is re-parented rather than rebuilt</h2>
 *
 * <p>Plan §0 settles this — the heatmap is "re-parented behind the two doors, not deleted". Unlike
 * the filmstrip and the peek, which §5a and §5 order <em>copied</em> so {@code CloseToHome} and
 * {@code CardHoverPreview} stay untouched, {@code HeatmapGrid} is already a standalone component
 * with a declared prop contract and no knowledge of its parent. Rendering it from a second call site
 * changes nothing about the first, so the v1 arm is as untouched by this as it would be by a copy —
 * and a copy of 900 lines would be two grids to keep in step for the length of the pilot.
 *
 * <h2>Its drill-down is why "regional planner" is not an overstatement</h2>
 *
 * <p>§0 names the door's contents as the heatmap <em>plus the full regional briefing</em>. The grid
 * carries its own {@code drillDown} state over (date, region, event), which is that briefing — the
 * per-region slot detail a reader would otherwise open a region card for. What is <b>not</b>
 * re-parented is {@code DailyBriefing}'s own region-card list, which is entangled with a dozen
 * pieces of that component's state; the door's description says "every region, every window" rather
 * than promising a surface it does not open.
 *
 * <h2>Reach comes from this arm's own join, not from a second drive-time fetch</h2>
 *
 * <p>The grid wants {@code driveMap} keyed by location <em>name</em>; v1 builds it from a
 * {@code userDriveTimes} object it fetches separately. This arm already holds per-user reach from
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
 * <h2>Astro is fetched here, which makes it lazy by construction</h2>
 *
 * <p>v1 fetches one request per visible date on mount. Here the panel does not exist until the door
 * is opened, so the requests are not made until something is going to draw them. The shell keeps the
 * panel mounted once opened (hidden rather than unmounted) so closing and reopening the door does
 * not fire the wave again. A date whose request fails contributes an empty map and the grid's astro
 * column is simply blank for it — the same degradation v1 has.
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

  // The same key v1 writes, deliberately: it is a display preference about the same grid, and a
  // second key would silently reset it the moment the flag default flips.
  const [showAllLocations, setShowAllLocations] = useLocalStorageState('showStanddownLocations', false);
  const [astroScoresByDate, setAstroScoresByDate] = useState({});

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

  const astroDates = useMemo(
    () => [...new Set((upcomingEvents || []).map((e) => e.date))],
    [upcomingEvents],
  );
  // Joined so the effect re-runs on a genuine change of dates rather than on every new array
  // identity — `upcomingEvents` is rebuilt whenever the briefing object is, i.e. every poll.
  const astroDateKey = astroDates.join(',');

  useEffect(() => {
    if (astroDates.length === 0) return undefined;
    let live = true;
    Promise.all(astroDates.map((date) => getAstroConditions(date)
      .then((scores) => ({ date, scores }))
      .catch(() => ({ date, scores: [] }))))
      .then((results) => {
        if (!live) return;
        const byDate = {};
        for (const { date, scores } of results) {
          const byName = {};
          for (const s of scores) byName[s.locationName] = s;
          byDate[date] = byName;
        }
        setAstroScoresByDate(byDate);
      });
    return () => { live = false; };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [astroDateKey]);

  return (
    <HeatmapGrid
      events={upcomingEvents}
      sortedRegions={sortedRegions}
      briefingDays={briefingDays}
      qualityTier={SHOW_ALL_TIER}
      driveMap={driveMap}
      typeMap={typeMap}
      todayStr={todayStr}
      tomorrowStr={tomorrowStr}
      onShowOnMap={onShowOnMap}
      evaluationScores={evaluationScores}
      isPro={isPro}
      astroScoresByDate={astroScoresByDate}
      showAllLocations={showAllLocations}
      onShowAllLocationsChange={setShowAllLocations}
      travelDayDates={travelDayDates}
      // This arm asks for the phone layout; the frozen v1 arm does not, and that asymmetry is the
      // whole of the change's blast radius. See docs/engineering/phone-heatmap-blast-radius.md.
      scrollable
      // Same shape, same reason. Each cell's star comes from `BriefingRegion.meanRating` — the same
      // statistics the backend derived that cell's verdict word from — instead of a client-side mean
      // over `/api/briefing/evaluate/scores` joined on a region-name prefix. Two fetches with two
      // cache lifetimes could put a word and a number in one cell that disagree; one payload cannot.
      // The v1 arm passes nothing and keeps its own client-side join — which is no longer the
      // derivation it shipped with: the canopy rule had to be applied to it by hand once the
      // verdict word moved, because that word is a payload field with no prop to gate it. See
      // `HeatmapCell`. `evaluationScores` still travels either way: the drill-down reads it for
      // per-location detail, canopy rows included.
      serverCellRating
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
