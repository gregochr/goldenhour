import { useState } from 'react';
import { resetLocationFailures } from '../api/forecastApi.js';
import { apiErrorMessage } from '../utils/apiError.js';

/**
 * Resetting a location's failure count (which also turns an auto-disabled place back on): a per-location pending
 * state, the server's sentence for a refusal (cleared by the next attempt), and a callback when it worked.
 *
 * @param {(locationName: string) => void} onDone called after a successful reset
 * @param {string} [fallback] the sentence to show when the server said nothing usable
 * @returns {{pending: Set<string>, errors: Object<string, string|null>,
 *   reset: (locationName: string) => Promise<void>}}
 */
export default function useLocationFailureReset(onDone, fallback = 'Could not re-enable this location.') {
  // Names with a request in flight (their button is disabled), and the server's sentence for any that failed.
  const [pending, setPending] = useState(() => new Set());
  const [errors, setErrors] = useState({});

  async function reset(locationName) {
    setPending((prev) => new Set(prev).add(locationName));
    setErrors((prev) => ({ ...prev, [locationName]: null }));
    try {
      await resetLocationFailures(locationName);
      onDone(locationName);
    } catch (err) {
      setErrors((prev) => ({ ...prev, [locationName]: apiErrorMessage(err, fallback) }));
    } finally {
      setPending((prev) => {
        const next = new Set(prev);
        next.delete(locationName);
        return next;
      });
    }
  }

  return { pending, errors, reset };
}
