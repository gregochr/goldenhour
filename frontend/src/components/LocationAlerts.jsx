import React, { useMemo } from 'react';
import PropTypes from 'prop-types';
import axios from 'axios';

/**
 * Displays alerts for locations with consecutive failures or that have been auto-disabled.
 *
 * @param {object} props
 * @param {Array<object>} props.locations - All locations with failure tracking fields.
 * @param {function} props.onReenabledLocation - Called when a location is re-enabled.
 */
export default function LocationAlerts({ locations, onReenabledLocation }) {
  const failingLocations = useMemo(
    () => locations.filter((loc) => loc.consecutiveFailures > 0),
    [locations],
  );

  async function handleReenableLocation(locationName) {
    try {
      await axios.put(`/api/locations/${encodeURIComponent(locationName)}/reset-failures`);
      onReenabledLocation(locationName);
    } catch (err) {
      console.error('Failed to re-enable location:', err);
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
          <div key={loc.name} className="flex items-start justify-between gap-3 p-3 bg-amber-900/10 rounded border border-amber-700/30">
            <div className="flex-1 min-w-0">
              <div className="font-medium text-gray-100">{loc.name}</div>
              <div className="text-xs text-gray-400 mt-1">
                {loc.disabledReason ? (
                  <>
                    <div>{loc.disabledReason}</div>
                    {loc.lastFailureAt && (
                      <div className="mt-1">
                        Last failure: {new Date(loc.lastFailureAt).toLocaleString()}
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
                        ({new Date(loc.lastFailureAt).toLocaleString()})
                      </>
                    )}
                  </div>
                )}
              </div>
            </div>
            {loc.disabledReason && (
              <button
                type="button"
                onClick={() => handleReenableLocation(loc.name)}
                className="flex-shrink-0 px-3 py-1 text-xs font-medium bg-amber-700 text-amber-100 hover:bg-amber-600 rounded transition-colors"
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

LocationAlerts.defaultProps = {
  locations: [],
  onReenabledLocation: () => {},
};
