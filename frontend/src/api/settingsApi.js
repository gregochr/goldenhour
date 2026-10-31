import apiClient from './axiosClient.js';

const BASE_URL = '/api/user/settings';

/**
 * Fetches the current user's settings (profile + home location).
 * @returns {Promise<Object>} UserSettingsResponse
 */
export async function getSettings() {
  const response = await apiClient.get(BASE_URL);
  return response.data;
}

/**
 * Looks up a UK postcode, returning coordinates and place name.
 * @param {string} postcode
 * @returns {Promise<Object>} PostcodeLookupResult
 */
export async function lookupPostcode(postcode) {
  const response = await apiClient.post(`${BASE_URL}/home/lookup`, { postcode });
  return response.data;
}

/**
 * Finds the UK postcode nearest a device position (the "Use my location" link).
 *
 * <p>Answers the same shape as {@link lookupPostcode}. A 400 means no UK postcode was found there
 * (or the coordinates were not valid); a 502 means postcodes.io was unreachable. The caller tells
 * the two apart by `error.response.status`.
 *
 * @param {number} latitude degrees, -90..90
 * @param {number} longitude degrees, -180..180
 * @returns {Promise<Object>} PostcodeLookupResult
 */
export async function reverseLookupPostcode(latitude, longitude) {
  const response = await apiClient.post(`${BASE_URL}/home/reverse`, { latitude, longitude });
  return response.data;
}

/**
 * Saves the user's confirmed home location.
 * @param {string} postcode
 * @param {number} latitude
 * @param {number} longitude
 * @param {number|null} [localRadiusMiles] Local radius in miles (Ask's nearHome), or null to leave the
 *        stored value alone — omitting it is not a reset.
 * @returns {Promise<Object>} UserSettingsResponse
 */
export async function saveHome(postcode, latitude, longitude, localRadiusMiles = null) {
  const response = await apiClient.put(`${BASE_URL}/home`, {
    postcode, latitude, longitude, localRadiusMiles,
  });
  return response.data;
}

/**
 * Recalculates drive times from the user's home to all locations.
 * @returns {Promise<Object>} DriveTimeRefreshResponse
 */
export async function refreshDriveTimes() {
  const response = await apiClient.post(`${BASE_URL}/drive-times/refresh`);
  return response.data;
}

/**
 * Saves the caller's map colour preferences.
 *
 * <p>Its own endpoint rather than fields on `saveHome`: a colour preference is not home-derived,
 * so folding it into that request would deserialise the home fields to null and wipe a saved
 * postcode.
 *
 * @param {'temp'|'verdict'} mapColourScale which ramp paints the map
 * @returns {Promise<Object>} UserSettingsResponse
 */
export async function saveMapColourPreferences(mapColourScale) {
  const response = await apiClient.put(`${BASE_URL}/map-colours`, { mapColourScale });
  return response.data;
}

/**
 * Saves the caller's Map tab tide mode (map-mobile-sheet-plan.md M4).
 *
 * <p>Its own endpoint, matching `saveMapColourPreferences`'s reasoning: a tide mode is not
 * home-derived, so folding it into `saveHome` would deserialise the home fields to null and wipe a
 * saved postcode.
 *
 * @param {'auto'|'always'|'off'} mapTideMode which tide cues the phone Map tab shows
 * @returns {Promise<Object>} UserSettingsResponse
 */
export async function saveMapTideMode(mapTideMode) {
  const response = await apiClient.put(`${BASE_URL}/map-tide-mode`, { mapTideMode });
  return response.data;
}

/**
 * Records that the caller has just looked at the "Coming up" tab (plan D3/P5) — the badge's only
 * write. Its own endpoint rather than fields on `saveHome`, matching `saveMapColourPreferences`'s
 * precedent: this is not home-derived, so folding it into that request would deserialise the home
 * fields to null and wipe a saved postcode. No request body: "now" is the server's clock.
 *
 * @returns {Promise<Object>} UserSettingsResponse, with the freshly updated `comingUpLastSeenDate`
 */
export async function markComingUpSeen() {
  const response = await apiClient.put(`${BASE_URL}/coming-up-seen`);
  return response.data;
}

/**
 * Fetches the caller's reach — drive minutes and distance — for the whole enabled roster.
 *
 * <p>The per-user half of the window-first spot strip's two-contract join. It is deliberately
 * separate from `/api/briefing`, which is ETag-revalidated and therefore persists its body to a
 * browser HTTP cache JavaScript cannot evict on logout; `HttpCachingConfig` keeps
 * `/api/user/settings*` out of that filter for exactly this reason. The client joins the two on
 * `locationId`.
 *
 * <p>Every enabled location is present, with null figures for a caller who has saved no home
 * postcode — the normal first-run state. An absent figure means "unknown", never "out of reach".
 *
 * @returns {Promise<Array<{locationId: number, driveMinutes?: number, distanceMiles?: number}>>}
 */
export async function getReach() {
  const response = await apiClient.get(`${BASE_URL}/reach`);
  return response.data;
}

/**
 * Records that the caller has dismissed the one-time "set your home postcode" prompt (login-
 * registration plan §2 #3). Its own endpoint with no body, matching `markComingUpSeen`: "now" is the
 * server's clock, and folding it into `saveHome` would deserialise the home fields to null and wipe
 * a saved postcode. Idempotent on the server.
 *
 * <p>⚠️ The response is a settings response whose `homePlaceName` is ALWAYS null (the server does
 * not geocode on a write), exactly as `markComingUpSeen`'s is — apply it to the page's settings
 * record only through the hook's reporter, which keeps the place name already on record.
 *
 * @returns {Promise<Object>} UserSettingsResponse, with `postcodePromptDue` now false
 */
export async function markPostcodePromptDismissed() {
  // Bounded: the prompt that awaits this holds the app inert, and axios has no default timeout.
  const response = await apiClient.put(`${BASE_URL}/postcode-prompt-dismissed`, undefined, { timeout: 10000 });
  return response.data;
}
