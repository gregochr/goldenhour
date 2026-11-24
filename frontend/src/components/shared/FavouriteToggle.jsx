import React, { useEffect, useId, useRef, useState } from 'react';
import PropTypes from 'prop-types';
import { useFavourites } from '../../context/FavouritesContext.jsx';
import { useIsMobile } from '../../hooks/useIsMobile.js';
import BookmarkMark from './BookmarkMark.jsx';
import { ProUpsellContent } from './ProUpsell.jsx';

/** A stable empty list, so a place or area toggle's default prop is not a new array every render. */
const NO_KEYS = [];

/** The upsell's words (roles-and-gating.md): worded, never a link, never an upgrade button; `/upgrade` is no route. */
export const FAVOURITES_UPSELL_HEADING = 'Your places';
export const FAVOURITES_UPSELL_BODY = 'Pro keeps your own list of bookmarked places and areas. They come first in Find, have their own entry in the region pickers and a row of their own at the top of This week.';

/** The upsell a Lite reader's "Watch this date" opens (one-screen-plan.md D16), in its own words. */
export const WATCH_UPSELL_HEADING = 'Watch a date';
export const WATCH_UPSELL_BODY = 'Pro can watch a date known in advance. It joins Your places at the top of This week, and once it is forecast it names the morning to go and the best spot.';

/** The date toggle's two labels: the words carry the state (there is no {@code aria-pressed}). */
export const WATCH_LABEL = 'Watch this date';
export const WATCHING_LABEL = 'Watching';
/** The place and area toggle's two labels. */
export const BOOKMARK_LABEL = 'Bookmark';
export const BOOKMARKED_LABEL = 'Bookmarked';

/**
 * One Lite reader's open upsell, shared by the toggle that opens it and the panel the host draws under its header.
 * The panel is a host-placed block rather than a popover because every host (the sheets, the map callout) clips its
 * overflow.
 *
 * @returns {{open: boolean, toggle: function(): void, close: function(): void, id: string,
 *   setTrigger: function(?Element): void, focusTrigger: function(): void}}
 */
export function useFavouriteUpsell() {
  const [open, setOpen] = useState(false);
  const id = useId();
  // A callback ref held in state, so the host can hand focus back without reading a ref during render.
  const [trigger, setTrigger] = useState(null);
  return {
    open,
    id,
    setTrigger,
    focusTrigger: () => trigger?.focus({ preventScroll: true }),
    toggle: () => setOpen((v) => !v),
    close: () => setOpen(false),
  };
}

/**
 * The trigger-side Escape for a Lite upsell: with the panel open and focus still on the trigger, Escape closes ONLY
 * the panel (the key is stopped so the dialog under it stays), as it does with focus inside the panel.
 *
 * @param {{open: boolean, close: function(): void}} upsell
 * @returns {function(React.KeyboardEvent): void}
 */
export function upsellEscape(upsell) {
  return (e) => {
    if (e.key !== 'Escape' || !upsell.open) return;
    e.stopPropagation();
    e.preventDefault();
    upsell.close();
  };
}

/**
 * The Lite upsell panel, drawn by the host under its header while {@code upsell.open}. Focus moves to its heading on
 * open; Escape (on it or on the trigger) closes ONLY the panel and hands focus back to the trigger, stopping the key
 * so the dialog it sits in stays open. It is not a dialog, so it adds nothing to the two-layer count.
 *
 * @param {object} props
 * @param {ReturnType<typeof useFavouriteUpsell>} props.upsell
 * @param {string} [props.testIdPrefix]
 * @param {string} [props.heading] the panel's heading (places and areas by default)
 * @param {string} [props.body] the panel's sentence (places and areas by default)
 */
