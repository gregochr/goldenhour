import React, { useEffect, useId, useRef, useState } from 'react';
import PropTypes from 'prop-types';
import { useFavourites } from '../../context/FavouritesContext.jsx';
import { useIsMobile } from '../../hooks/useIsMobile.js';
import { ProUpsellContent } from './ProUpsell.jsx';

/** The upsell's words (roles-and-gating.md): worded, never a link, never an upgrade button; `/upgrade` is no route. */
export const FAVOURITES_UPSELL_HEADING = 'Your places';
export const FAVOURITES_UPSELL_BODY = 'Pro keeps your own list of places and areas. They come first in Find, have their own entry in the region pickers and a row of their own at the top of This week.';

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
 */
export function FavouriteUpsell({ upsell, testIdPrefix = 'favourite-upsell' }) {
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
          heading={FAVOURITES_UPSELL_HEADING}
          body={FAVOURITES_UPSELL_BODY}
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
};

/**
 * The ☆ / ★ toggle (find-and-favourites-plan.md F4): in the place sheet, the area sheet and the map callout header, and
 * nowhere else. Never inside a Find option row (an option is the one control there).
 *
 * <p><b>One constant label, {@code Your place}, with {@code aria-pressed}</b>: the state is the pressed state, never a
 * swapped name on top of it. Visible text on a desktop is {@code ☆ Your place} / {@code ★ Your place}; on a phone the
 * glyph stands alone (a 30px target in a 44px row, {@code index.css}) and the name is spoken only.
 *
 * <p>A Pro or Admin reader whose list has landed gets the toggle. A Lite reader gets the ☆ with a Pro pill, which
 * opens the worded upsell through {@code upsell} (the host draws {@link FavouriteUpsell}). Anyone else (no
 * provider, loading, failed) gets nothing: a toggle with a wrong {@code aria-pressed} would be a false statement.
 *
 * @param {object} props
 * @param {'place'|'area'} props.kind
 * @param {?number} props.id the location or area id; none, none drawn
 * @param {string} props.name what the toast calls it
 * @param {ReturnType<typeof useFavouriteUpsell>} props.upsell the host's upsell state, for the Lite reader
 * @param {string} [props.testId]
 */
export default function FavouriteToggle({ kind, id, name, upsell, testId = 'favourite-toggle' }) {
  const favourites = useFavourites();
  const isMobile = useIsMobile();
  const { setTrigger } = upsell;
  if (id == null) return null;

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
          <span aria-hidden="true" className="wf-fav-g">☆</span>
          <span className={isMobile ? 'sr-only' : 'wf-fav-t'}>Your place</span>
          {' '}
          <span className="wf-lens-pro">Pro</span>
        </button>
      </span>
    );
  }
  if (favourites.status !== 'ready') return null;

  const starred = kind === 'area' ? favourites.isAreaStarred(id) : favourites.isPlaceStarred(id);
  return (
    <span className="wf-fav-wrap">
      <button
        type="button"
        className="wf-fav"
        data-testid={testId}
        data-starred={starred ? 'true' : 'false'}
        aria-pressed={starred}
        // A void press: nothing waits on the request, the toast and the revert report it.
        onClick={() => { void favourites.toggle(kind, id, name); }}
      >
        <span aria-hidden="true" className="wf-fav-g">{starred ? '★' : '☆'}</span>
        <span className={isMobile ? 'sr-only' : 'wf-fav-t'}>Your place</span>
      </button>
    </span>
  );
}

FavouriteToggle.propTypes = {
  kind: PropTypes.oneOf(['place', 'area']).isRequired,
  id: PropTypes.number,
  name: PropTypes.string.isRequired,
  upsell: PropTypes.shape({
    open: PropTypes.bool.isRequired,
    id: PropTypes.string.isRequired,
    toggle: PropTypes.func.isRequired,
    close: PropTypes.func.isRequired,
    setTrigger: PropTypes.func.isRequired,
  }).isRequired,
  testId: PropTypes.string,
};

/**
 * A ★ beside a starred PLACE's name on a surface that lists places (map markers, popup spot cards, the Glance best
 * spot). Only a place the reader starred itself: a place inside a starred area is not marked individually. A mark and
 * nothing more: it never moves, restyles or re-ranks the row it sits on.
 *
 * @param {object} props
 * @param {?number} props.id the location id
 * @param {string} [props.className]
 */
export function FavouriteMark({ id, className = 'wf-fav-mark' }) {
  const { status, isPlaceStarred } = useFavourites();
  if (status !== 'ready' || id == null || !isPlaceStarred(id)) return null;
  return <span aria-hidden="true" className={className} data-testid="favourite-mark">★</span>;
}

FavouriteMark.propTypes = {
  id: PropTypes.number,
  className: PropTypes.string,
};

/**
 * Whether a place is one the reader starred, for a surface that adds a spoken word beside the mark.
 *
 * @param {?number} id the location id
 * @returns {boolean}
 */
export function useIsStarredPlace(id) {
  const { status, isPlaceStarred } = useFavourites();
  return status === 'ready' && id != null && isPlaceStarred(id);
}
