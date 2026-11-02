import PropTypes from 'prop-types';
import { textButtonClass } from '../users/userFormClasses.js';
import { formatClockUk } from '../../utils/runsView.js';

/**
 * The Runs header (runs-deep-dive-plan.md F3, extracted from the old list pane with its test ids): the "Runs" heading,
 * "Updated {HH:MM} · refreshes every minute", Refresh and Start a run. "Updated" and Refresh follow the active view's
 * fetch; the caller passes that view's time and refresh. The heading takes focus after a progress panel's Dismiss, and
 * the Start button takes it back when the Start a run panel closes.
 *
 * @param {object} props
 * @param {{current: HTMLElement|null}} props.headingRef the "Runs" heading
 * @param {{current: HTMLElement|null}} [props.startButtonRef] the Start a run button
 * @param {Date|null} [props.updatedAt] when the active view last loaded (the real clock)
 * @param {boolean} [props.refreshes] whether the view refreshes by itself (a past day does not, so it does not say so)
 * @param {() => void} props.onRefresh called by Refresh
 * @param {() => void} props.onStart called by Start a run
 */
export default function RunsHeader({ headingRef, startButtonRef = undefined, updatedAt = null, refreshes = true, onRefresh, onStart }) {
  return (
    <div className="flex flex-wrap items-center justify-between gap-x-4 gap-y-2">
      <div className="flex min-w-0 flex-wrap items-baseline gap-x-3 gap-y-1">
        <h2
          ref={headingRef}
          tabIndex={-1}
          className="text-sm font-semibold text-plex-text focus-visible:outline-2 focus-visible:outline-plex-gold"
          data-testid="runs-heading"
        >
          Runs
        </h2>
        {updatedAt && (
          <span className="font-mono text-[11px] text-plex-text-muted" data-testid="runs-updated">
            Updated {formatClockUk(updatedAt)}{refreshes ? ' \u00b7 refreshes every minute' : ''}
          </span>
        )}
      </div>
      <div className="ml-auto flex items-center gap-2 max-[639px]:flex-wrap">
        <button type="button" className={textButtonClass} onClick={onRefresh} data-testid="runs-refresh-btn">
          Refresh
        </button>
        <button
          ref={startButtonRef}
          type="button"
          className="btn-primary shrink-0 text-xs max-[639px]:min-h-11"
          onClick={onStart}
          data-testid="start-run-btn"
        >
          Start a run
        </button>
      </div>
    </div>
  );
}

RunsHeader.propTypes = {
  headingRef: PropTypes.shape({ current: PropTypes.any }).isRequired,
  startButtonRef: PropTypes.shape({ current: PropTypes.any }),
  updatedAt: PropTypes.instanceOf(Date),
  refreshes: PropTypes.bool,
  onRefresh: PropTypes.func.isRequired,
  onStart: PropTypes.func.isRequired,
};
