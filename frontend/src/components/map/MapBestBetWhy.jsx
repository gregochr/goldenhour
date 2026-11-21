import React from 'react';
import PropTypes from 'prop-types';
import MapInfoPanel from './MapInfoPanel.jsx';
import { PICK_TEXT } from './WindowControl.jsx';

/** The id the pill's {@code aria-controls} names, on {@link MapBestBetPanel}'s root. */
export const MAP_BEST_BET_PANEL_ID = 'wf-map-best-bet-panel';

const PICK_SHAPE = PropTypes.shape({
  kind: PropTypes.oneOf(['best', 'also']),
  regionName: PropTypes.string,
  headline: PropTypes.string,
  detail: PropTypes.string,
  locationName: PropTypes.string,
});

/** The pill's word: a best pick is "Best bet", an "also" pick "Also good". */
const wordOf = (pick) => (pick.kind === 'best' ? 'Best bet' : 'Also good');

/**
 * The Map's "◎ Best bet · why" pill (glance-to-map-plan.md G1): the reason the Plan card names its pick, readable where
 * the reader now drills in. A pill of its own UNDER the window control (never inside the control's flex row, which a
 * long pill would crowd on a phone). It is only the trigger: the reason itself is {@link MapBestBetPanel}, a
 * frame-level panel hung under the chrome group, so opening it never changes the group's height (which would change
 * {@code --wf-chrome-tl-h} and re-place the map's labels).
 *
 * <p>It draws what the server served and nothing else: the pick is the card's own ({@code card.pick}, which the server
 * nulls for a Lite reader and the card nulls for an away origin the pick is scoped out of, and for an orphan "also"),
 * so a null pick prints nothing at all and this never re-picks or re-scopes. A disclosure button
 * ({@code aria-expanded}, and {@code aria-controls} only while the panel exists), not a live region: the tab already
 * owns its one status region ({@code #wf-win-status}), and opening this is a press the reader made.
 *
 * @param {object} props
 * @param {?object} props.pick the selected window card's served pick, or null
 * @param {boolean} props.open whether the panel is open
 * @param {Function} props.onToggle opens or closes the panel; takes the button
 */
export default function MapBestBetWhy({ pick = null, open = false, onToggle }) {
  if (!pick || !pick.headline) return null;
  return (
    <div className="pointer-events-none w-0 min-w-full" data-testid="map-best-bet-row">
      <div className="pointer-events-auto mt-1 inline-flex max-w-full">
        <button
          type="button"
          data-testid="map-best-bet-why"
          className="wf-bestbet-pill font-mono"
          aria-expanded={open}
          aria-controls={open ? MAP_BEST_BET_PANEL_ID : undefined}
          onClick={(event) => onToggle(event.currentTarget)}
        >
          <span aria-hidden="true">{`${(PICK_TEXT[pick.kind] ?? PICK_TEXT.best).glyph} `}</span>
          {`${wordOf(pick)} · why`}
          <span aria-hidden="true" className="wf-win-caret">{open ? '▴' : '▾'}</span>
        </button>
      </div>
    </div>
  );
}

MapBestBetWhy.propTypes = {
  pick: PICK_SHAPE,
  open: PropTypes.bool,
  onToggle: PropTypes.func.isRequired,
};

/**
 * The reason behind the pill: the served headline, detail and place, in a capped panel hung under the chrome group at
 * the frame level (the warning panel's own placement and z-index). No star and no rating: the pick's average is
 * published for audit, not for display.
 *
 * @param {object} props
 * @param {?object} props.pick the selected window card's served pick
 * @param {Function} props.onClose closes the panel
 */
export function MapBestBetPanel({ pick = null, onClose }) {
  if (!pick || !pick.headline) return null;
  return (
    <MapInfoPanel
      className="wf-bestbet-panel"
      hostTestId="map-best-bet-body-host"
      closeTestId="map-best-bet-close"
      onClose={onClose}
    >
      <div id={MAP_BEST_BET_PANEL_ID} data-testid="map-best-bet-body" className="flex flex-col gap-1">
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
    </MapInfoPanel>
  );
}

MapBestBetPanel.propTypes = {
  pick: PICK_SHAPE,
  onClose: PropTypes.func.isRequired,
};
