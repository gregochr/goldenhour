import React, {
  useEffect, useLayoutEffect, useMemo, useRef,
} from 'react';
import PropTypes from 'prop-types';
import { useFavourites } from '../context/FavouritesContext.jsx';
import { useAreaOutlook } from '../hooks/useAreaFind.js';
import useInViewOnce from '../hooks/useInViewOnce.js';
import { areaFigure, topWindowFor } from '../utils/planSearch.js';
import { areaMapWindow } from '../utils/areaFind.js';
import { isOutsidePicks } from '../utils/pickScope.js';
import { YOUR_PLACES } from '../utils/favourites.js';
import BookmarkMark from './shared/BookmarkMark.jsx';
import StarFigure from './shared/StarFigure.jsx';
import { FavouriteUpsell, upsellEscape, useFavouriteUpsell } from './shared/FavouriteToggle.jsx';

/** Below this the line reads quietly: the reader's own place has nothing worth a trip in the days we have scored. */
const QUIET_BELOW = 3;
const YOUR_PLACES_EMPTY = 'Bookmark a place or an area, and it lands here.';

/**
 * The row's order once its figures are in (screen-spec.md D2): best served rating first, then drive minutes (an area
 * has none, so it ties like a place with an unknown drive), then name. A row that reads quiet (under 3★, not rated yet, or outside
 * the reader's picks) goes after every worthwhile one, in the same order among themselves, rated before unrated.
 *
 * @param {Array<{rating: ?number, drive: ?number, name: string}>} rows the rows in insertion order
 * @returns {Array} a new, sorted array
 */
function orderRows(rows) {
  const quiet = (row) => row.rating == null || row.rating < QUIET_BELOW;
  return [...rows].sort((a, b) => (
    Number(quiet(a)) - Number(quiet(b))
    || (b.rating ?? -1) - (a.rating ?? -1)
    || (a.drive ?? Infinity) - (b.drive ?? Infinity)
    || a.name.localeCompare(b.name)
  ));
}

/**
 * What follows a chip's name: the served figure with its window, or the honest reason there is none.
 *
 * @param {object} row an area or place row
 * @returns {{node: React.ReactNode, quiet: boolean}}
 */
function lineOf(row) {
  const NOT_RATED = { node: 'not rated yet', quiet: true };
  const quietLine = { node: <>nothing above <StarFigure n={2} /></>, quiet: true };
  if (row.kind === 'area') {
    const { figure } = row;
    if (!figure || (figure.topRating == null && figure.gatedMembers === 0)) return NOT_RATED;
    if (figure.topRating == null) return { node: `${figure.gatedMembers} outside your picks`, quiet: true };
    if (figure.topRating < QUIET_BELOW) return quietLine;
    return { node: <>{`${figure.label} `}<StarFigure n={figure.topRating} /></>, quiet: false };
  }
  if (row.gated) return { node: 'outside your picks', quiet: true };
  if (!row.top) return NOT_RATED;
  if (row.top.rating < QUIET_BELOW) return quietLine;
  return { node: <>{`${row.top.label} `}<StarFigure n={row.top.rating} /></>, quiet: false };
}

/**
 * Your places (find-and-favourites-plan.md F4, D3, D4; one-screen-plan.md D15; screen-spec.md D2): the reader's own
 * bookmarked areas and places as ONE horizontal row of one-line chips at the top of This week, a SEPARATE labelled
 * section above the forecast. It scrolls sideways (with scroll-snap on a phone). Places and areas only: a watched date
 * is never here (it is its day's lane in This week, or Watching at the top of Further ahead).
 *
 * <p><b>The order</b> (a licensed client derivation, architecture-decisions.md "Backend-heavy"): best served rating
 * first, the figure the chip prints; ties by drive minutes, then by name (an area has no drive, so it sorts with a
 * place whose drive is unknown: by name). Quiet rows (nothing above 2★, not rated yet, outside the reader's picks) come
 * last and read muted ({@code wf-yp-quiet}). It is a sort of the reader's own rows by a served figure, never a
 * verdict, a pick or a re-rank of any rating (screen-spec.md "Honesty"). <b>It sorts only once its figures are in</b>:
 * until the area outlook and the place scores have landed the row keeps its insertion order, areas then places, so
 * nothing re-orders under the reader's thumb once they have started to reach for a chip.
 *
 * <p>Every figure is served. A place's is its own best window (the same walk Find's place row and the area sheet make,
 * {@code topWindowFor}); an area's is the per-reader outlook ({@code GET /api/areas/outlook}) with its window, never
 * "best of the area" as a verdict, and a Lite-style gated member is counted and never rated. One line per chip, about
 * 34 px: {@code [mark] Crimdon Beach · Fri sunrise 4★}. Below 3★ a chip reads quietly ({@code nothing above 2★}); a
 * place or area with nothing rated says so.
 *
 * <p><b>What a tap does.</b> A chip with a worthwhile window opens the map view on that window, focused on the place
 * or area ({@code onOpenWindow}). A chip with no window to open (quiet, outside the reader's picks, not rated yet)
 * opens the place's or area's own sheet instead, so a control never opens nothing.
 *
 * <p><b>Fetching.</b> {@code useAreaOutlook} fetches only once the row has been on screen, so a reader who never
 * scrolls to a bookmarked area never pays for it.
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
 * @param {function(object): void} props.onOpenWindow opens the map view; given
 *        {@code {window: {date, eventType}, focus, from}}
 * @param {function(object): void} props.onOpenPlace opens a place's next-days sheet, given the heat spot
 * @param {function(object): void} props.onOpenArea opens an area's sheet, given the directory entry
 */
