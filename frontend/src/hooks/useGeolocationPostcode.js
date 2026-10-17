import { useCallback, useState } from 'react';
import { reverseLookupPostcode } from '../api/settingsApi.js';

/** The reader refused the browser's location prompt (GeolocationPositionError.PERMISSION_DENIED). */
export const LOCATION_DENIED = 'Location access is off. Enter your postcode instead.';
/** The device was located but the server found no UK postcode there (the reverse endpoint's 400). */
export const LOCATION_NOT_UK = "We couldn't find a UK postcode for your location.";
/** Everything else: position unavailable, a timeout, an unreachable lookup, a throwing browser. */
export const LOCATION_FAILED = "Couldn't get your location. Enter your postcode instead.";

/** `getCurrentPosition`'s options: a coarse, cached fix is plenty for a postcode. */
const POSITION_OPTIONS = { enableHighAccuracy: false, timeout: 10000, maximumAge: 600000 };

/** Whether this browser can be asked for a position at all. The "Use my location" link hides when not. */
export function isGeolocationAvailable() {
  return typeof navigator !== 'undefined' && navigator.geolocation != null;
}

/**
 * "Use my location": asks the browser for a position, then the server for the postcode there
 * (`POST /api/user/settings/home/reverse`), and answers the same result shape as a typed lookup so
 * the caller can land it in the same confirmation row.
 *
 * <p>The error strings are chosen by the browser's NUMERIC {@code GeolocationPositionError} codes
 * (1 permission denied; 2 unavailable and 3 timeout both read as the generic sentence), because jsdom
 * has neither {@code navigator.geolocation} nor the error class and a test hands over {@code {code: N}};
 * and by the reverse endpoint's status (400 = no UK postcode there; a 502 or anything else = the
 * generic sentence). The reader's own coordinates are never logged or kept: they go from the browser
 * to the one request and no further.
 *
 * @returns {{available: boolean, locate: function(): Promise<?Object>, locating: boolean,
 *            error: ?string, clearError: function(): void}} {@code locate} resolves to the lookup
 *            result, or to null after setting {@code error}
 */
export default function useGeolocationPostcode() {
  const [locating, setLocating] = useState(false);
  const [error, setError] = useState(null);

  const clearError = useCallback(() => setError(null), []);

  const locate = useCallback(() => new Promise((resolve) => {
    setError(null);
    setLocating(true);
    const finish = (result, message) => {
      setLocating(false);
      setError(message);
      resolve(result);
    };
    try {
      navigator.geolocation.getCurrentPosition(
        async (position) => {
          try {
            const result = await reverseLookupPostcode(
              position.coords.latitude, position.coords.longitude);
            finish(result, null);
          } catch (err) {
            finish(null, err?.response?.status === 400 ? LOCATION_NOT_UK : LOCATION_FAILED);
          }
        },
        (failure) => finish(null, failure?.code === 1 ? LOCATION_DENIED : LOCATION_FAILED),
        POSITION_OPTIONS,
      );
    } catch {
      finish(null, LOCATION_FAILED);
    }
  }), []);

  return { available: isGeolocationAvailable(), locate, locating, error, clearError };
}