export function FavouriteUpsell({
  upsell, testIdPrefix = 'favourite-upsell', heading = FAVOURITES_UPSELL_HEADING, body = FAVOURITES_UPSELL_BODY,
}) {
  const headingRef = useRef(null);
  const headingId = `${upsell.id}-h`;
  const bodyId = `${upsell.id}-b`;
  useEffect(() => {
    if (upsell.open) headingRef.current?.focus({ preventScroll: true });
  }, [upsell.open]);
  if (!upsell.open) return null;

  const close = () => {
    upsell.close();
    upsell.focusTrigger();
  };
  return (
    <div className="wf-pro-upsell-door wf-pro-upsell-door--inline" data-testid={`${testIdPrefix}-door`}>
      {/* The group only owns Escape for the panel's own controls. */}
      {/* eslint-disable-next-line jsx-a11y/no-noninteractive-element-interactions */}
      <div
        id={upsell.id}
        role="group"
        aria-labelledby={headingId}
        aria-describedby={bodyId}
        className="wf-pro-upsell"
        data-testid={`${testIdPrefix}-panel`}
        onKeyDown={(e) => {
          if (e.key !== 'Escape') return;
          e.stopPropagation();
          e.preventDefault();
          close();
        }}
      >
        <ProUpsellContent
          heading={heading}
          body={body}
          headingId={headingId}
          headingRef={headingRef}
          bodyId={bodyId}
          testIdPrefix={testIdPrefix}
        />
        <button
          type="button"
          className="wf-pro-upsell-dismiss"
          data-testid={`${testIdPrefix}-dismiss`}
          onClick={close}
        >
          Not now
        </button>
      </div>
    </div>
  );
}

FavouriteUpsell.propTypes = {
  upsell: PropTypes.shape({
    open: PropTypes.bool.isRequired,
    id: PropTypes.string.isRequired,
    close: PropTypes.func.isRequired,
    focusTrigger: PropTypes.func.isRequired,
  }).isRequired,
  testIdPrefix: PropTypes.string,
  heading: PropTypes.string,
  body: PropTypes.string,
};

/**
 * The bookmark toggle (find-and-favourites-plan.md F4, screen-spec.md D1): in the place sheet, the area sheet and the
 * map callout header, and nowhere else. Never inside a Find option row (an option is the one control there).
 *
 * <p><b>The words carry the state and there is no {@code aria-pressed}</b> (screen-spec-plan.md section 9.1): a place or
 * area reads {@code Bookmark} / {@code Bookmarked}, a date {@code Watch this date} / {@code Watching}. A changing name
 * on top of a pressed state would announce twice; the change is announced by the undo toast, a status region. The state
 * is mirrored in {@code data-starred} for the CSS. The mark is the {@link BookmarkMark} ribbon, outline until it is
 * set. On a phone a place's word is spoken only and the mark stands alone (a 30px target in a 44px row).
 *
 * <p>A Pro or Admin reader whose list has landed gets the toggle. A Lite reader gets the outline mark with a Pro pill,
 * which opens the worded upsell through {@code upsell} (the host draws {@link FavouriteUpsell}). Anyone else (no
 * provider, loading, failed) gets nothing: a toggle with a wrong name would be a false statement.
 *
 * <p><b>{@code kind="date"} is the Further ahead entry's "Watch this date"</b> (one-screen-plan.md D16): {@code id} is
 * the entry's watch key, the words are {@link WATCH_LABEL} / {@link WATCHING_LABEL} on every viewport (it sits in an
 * entry, not a header, so it is never mark-only), and the Lite door opens the date-worded upsell. It reads as pressed when ANY of
 * {@code matchedKeys} is watched (a stored watch can differ from the entry's own key after a drift), and a press on a
 * pressed toggle un-watches every one of them.
 *
 * @param {object} props
 * @param {'place'|'area'|'date'} props.kind
 * @param {?(number|string)} props.id the location or area id, or the date's watch key; none, none drawn
 * @param {string} props.name what the toast calls it
 * @param {ReturnType<typeof useFavouriteUpsell>} props.upsell the host's upsell state, for the Lite reader
 * @param {string} [props.testId]
 * @param {string[]} [props.matchedKeys] {@code kind="date"} only: the stored watches that are this entry
 */
