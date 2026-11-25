import React, {
  useEffect, useLayoutEffect, useMemo, useRef, useState,
} from 'react';
import PropTypes from 'prop-types';
import { useFavourites } from '../context/FavouritesContext.jsx';
import { useAreaOutlook } from '../hooks/useAreaFind.js';
import useInViewOnce from '../hooks/useInViewOnce.js';
import { areaFigure, topWindowFor } from '../utils/planSearch.js';
import { areaMapWindow } from '../utils/areaFind.js';
import { isOutsidePicks } from '../utils/pickScope.js';
import { YOUR_PLACES } from '../utils/favourites.js';
import { THIS_WEEK_HEADING_ID } from '../utils/screenSections.js';
import BookmarkMark from './shared/BookmarkMark.jsx';
import StarFigure from './shared/StarFigure.jsx';
import { FavouriteUpsell, upsellEscape, useFavouriteUpsell } from './shared/FavouriteToggle.jsx';

/** Below this the line reads quietly: the reader's own place has nothing worth a trip in the days we have scored. */
const QUIET_BELOW = 3;

/** The one-time hint (screen-spec.md D8): it teaches the next action for a reader with nothing saved. */
const PLACES_HINT = 'Bookmark places and watch dates to build your plan.';
/** The Lite reader's upsell, one body covering places and dates (it replaces the two per-toggle teasers' separate words). */
const PLACES_HINT_UPSELL_HEADING = 'Build your plan';
const PLACES_HINT_UPSELL_BODY = 'Pro keeps your own list of bookmarked places and areas, and can watch dates known in advance. Places get a row of their own at the top of This week; a watched date moves to the top of Further ahead and, once it is forecast, becomes a lane on its day.';

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
 * <p><b>The one-time hint</b> (screen-spec.md D8). A reader with nothing saved, while the server says it is still due
 * ({@code hintDue}, {@code placesHintDue}), sees one dashed line in this row's place: {@code Bookmark places and watch
 * dates to build your plan.} with a ✕ ("Dismiss"). A Lite reader sees the same line with the Pro pill, which opens a
 * worded upsell beneath it (one body for places and dates). The ✕ hides it at once, writes the flag and puts focus on
 * the This week heading; the first save, or a dismissal, ends it for good. With the hint gone, a reader with nothing
 * bookmarked sees no row at all (no empty heading, no teaser): a Lite reader meets the upsell on the locked toggles.
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
 * @param {boolean} [props.hintDue] whether the one-time hint is still due
 * @param {?function(): void} [props.onDismissHint] records the hint's dismissal
 */
export default function YourPlacesRow({
  spots, windows, scoreIndex, reachById, allowedLocationIds, onOpenWindow, onOpenPlace, onOpenArea,
  hintDue = false, onDismissHint = null,
}) {
  const favourites = useFavourites();
  const upsell = useFavouriteUpsell();
  const { setTrigger } = upsell;
  const [viewRef, inView] = useInViewOnce();
  const ready = favourites.status === 'ready';
  // Empty only when the SERVED list holds nothing: places and areas both zero, whatever has or has not resolved yet.
  const nothingStarred = favourites.placeIds.size === 0 && favourites.areaIds.size === 0;
  const outlook = useAreaOutlook(inView && ready && favourites.areaIds.size > 0);
  // The hint's own session state: hidden at once by its ✕, and latched off by the first save made while it was due, so
  // removing every bookmark afterwards does not bring it back (the server stamps the same flag on that first save).
  const [hintGone, setHintGone] = useState(false);
  const [everSaved, setEverSaved] = useState(false);
  const savedNow = ready && (!nothingStarred || (favourites.dates?.length ?? 0) > 0);
  // Latched while rendering (React's own "adjust state when a prop changes" pattern), so the very commit that shows the
  // first save already has the hint gone.
  if (savedNow && !everSaved) setEverSaved(true);
  /** ✕: hides the hint at once, writes the flag, and puts focus on the This week heading (the button unmounts). */
  const dismissHint = () => {
    setHintGone(true);
    onDismissHint?.();
    document.getElementById(THIS_WEEK_HEADING_ID)?.focus({ preventScroll: true });
  };

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

  // The one-time hint (screen-spec.md D8) takes this row's place for a reader with nothing saved, and only while the
  // server says it is still due. A Lite reader (the list is `locked`) sees the same line with the Pro pill; a Pro
  // reader sees it only once the list has landed empty, never while it loads or after it failed. Dismissed, or the
  // first save made in this session, it never comes back, even if the reader later removes everything.
  const locked = favourites.status === 'locked';
  const nothingSaved = nothingStarred && (favourites.dates?.length ?? 0) === 0;
  const hintShown = hintDue && !hintGone && !everSaved && (locked || (ready && nothingSaved));
  if (hintShown) {
    return (
      <div className="wf-fav-section wf-yp wf-yp-hint-wrap" data-testid="your-places-hint">
        <div className="wf-yp-hint">
          {locked ? (
            <button
              type="button"
              ref={setTrigger}
              className="wf-yp-hint-t wf-yp-hint-lite"
              data-testid="your-places-hint-button"
              aria-expanded={upsell.open}
              aria-controls={upsell.open ? upsell.id : undefined}
              onClick={upsell.toggle}
              onKeyDown={upsellEscape(upsell)}
            >
              <BookmarkMark outline className="wf-yp-mark" />
              <span>{PLACES_HINT}</span>
              {' '}
              <span className="wf-lens-pro">Pro</span>
            </button>
          ) : (
            <p className="wf-yp-hint-t m-0" data-testid="your-places-hint-text">
              <BookmarkMark className="wf-yp-mark" />
              <span>{PLACES_HINT}</span>
            </p>
          )}
          <button
            type="button"
            className="wf-yp-hint-x"
            data-testid="your-places-hint-dismiss"
            aria-label="Dismiss"
            onClick={dismissHint}
          >
            <span aria-hidden="true">✕</span>
          </button>
        </div>
        {locked && (
          <FavouriteUpsell
            upsell={upsell}
            testIdPrefix="your-places-upsell"
            heading={PLACES_HINT_UPSELL_HEADING}
            body={PLACES_HINT_UPSELL_BODY}
          />
        )}
      </div>
    );
  }
  // Nothing bookmarked: the row is absent (no empty heading, no teaser). A Lite reader meets the upsell on the locked
  // toggles only, once the hint is gone.
  if (locked || !ready || nothingStarred) return null;

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
    </section>
  );
}

YourPlacesRow.propTypes = {
  /** Whether the one-time hint is still due (the server's `placesHintDue`). */
  hintDue: PropTypes.bool,
  /** Records the hint's dismissal; called once from the ✕. */
  onDismissHint: PropTypes.func,
  spots: PropTypes.arrayOf(PropTypes.object),
  windows: PropTypes.arrayOf(PropTypes.object),
  scoreIndex: PropTypes.shape({ byId: PropTypes.instanceOf(Map), byName: PropTypes.instanceOf(Map) }),
  reachById: PropTypes.instanceOf(Map),
  allowedLocationIds: PropTypes.arrayOf(PropTypes.number),
  onOpenWindow: PropTypes.func.isRequired,
  onOpenPlace: PropTypes.func.isRequired,
  onOpenArea: PropTypes.func.isRequired,
};
