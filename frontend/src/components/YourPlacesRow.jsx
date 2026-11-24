import React, { useMemo } from 'react';
import PropTypes from 'prop-types';
import { useFavourites } from '../context/FavouritesContext.jsx';
import { useAreaOutlook } from '../hooks/useAreaFind.js';
import useInViewOnce from '../hooks/useInViewOnce.js';
import { areaFigure, topWindowFor } from '../utils/planSearch.js';
import { areaMapWindow } from '../utils/areaFind.js';
import { formatDriveDuration } from '../utils/briefingDisplay.js';
import { isOutsidePicks } from '../utils/pickScope.js';
import { YOUR_PLACES } from '../utils/favourites.js';
import { buildWatchedChips } from '../utils/watchedDates.js';
import { FavouriteUpsell, upsellEscape, useFavouriteUpsell } from './shared/FavouriteToggle.jsx';

/** Below this the line reads quietly: the reader's own place has nothing worth a trip in the days we have scored. */
const QUIET_BELOW = 3;
const QUIET_LINE = 'nothing above 2★ in the next days';
export const YOUR_PLACES_EMPTY = 'Star a place or an area, from its sheet or its map callout, or watch a date in Further ahead, and it shows here.';

/**
 * Your places (find-and-favourites-plan.md F4, D3, D4; one-screen-plan.md D15): the reader's own starred areas and
 * places as ONE horizontal row of compact chips at the top of This week, a SEPARATE labelled section above the
 * forecast. It scrolls sideways (with scroll-snap on a phone). It was the Coming up tab's watchlist, one row each;
 * it moved because it is forecast-based, which is what This week is.
 *
 * <p>Not a ranked list: the order is areas first, then places, each in the order the reader holds them, and a
 * favourite is never placed by its rating.
 *
 * <p>Every figure is served. A place's is its own best window (the same walk Find's place row and the area sheet make,
 * {@code topWindowFor}); an area's is the per-reader outlook ({@code GET /api/areas/outlook}) with its window and
 * {@code N rated}, never "best of the area" as a verdict, and a Lite-style gated member is counted and never rated.
 * Below 3★ a chip reads quietly ({@code nothing above 2★ in the next days}); a place or area with nothing rated says so.
 *
 * <p><b>What a tap does.</b> A chip with a worthwhile window opens the map view on that window, focused on the place
 * or area ({@code onOpenWindow}). A chip with no window to open (quiet, outside the reader's picks, not rated yet)
 * opens the place's or area's own sheet instead, so a control never opens nothing.
 *
 * <p><b>Fetching.</b> {@code useAreaOutlook} fetches only once the row has been on screen, so a reader who never
 * scrolls to a starred area never pays for it.
 *
 * <p><b>Watched dates (one-screen-plan.md D16)</b> join the row as chips of their own, earliest date first, before the
 * places. A date still beyond the forecast reads {@code ★ 🌊 King tide · 3 Dec} with {@code in N days}, and a tap scrolls
 * to its entry in Further ahead and opens it ({@code onOpenEntry}). Once its dates reach the forecast the chip becomes
 * that morning's plan, from served fields only ({@code utils/watchedDates.js}): the window, the best spot and its stars,
 * and for a tide {@code tide on the light}; a tap opens the map view on that window. A date with no window of its own (a
 * meteor peak) is a plain chip that says {@code clear-sky check on the night} and opens nothing. A date that has passed
 * draws nothing (the row is kept, not shown).
 *
 * <p>A Lite reader gets one line, {@code Your places · PRO}, opening the worded upsell. Anyone else (no provider, the
 * list not yet landed) gets nothing.
 *
 * @param {object} props
 * @param {Array} props.spots the heat spots
 * @param {Array} props.windows the strip's descriptors
 * @param {?object} props.scoreIndex from {@code buildScoreIndex}
 * @param {?Map} props.reachById the reach map the page plans from
 * @param {*} props.allowedLocationIds the briefing's served allowed list, on a shaped body only
 * @param {?Array} [props.entries] the served almanac entries ({@code comingUp.events.entries}), which name a watched date
 * @param {?Array} [props.hotTopics] the served {@code briefing.hotTopics}, for a watched topic's own window
 * @param {string} [props.todayStr] the reader's UK today (from {@code appNow}, Rewind)
 * @param {function(object): void} props.onOpenWindow opens the map view; given
 *        {@code {window: {date, eventType}, focus, from}}
 * @param {function(object): void} props.onOpenPlace opens a place's next-days sheet, given the heat spot
 * @param {function(object): void} props.onOpenArea opens an area's sheet, given the directory entry
 * @param {function(string): void} [props.onOpenEntry] scrolls to and opens a Further ahead entry, given its id
 */
