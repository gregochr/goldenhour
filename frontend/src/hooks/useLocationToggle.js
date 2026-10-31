import { useOptimistic, useState, useTransition } from 'react';
import { setLocationEnabled } from '../api/forecastApi.js';
import { apiErrorMessage } from '../utils/apiError.js';

/**
 * Turning a location on or off: optimistic over the stored list, reverting if the server refuses, with the server's
 * sentence (e.g. "Turn Northumberland on first.") kept for the caller to show.
 *
 * @param {object} options
 * @param {Array<object>} options.locations the stored locations
 * @param {() => Promise<void>} options.refreshLocations re-reads the locations
 * @param {(bump?: boolean) => Promise<void>} options.refreshAreas re-reads the areas (the "no sub-region" count
 *   only covers enabled locations)
 * @param {() => void} [options.onChanged] told after a successful change (it changes what readers see)
 * @returns {{locations: Array<object>, toggle: (loc: object) => void, error: {id: number, message: string}|null,
 *   pending: boolean}}
 */
export default function useLocationToggle({ locations, refreshLocations, refreshAreas, onChanged = undefined }) {
  const [optimisticLocations, addOptimisticLocation] = useOptimistic(locations, (current, toggledId) =>
    current.map((loc) => (loc.id === toggledId ? { ...loc, enabled: !loc.enabled } : loc)),
  );
  const [pending, startToggleTransition] = useTransition();
  // The server's sentence for a refused toggle, with the location it belongs to so it never shows under another.
  const [error, setError] = useState(null);

  function toggle(loc) {
    setError(null);
    startToggleTransition(async () => {
      addOptimisticLocation(loc.id);
      try {
        await setLocationEnabled(loc.id, !loc.enabled);
        await refreshLocations();
        await refreshAreas();
        if (onChanged) onChanged();
      } catch (err) {
        console.error('Failed to toggle location enabled:', err);
        setError({ id: loc.id, message: apiErrorMessage(err, 'Could not change this location.') });
      }
    });
  }

  return { locations: optimisticLocations, toggle, error, pending };
}
