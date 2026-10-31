import { useOptimistic, useState, useTransition } from 'react';
import { setLocationEnabled } from '../api/forecastApi.js';
import { apiErrorMessage } from '../utils/apiError.js';

/**
 * Turning a location on or off: optimistic over the stored list, reverting if the server refuses, with the server's
 * sentence (e.g. "Turn Northumberland on first.") kept for the caller to show.
 *
 * <p>Pending is per location ({@code pendingIds}): one place's toggle in flight never disables another's button.
 * When the change succeeded but the re-read of the list fails, the server's state is kept: the toggled place is
 * merged into the stored list (the reply when it carries the place, else the intended value) rather than reverting.
 *
 * @param {object} options
 * @param {Array<object>} options.locations the stored locations
 * @param {Function} options.setLocations sets the stored locations
 * @param {() => Promise<void>} options.refreshLocations re-reads the locations
 * @param {() => Promise<void>} options.refreshAreas re-reads the areas (the "no sub-region" count
 *   only covers enabled locations)
 * @param {() => void} [options.onChanged] told after a successful change (it changes what readers see)
 * @returns {{locations: Array<object>, toggle: (loc: object) => void, error: {id: number, message: string}|null,
 *   pendingIds: Set<number>, clearError: () => void}}
 */
export default function useLocationToggle({
  locations, setLocations, refreshLocations, refreshAreas, onChanged = undefined,
}) {
  const [optimisticLocations, addOptimisticLocation] = useOptimistic(locations, (current, toggledId) =>
    current.map((loc) => (loc.id === toggledId ? { ...loc, enabled: !loc.enabled } : loc)),
  );
  const [, startToggleTransition] = useTransition();
  const [pendingIds, setPendingIds] = useState(() => new Set());
  // The server's sentence for a refused toggle, with the location it belongs to so it never shows under another.
  const [error, setError] = useState(null);

  function setPending(id, on) {
    setPendingIds((prev) => {
      const next = new Set(prev);
      if (on) next.add(id);
      else next.delete(id);
      return next;
    });
  }

  function toggle(loc) {
    setError(null);
    setPending(loc.id, true);
    startToggleTransition(async () => {
      addOptimisticLocation(loc.id);
      try {
        const reply = await setLocationEnabled(loc.id, !loc.enabled);
        // Keep what the server now holds even if the re-read below fails.
        const served = reply && typeof reply === 'object' && reply.id === loc.id ? reply : {};
        setLocations((current) => current.map((l) => (
          l.id === loc.id ? { ...l, ...served, enabled: !loc.enabled } : l
        )));
        await refreshLocations();
        await refreshAreas();
        if (onChanged) onChanged();
      } catch (err) {
        console.error('Failed to toggle location enabled:', err);
        setError({ id: loc.id, message: apiErrorMessage(err, 'Could not change this location.') });
      } finally {
        setPending(loc.id, false);
      }
    });
  }

  return {
    locations: optimisticLocations, toggle, error, pendingIds, clearError: () => setError(null),
  };
}