export default function YourPlacesRow({
  spots, windows, scoreIndex, reachById, allowedLocationIds, onOpenWindow, onOpenPlace, onOpenArea,
}) {
  const favourites = useFavourites();
  const upsell = useFavouriteUpsell();
  const { setTrigger } = upsell;
  const [viewRef, inView] = useInViewOnce();
  const ready = favourites.status === 'ready';
  // Empty only when the SERVED list holds nothing: places and areas both zero, whatever has or has not resolved yet.
  const nothingStarred = favourites.placeIds.size === 0 && favourites.areaIds.size === 0;
  const outlook = useAreaOutlook(inView && ready && favourites.areaIds.size > 0);

  const rows = useMemo(() => {
    if (!ready) return [];
    const byId = new Map((spots || []).map((spot) => [spot.id, spot]));
    const areaRows = favourites.bookmarkedAreas.map((area) => {
      const figure = areaFigure(outlook?.get(area.id), windows);
      return {
        kind: 'area', key: `area:${area.id}`, area, name: area.name, figure, rating: figure?.topRating ?? null,
        drive: null,
      };
    });
    const placeRows = [...favourites.placeIds].map((id) => byId.get(id)).filter(Boolean).map((spot) => {
      const gated = isOutsidePicks(allowedLocationIds, spot.id);
      const top = gated ? null : topWindowFor(spot, windows, scoreIndex);
      return {
        kind: 'place', key: `place:${spot.id}`, spot, name: spot.name, gated, top,
        rating: top?.rating ?? null, drive: reachById?.get(spot.id)?.driveMinutes ?? null,
      };
    });
    const insertion = [...areaRows, ...placeRows];
    // Sorted only once the figures are in: an area's arrive with the outlook, a place's with the score index.
    const figuresIn = (areaRows.length === 0 || outlook != null) && (placeRows.length === 0 || scoreIndex != null);
    return figuresIn ? orderRows(insertion) : insertion;
  }, [
    ready, favourites.bookmarkedAreas, favourites.placeIds, spots, windows, scoreIndex, reachById, allowedLocationIds,
    outlook,
  ]);

  // The row keeps scroll-snap on a phone, and a browser re-snaps to the chip it had snapped to when the chips reorder,
  // which would scroll the best chip out of view the moment the sort lands. So a reorder puts an untouched row back at
  // its start; a reader who has already reached for it (pointer, touch, wheel, keys, focus) is never moved.
  const listRef = useRef(null);
  const touched = useRef(false);
  const orderKey = rows.map((row) => row.key).join('|');
  useLayoutEffect(() => {
    if (listRef.current && !touched.current) listRef.current.scrollLeft = 0;
  }, [orderKey]);
  const hasRow = rows.length > 0;
  useEffect(() => {
    const list = listRef.current;
    if (!list) return undefined;
    const note = () => { touched.current = true; };
    const gestures = ['pointerdown', 'touchstart', 'wheel', 'keydown', 'focusin'];
    gestures.forEach((gesture) => list.addEventListener(gesture, note, { passive: true }));
    return () => gestures.forEach((gesture) => list.removeEventListener(gesture, note));
  }, [hasRow]);

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
          <span className="wf-fav-g"><BookmarkMark outline /></span>
          <span>{YOUR_PLACES}</span>
          {' '}
          <span className="wf-lens-pro">Pro</span>
        </button>
        <FavouriteUpsell upsell={upsell} testIdPrefix="your-places-upsell" />
      </div>
    );
  }
  if (!ready) return null;

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
      <h3 id="your-places-h" className="wf-fav-section-h">
        <BookmarkMark className="wf-yp-mark" />
        {YOUR_PLACES}
      </h3>
      {nothingStarred ? (
        <p className="wf-cu-note" data-testid="your-places-empty">{YOUR_PLACES_EMPTY}</p>
      ) : (
        <>
          {rows.length > 0 && (
            <ul
              ref={listRef}
              className="wf-yp-row m-0 list-none p-0"
              data-testid="your-places-row"
            >
              {rows.map((row) => {
                const line = lineOf(row);
                return (
                  <li key={row.key} className="wf-yp-item">
                    <button
                      type="button"
                      data-testid="your-places-chip"
                      data-kind={row.kind}
                      data-quiet={line.quiet ? 'true' : undefined}
                      className={`wf-yp-chip text-left focus-visible:outline focus-visible:outline-2 focus-visible:outline-plex-gold${line.quiet ? ' wf-yp-quiet' : ''}`}
                      onClick={(event) => press(row, event)}
                    >
                      <BookmarkMark className="wf-yp-mark" />
                      <span className="wf-yp-name">{row.name}</span>
                      <span className="sr-only">,</span>
                      {' '}
                      <span aria-hidden="true" className="wf-yp-sep">{' · '}</span>
                      <span className="wf-yp-detail" data-testid="your-places-detail">{line.node}</span>
                    </button>
                  </li>
                );
              })}
            </ul>
          )}
          {/* A bookmarked area the directory has not named yet is said, never silently left out. */}
          {favourites.areasUnresolved > 0 && (
            <p className="wf-cu-note" data-testid="your-places-areas-pending">
              {`${favourites.areasUnresolved} bookmarked ${favourites.areasUnresolved === 1 ? 'area is' : 'areas are'} not shown yet.`}
            </p>
          )}
          {rows.length === 0 && favourites.areasUnresolved === 0 && (
            <p className="wf-cu-note" data-testid="your-places-offplan">
              Your bookmarked places are not on the plan right now.
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
};
