import PropTypes from 'prop-types';
import {
  failedRunsText, failureThresholdWord, isAutoDisabled, lastFailedSentence,
} from '../../utils/locationAdmin.js';

/** The sentences shown when the server refused with nothing usable to say. */
const RESET_FAILED = "Couldn't reset the failure count.";
const TURN_BACK_ON_FAILED = "Couldn't turn this location back on.";

/**
 * The amber failure box at the top of a panel (data-tabs-redesign-plan.md §6.2), for a place the failure tracking has
 * touched:
 * <ul>
 *   <li>a place with failed runs and no stored reason: "{n} failed runs in a row", when it last failed, and (while it is
 *       on) that a third failure turns it off, with {@code Reset failure count};
 *   <li>a place the server turned off after failures: "Turned off after {n} failed runs", the stored reason, and
 *       {@code Turn back on}.
 * </ul>
 * Both buttons call the same reset endpoint (it clears the counters, and re-enables only an auto-disabled place). A
 * request in flight disables the button; a refusal shows the server's own sentence (for a place in a switched-off
 * region: "Turn {region} on first.") and the next attempt clears it. The parent owns the request.
 *
 * @param {object} props
 * @param {object} props.location the location
 * @param {boolean} props.pending a reset request for this location is in flight
 * @param {string|null} props.error the server's sentence for the last refused reset
 * @param {(fallback: string) => void} props.onReset resets the failure count; called with the sentence to show if
 *   the server says nothing usable
 */
export default function LocationFailureBox({ location, pending, error, onReset }) {
  const autoDisabled = isAutoDisabled(location);
  const failures = location.consecutiveFailures ?? 0;
  if (!autoDisabled && failures <= 0) return null;

  const lastFailed = lastFailedSentence(location);
  return (
    <div
      className="border-b border-plex-border bg-verdict-marginal/10 px-[18px] py-3 text-[13px] text-plex-text max-[639px]:px-4"
      data-testid="location-failure-box"
    >
      {autoDisabled ? (
        <>
          <p>
            <strong className="font-semibold" data-testid="location-failure-title">
              {failures > 0 ? `Turned off after ${failedRunsText(failures)}` : 'Turned off after failed runs'}
            </strong>
          </p>
          <p className="mt-0.5 text-plex-text-secondary" data-testid="location-failure-reason">
            {location.disabledReason}
          </p>
          {lastFailed && <p className="mt-0.5 text-plex-text-secondary" data-testid="location-failure-last">{lastFailed}</p>}
        </>
      ) : (
        <>
          <p>
            <strong className="font-semibold" data-testid="location-failure-title">
              {failures} failed {failures === 1 ? 'run' : 'runs'} in a row
            </strong>
          </p>
          <p className="mt-0.5 text-plex-text-secondary">
            {lastFailed}
            {location.enabled && ` A ${failureThresholdWord()} failure turns the location off.`}
          </p>
        </>
      )}
      <button
        type="button"
        className="btn-secondary mt-2 text-xs max-[639px]:min-h-11"
        onClick={() => onReset(autoDisabled ? TURN_BACK_ON_FAILED : RESET_FAILED)}
        disabled={pending}
        data-testid={autoDisabled ? 'location-turn-back-on' : 'location-reset-failures'}
      >
        {autoDisabled ? 'Turn back on' : 'Reset failure count'}
      </button>
      {error && (
        <p className="mt-1.5 text-xs text-plex-error" role="alert" data-testid="location-failure-error">{error}</p>
      )}
    </div>
  );
}

LocationFailureBox.propTypes = {
  location: PropTypes.object.isRequired,
  pending: PropTypes.bool.isRequired,
  error: PropTypes.string,
  onReset: PropTypes.func.isRequired,
};
