import React, { useMemo, useState } from 'react';
import PropTypes from 'prop-types';
import { resetLocationFailures } from '../api/forecastApi.js';
import { apiErrorMessage } from '../utils/apiError.js';
import { formatTimestampUk } from '../utils/conversions';

/**
 * Displays alerts for locations with consecutive failures or that have been auto-disabled.
 *
 * A location is listed when it has a failure count above zero OR carries a disabled reason: the
 * scheduled-cycle auto-disable stores a reason, and a place switched off that way must stay
 * listed (with its Re-enable button) even if its counter was reset elsewhere. Re-enable calls the
 * reset endpoint, which also sets `enabled` back for a place that has a disabled reason.
 *
 * @param {object} props
 * @param {Array<object>} props.locations - All locations with failure tracking fields.
 * @param {function} props.onReenabledLocation - Called when a location is re-enabled.
 */
export default function LocationAlerts({ locations = [], onReenabledLocation = () => {} }) {
  const failingLocations = useMemo(
    () => locations.filter((loc) => loc.consecutiveFailures > 0 || Boolean(loc.disabledReason)),
    [locations],
  );

  // Names with a Re-enable request in flight (their button is disabled), and the server's
  // sentence for any that failed (shown on the row, cleared by the next attempt).
  const [pending, setPending] = useState(() => new Set());
  const [errors, setErrors] = useState({});

  async function handleReenableLocation(locationName) {
    setPending((prev) => new Set(prev).add(locationName));
    setErrors((prev) => ({ ...prev, [locationName]: null }));
    try {
      await resetLocationFailures(locationName);
      onReenabledLocation(locationName);
    } catch (err) {
      setErrors((prev) => ({
        ...prev,
        [locationName]: apiErrorMessage(err, 'Could not re-enable this location.'),
      }));
    } finally {
      setPending((prev) => {
        const next = new Set(prev);
        next.delete(locationName);
        return next;
      });
    }
  }

  if (failingLocations.length === 0) {
    return null; // Don't show anything if no failing locations
  }

  return (
    <div className="mb-6 rounded-lg bg-amber-900/20 border border-amber-700/50 p-4">
      <h3 className="text-sm font-semibold text-amber-400 mb-3 flex items-center gap-2">
        <span>⚠️</span>
        <span>Location Issues</span>
      </h3>
      <div className="space-y-2">
        {failingLocations.map((loc) => (
          <div
            key={loc.name}
            data-testid={`location-alert-${loc.name}`}
            className="flex items-start justify-between gap-3 p-3 bg-amber-900/10 rounded border border-amber-700/30"
          >
            <div className="flex-1 min-w-0">
              <div className="font-medium text-plex-text">{loc.name}</div>
              <div className="text-xs text-plex-text-secondary mt-1">
                {loc.disabledReason ? (
                  <>
                    <div data-testid="location-alert-reason">{loc.disabledReason}</div>
                    {loc.lastFailureAt && (
                      <div className="mt-1">
                        Last failure: {formatTimestampUk(loc.lastFailureAt)}
                      </div>
                    )}
                  </>
                ) : (
                  <div>
                    {loc.consecutiveFailures} consecutive failure
                    {loc.consecutiveFailures !== 1 ? 's' : ''}
                    {loc.lastFailureAt && (
                      <>
                        {' '}
                        ({formatTimestampUk(loc.lastFailureAt)})
                      </>
                    )}
                  </div>
                )}
              </div>
              {errors[loc.name] && (
                <div role="alert" className="mt-1 text-xs text-red-400">
                  {errors[loc.name]}
                </div>
              )}
            </div>
            {loc.disabledReason && (
              <button
                type="button"
                aria-label={`Re-enable ${loc.name}`}
                disabled={pending.has(loc.name)}
                onClick={() => handleReenableLocation(loc.name)}
                className="flex-shrink-0 px-3 py-1 text-xs font-medium bg-amber-700 text-amber-100 hover:bg-amber-600 rounded transition-colors disabled:opacity-50 disabled:pointer-events-none"
              >
                Re-enable
              </button>
            )}
          </div>
        ))}
      </div>
    </div>
  );
}

LocationAlerts.propTypes = {
  locations: PropTypes.arrayOf(
    PropTypes.shape({
      name: PropTypes.string.isRequired,
      consecutiveFailures: PropTypes.number,
      lastFailureAt: PropTypes.string,
      disabledReason: PropTypes.string,
    }),
  ),
  onReenabledLocation: PropTypes.func,
};