export default function FavouriteToggle({
  kind, id, name, upsell, testId = 'favourite-toggle', matchedKeys = NO_KEYS,
}) {
  const favourites = useFavourites();
  const isMobile = useIsMobile();
  const { setTrigger } = upsell;
  if (id == null) return null;
  const isDate = kind === 'date';
  // A date reads its words on every viewport; a place's collapse to the mark on a phone.
  const wordsClass = isMobile && !isDate ? 'sr-only' : `wf-fav-t${isDate ? ' wf-fav-t-date' : ''}`;

  if (favourites.status === 'locked') {
    return (
      <span className="wf-fav-wrap">
        <button
          type="button"
          ref={setTrigger}
          className="wf-fav wf-fav-locked"
          data-testid={`${testId}-locked`}
          aria-expanded={upsell.open}
          aria-controls={upsell.open ? upsell.id : undefined}
          onClick={upsell.toggle}
          onKeyDown={upsellEscape(upsell)}
        >
          <span className="wf-fav-g"><BookmarkMark outline /></span>
          <span className={wordsClass}>{isDate ? WATCH_LABEL : BOOKMARK_LABEL}</span>
          {' '}
          <span className="wf-lens-pro">Pro</span>
        </button>
      </span>
    );
  }
  if (favourites.status !== 'ready') return null;

  const bookmarked = isDate
    ? matchedKeys.some((key) => favourites.isDateWatched(key))
    : (kind === 'area' ? favourites.isAreaStarred(id) : favourites.isPlaceStarred(id));
  const label = isDate
    ? (bookmarked ? WATCHING_LABEL : WATCH_LABEL)
    : (bookmarked ? BOOKMARKED_LABEL : BOOKMARK_LABEL);
  const press = () => {
    // Void presses: nothing waits on the request, the toast and the revert report it.
    if (isDate && bookmarked) {
      // One press, one Undo: each request carries the whole group, so the toast that survives restores all of them.
      const group = matchedKeys.filter((key) => favourites.isDateWatched(key));
      group.forEach((key) => { void favourites.setStar('date', key, name, false, false, group); });
      return;
    }
    void favourites.toggle(kind, id, name);
  };
  return (
    <span className="wf-fav-wrap">
      <button
        type="button"
        className="wf-fav"
        data-testid={testId}
        data-starred={bookmarked ? 'true' : 'false'}
        onClick={press}
      >
        <span className="wf-fav-g"><BookmarkMark outline={!bookmarked} /></span>
        <span className={wordsClass}>{label}</span>
      </button>
    </span>
  );
}

FavouriteToggle.propTypes = {
  kind: PropTypes.oneOf(['place', 'area', 'date']).isRequired,
  id: PropTypes.oneOfType([PropTypes.number, PropTypes.string]),
  name: PropTypes.string.isRequired,
  upsell: PropTypes.shape({
    open: PropTypes.bool.isRequired,
    id: PropTypes.string.isRequired,
    toggle: PropTypes.func.isRequired,
    close: PropTypes.func.isRequired,
    setTrigger: PropTypes.func.isRequired,
  }).isRequired,
  testId: PropTypes.string,
  matchedKeys: PropTypes.arrayOf(PropTypes.string),
};

/**
 * A bookmark mark beside a bookmarked PLACE's name on a surface that lists places (map markers, popup spot cards, the
 * Glance best spot). Only a place the reader bookmarked itself: a place inside a bookmarked area is not marked
 * individually. A mark and
 * nothing more: it never moves, restyles or re-ranks the row it sits on.
 *
 * @param {object} props
 * @param {?number} props.id the location id
 * @param {string} [props.className]
 */
export function FavouriteMark({ id, className = 'wf-fav-mark' }) {
  const { status, isPlaceStarred } = useFavourites();
  if (status !== 'ready' || id == null || !isPlaceStarred(id)) return null;
  return (
    <span aria-hidden="true" className={className} data-testid="favourite-mark">
      <BookmarkMark />
    </span>
  );
}

FavouriteMark.propTypes = {
  id: PropTypes.number,
  className: PropTypes.string,
};

/**
 * Whether a place is one the reader bookmarked, for a surface that adds a spoken word beside the mark.
 *
 * @param {?number} id the location id
 * @returns {boolean}
 */
export function useIsStarredPlace(id) {
  const { status, isPlaceStarred } = useFavourites();
  return status === 'ready' && id != null && isPlaceStarred(id);
}
