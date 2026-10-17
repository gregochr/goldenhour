import PropTypes from 'prop-types';
import { setRewind } from '../utils/rewind.js';
import { formatDayClockUk } from '../utils/conversions.js';

/**
 * The one visible sign that the page is rewound (`utils/rewind.js`): a thin full-width bar in the
 * banner block above the page, naming the moment the app is rendering as of, with the way back to
 * live. Admin-only by construction — only an admin can set a rewind, and `App` mounts this only for
 * one.
 *
 * <p>A bar in normal flow, deliberately NOT a fixed corner pill: the Map tab is a full-frame surface
 * whose bottom-left holds the tide strip (and, on a phone, the peek sheet's buttons), and the first
 * cut's `fixed bottom-3 left-3` painted straight over them — on the one screen the feature exists to
 * screenshot. In the banner block the bar takes its own row (the Map tab's flex column absorbs it,
 * the same way it absorbs the aurora banner), covers nothing, and crops off the top of a screenshot.
 *
 * <p>Not a `Modal` and not a dialog: it claims no focus and traps nothing, so none of the shell's
 * one-modal invariants see it. The live region is the text, not the bar, so the button is not inside
 * a status node.
 *
 * @param {object} props
 * @param {{ to: string }} props.rewind - the current rewind
 */
export default function RewindPill({ rewind }) {
  return (
    <div
      data-testid="rewind-pill"
      className="flex flex-wrap items-center justify-center gap-3 border-b border-plex-gold/60 bg-plex-gold/10 px-4 py-1.5 text-xs text-plex-text"
    >
      <span role="status" data-testid="rewind-pill-text">
        <span aria-hidden="true">⏪ </span>
        Rewound to <span className="font-semibold">{formatDayClockUk(rewind.to)}</span> UK
      </span>
      <button
        type="button"
        data-testid="rewind-pill-exit"
        onClick={() => setRewind(null)}
        className="rounded-full bg-plex-gold px-2.5 py-0.5 font-semibold text-plex-bg hover:bg-plex-gold/80"
      >
        Back to live
      </button>
    </div>
  );
}

RewindPill.propTypes = {
  rewind: PropTypes.shape({ to: PropTypes.string.isRequired }).isRequired,
};