export default function YourPlacesRow({
  spots, windows, scoreIndex, reachById, allowedLocationIds, onOpenWindow, onOpenPlace, onOpenArea,
  entries = null, hotTopics = null, todayStr = '', onOpenEntry = undefined,
}) {
  const favourites = useFavourites();
  const upsell = useFavouriteUpsell();
  const { setTrigger } = upsell;
  const [viewRef, inView] = useInViewOnce();
  const ready = favourites.status === 'ready';
  // Empty only when the SERVED list holds nothing: places and areas both zero, whatever has or has not resolved yet.
  const nothingStarred = favourites.placeIds.size === 0 && favourites.areaIds.size === 0
    && favourites.dateKeys.size === 0;
  const outlook = useAreaOutlook(inView && ready && favourites.areaIds.size > 0);

  const rows = useMemo(() => {
    if (!ready) return [];
    const byId = new Map((spots || []).map((spot) => [spot.id, spot]));
    const areaRows = favourites.starredAreas.map((area) => {
      const figure = areaFigure(outlook?.get(area.id), windows);
      const best = figure?.topLocationId == null ? null : byId.get(figure.topLocationId);
      return { kind: 'area', key: `area:${area.id}`, area, name: area.name, figure, bestName: best?.name ?? null };
    });
    const placeRows = [...favourites.placeIds].map((id) => byId.get(id)).filter(Boolean).map((spot) => {
      const gated = isOutsidePicks(allowedLocationIds, spot.id);
      const top = gated ? null : topWindowFor(spot, windows, scoreIndex);
      return {
        kind: 'place', key: `place:${spot.id}`, spot, name: spot.name, gated, top,
        drive: reachById?.get(spot.id)?.driveMinutes ?? null,
      };
    });
    const dateRows = buildWatchedChips({ dates: favourites.dates, entries, windows: windows || [], hotTopics, todayStr })
      .map((chip) => ({ kind: 'date', key: `date:${chip.key}`, name: chip.name, chip }));
    return [...dateRows, ...areaRows, ...placeRows];
  }, [
    ready, favourites.starredAreas, favourites.placeIds, favourites.dates, spots, windows, scoreIndex, reachById,
    allowedLocationIds, outlook, entries, hotTopics, todayStr,
  ]);

  if (favourites.status === 'locked') {
    return (
      <div className="wf-fav-section wf-yp" data-testid="your-places-teaser">
        <button
          type="button"
          ref={setTrigger}
          className="wf-fav wf-fav-locked"
          data-testid="your-places-teaser-button"
          aria-expanded={upsell.open}
          aria-controls={upsell.open ? upsell.id : undefined}
          onClick={upsell.toggle}
          onKeyDown={upsellEscape(upsell)}
        >
          <span aria-hidden="true" className="wf-fav-g">☆</span>
          <span>{YOUR_PLACES}</span>
          {' '}
          <span className="wf-lens-pro">Pro</span>
        </button>
        <FavouriteUpsell upsell={upsell} testIdPrefix="your-places-upsell" />
      </div>
    );
  }
  if (!ready) return null;

  /** The words after the name: the served figure with its window, or the honest reason there is none. */
  const detail = (row) => {
    if (row.kind === 'date') return row.chip.detail;
    if (row.kind === 'area') {
      const { figure } = row;
      if (!figure || (figure.topRating == null && figure.gatedMembers === 0)) return 'not rated yet';
      if (figure.topRating == null) return `${figure.gatedMembers} outside your picks`;
      if (figure.topRating < QUIET_BELOW) return QUIET_LINE;
      return [
        row.bestName ? `best ${row.bestName}` : null, `${figure.topRating}★`, figure.label,
        `${figure.ratedMembers} rated`,
      ].filter(Boolean).join(' · ');
    }
    if (row.gated) return 'outside your picks';
    if (!row.top) return 'not rated yet';
    if (row.top.rating < QUIET_BELOW) return QUIET_LINE;
    return [`${row.top.rating}★`, row.top.label, row.drive == null ? null : formatDriveDuration(row.drive)]
      .filter(Boolean).join(' · ');
  };

  /** The window a chip opens the map on, or null when it opens its sheet instead (quiet, gated or unrated). */
  const windowOf = (row) => {
    const figure = row.kind === 'area' ? row.figure : row.top;
    const rating = row.kind === 'area' ? figure?.topRating : figure?.rating;
    if (row.gated || rating == null || rating < QUIET_BELOW) return null;
    if (row.kind === 'area') {
      // The window the area sheet's "Show on map" foot opens (`areaMapWindow`), so the chip and the sheet agree.
      const target = areaMapWindow(windows, outlook?.get(row.area.id));
      return target ? { date: target.date, eventType: target.targetType } : null;
    }
    return figure?.date && figure?.targetType ? { date: figure.date, eventType: figure.targetType } : null;
  };

  const press = (row, event) => {
    if (row.kind === 'date') {
      const { chip } = row;
      if (chip.kind === 'window') {
        onOpenWindow({
          window: chip.window,
          focus: chip.bestSpot ? { kind: 'location', id: chip.bestSpot.id, name: chip.bestSpot.name } : null,
          from: event.currentTarget,
        });
      } else {
        onOpenEntry?.(chip.entryId);
      }
      return;
    }
    const win = windowOf(row);
    if (!win) {
      if (row.kind === 'area') onOpenArea(row.area);
      else onOpenPlace(row.spot);
      return;
    }
    onOpenWindow({
      window: win,
      focus: row.kind === 'area' ? { kind: 'area', area: row.area } : { kind: 'location', id: row.spot.id, name: row.spot.name },
      from: event.currentTarget,
    });
  };

  return (
    <section
      ref={viewRef}
      className="wf-fav-section wf-yp"
      aria-labelledby="your-places-h"
      data-testid="your-places"
    >
      <h3 id="your-places-h" className="wf-fav-section-h">{`★ ${YOUR_PLACES}`}</h3>
      {nothingStarred ? (
        <p className="wf-cu-note" data-testid="your-places-empty">{YOUR_PLACES_EMPTY}</p>
      ) : (
        <>
          {rows.length > 0 && (
            <ul className="wf-yp-row m-0 list-none p-0" data-testid="your-places-row">
              {rows.map((row) => (
                <li key={row.key} className="wf-yp-item">
                  {row.kind === 'date' && row.chip.kind === 'static' ? (
                    // A watched date with no window of its own: a chip drawn as text, so a control never opens nothing.
                    <div
                      data-testid="your-places-chip"
                      data-kind="date"
                      data-date-state="static"
                      className="wf-yp-chip wf-yp-chip-static text-left"
                    >
                      <span className="wf-yp-name">
                        <span aria-hidden="true" className="wf-yp-star">★</span>
                        <span className="sr-only">Watched date: </span>
                        {row.chip.glyph && <span aria-hidden="true">{`${row.chip.glyph} `}</span>}
                        {row.name}
                      </span>
                      <span className="wf-yp-detail" data-testid="your-places-detail">{detail(row)}</span>
                    </div>
                  ) : (
                  <button
                    type="button"
                    data-testid="your-places-chip"
                    data-kind={row.kind}
                    data-date-state={row.kind === 'date' ? row.chip.kind : undefined}
                    className="wf-yp-chip text-left focus-visible:outline focus-visible:outline-2 focus-visible:outline-plex-gold"
                    onClick={(event) => press(row, event)}
                  >
                    <span className="wf-yp-name">
                      {row.kind === 'date' && (
                        <>
                          <span aria-hidden="true" className="wf-yp-star">★</span>
                          <span className="sr-only">Watched date: </span>
                          {row.chip.glyph && <span aria-hidden="true">{`${row.chip.glyph} `}</span>}
                        </>
                      )}
                      {row.name}
                    </span>
                    <span className="wf-yp-detail" data-testid="your-places-detail">{detail(row)}</span>
                  </button>
                  )}
                </li>
              ))}
            </ul>
          )}
          {/* A starred area the directory has not named yet is said, never silently left out. */}
          {favourites.areasUnresolved > 0 && (
            <p className="wf-cu-note" data-testid="your-places-areas-pending">
              {`${favourites.areasUnresolved} starred ${favourites.areasUnresolved === 1 ? 'area is' : 'areas are'} not shown yet.`}
            </p>
          )}
          {rows.length === 0 && favourites.areasUnresolved === 0 && (
            <p className="wf-cu-note" data-testid="your-places-offplan">
              {favourites.placeIds.size + favourites.areaIds.size > 0
                ? 'Your starred places are not on the plan right now.'
                : 'Your watched dates are not listed right now.'}
            </p>
          )}
        </>
      )}
    </section>
  );
}

YourPlacesRow.propTypes = {
  spots: PropTypes.arrayOf(PropTypes.object),
  windows: PropTypes.arrayOf(PropTypes.object),
  scoreIndex: PropTypes.shape({ byId: PropTypes.instanceOf(Map), byName: PropTypes.instanceOf(Map) }),
  reachById: PropTypes.instanceOf(Map),
  allowedLocationIds: PropTypes.arrayOf(PropTypes.number),
  onOpenWindow: PropTypes.func.isRequired,
  onOpenPlace: PropTypes.func.isRequired,
  onOpenArea: PropTypes.func.isRequired,
  entries: PropTypes.arrayOf(PropTypes.object),
  hotTopics: PropTypes.arrayOf(PropTypes.object),
  todayStr: PropTypes.string,
  onOpenEntry: PropTypes.func,
};
