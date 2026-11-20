import React, { useId, useState } from 'react';
import PropTypes from 'prop-types';

/**
 * The Map's "◎ Best bet · why" (glance-to-map-plan.md G1): the reason the Plan card names its pick, readable where the
 * reader now drills in. A pill of its own UNDER the window control (never inside the control's flex row, which a long
 * pill would crowd on a phone) that expands the served {@code card.pick} headline and detail in place.
 *
 * <p>It draws what the server served and nothing else: the pick is the card's own ({@code card.pick}, which the server
 * nulls for a Lite reader and the card nulls for an away origin the pick is scoped out of, and for an orphan "also"),
 * so a null pick prints nothing at all and this never re-picks or re-scopes. The words are the region's own gloss (the
 * pick's headline and detail are the region gloss, not the Best-bet advisor's). No star and no rating: the pick's
 * average is published for audit, not for display (the old pick dialog's own rule).
 *
 * <p>A disclosure button ({@code aria-expanded}, and {@code aria-controls} only while the body exists), not a live
 * region: the tab already owns its one status region ({@code #wf-win-status}), and opening this is a press the reader
 * made, which needs no announcement of its own. Keyed by the caller on the window so it closes when the window moves.
 *
 * @param {object} props
 * @param {?{kind: string, regionName: ?string, headline: ?string, detail: ?string, locationName: ?string}} props.pick
 *        the selected window card's served pick, or null
 */
export default function MapBestBetWhy({ pick = null }) {
  const [open, setOpen] = useState(false);
  const bodyId = useId();
  if (!pick || !pick.headline) return null;
  const word = pick.kind === 'best' ? 'Best bet' : 'Also good';
  return (
    <div className="pointer-events-none w-0 min-w-full" data-testid="map-best-bet-row">
      <div className="pointer-events-auto mt-1 inline-flex max-w-full flex-col items-start gap-1">
        <button
          type="button"
          data-testid="map-best-bet-why"
          className="wf-bestbet-pill font-mono"
          aria-expanded={open}
          aria-controls={open ? bodyId : undefined}
          onClick={() => setOpen((cur) => !cur)}
        >
          <span aria-hidden="true">&#9678; </span>
          {`${word} · why`}
          <span aria-hidden="true" className="wf-win-caret">{open ? '▴' : '▾'}</span>
        </button>
        {open && (
          <div id={bodyId} data-testid="map-best-bet-body" className="wf-bestbet-body">
            {pick.regionName && (
              <p data-testid="map-best-bet-region" className="font-mono text-[10px] uppercase tracking-wider text-plex-text-secondary">
                {pick.regionName}
              </p>
            )}
            <p data-testid="map-best-bet-headline" className="font-serif text-[13.5px] leading-snug text-plex-text">
              {pick.headline}
            </p>
            {pick.detail && (
              <p data-testid="map-best-bet-detail" className="text-[12px] leading-snug text-plex-text-secondary">
                {pick.detail}
              </p>
            )}
            {pick.locationName && (
              <p data-testid="map-best-bet-location" className="font-mono text-[10.5px] text-plex-text-muted">
                {pick.locationName}
              </p>
            )}
          </div>
        )}
      </div>
    </div>
  );
}

MapBestBetWhy.propTypes = {
  pick: PropTypes.shape({
    kind: PropTypes.string,
    regionName: PropTypes.string,
    headline: PropTypes.string,
    detail: PropTypes.string,
    locationName: PropTypes.string,
  }),
};
