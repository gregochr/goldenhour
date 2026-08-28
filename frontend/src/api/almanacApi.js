import apiClient from './axiosClient.js';

/**
 * The one horizon this client ever asks for.
 *
 * <p><b>It is a constant rather than a parameter, and that is load-bearing.</b>
 * {@code AlmanacService} caches the whole feed in a SINGLE slot keyed by
 * {@code (buildDate, days)} — a second caller asking for a different horizon does not get its own
 * entry, it evicts the first one, and then every request from either caller rebuilds all six
 * sources from scratch. Exporting the number instead of accepting one makes that impossible to do
 * by accident.
 */
export const ALMANAC_DAYS = 90;

/**
 * Fetches the "Coming up" almanac feed — the wrapped {@code ComingUpResponse} covering the next
 * {@link ALMANAC_DAYS} days.
 *
 * <p>{@code entries} are <em>spans</em> ({@code startDate}/{@code endDate}, both inclusive),
 * ascending by start date then by span length, and eligible under Plan's boundary — an entry
 * wholly inside Plan's four days is not in this list. They may start before today or end past the
 * horizon: a tide run or a supermoon window that straddles an edge is reported with its true
 * extent rather than clipped, so "this began yesterday" does not render as "starts today".
 *
 * <p><b>Do not unwrap this to {@code entries}.</b> The response also carries {@code builtFor},
 * {@code conditions}, {@code counts} and {@code bands} — later phases ride the same payload, and a
 * caller that unwraps here would have to be found and fixed again when they start populating.
 *
 * <p><b>No try/catch, deliberately.</b> The endpoint is Bearer with no role gate
 * ({@code AlmanacController}), so there is no 403 to swallow the way {@code nlcApi} swallows one
 * for a LITE user, and it always returns the wrapper — an empty {@code entries} list at worst,
 * never a 204. A failure here is a real failure and the caller renders it.
 *
 * <p>The path is ETag-revalidated ({@code HttpCachingConfig}) and carries no per-user data, so a
 * repeat load costs a 304 and the browser reconstructs the body below the XHR layer. Do not add
 * {@code If-None-Match} by hand — axios would reject the bare 304 as an error.
 *
 * @returns {Promise<object>} the wrapped feed — see {@code ComingUpResponse}
 */
export async function getAlmanac() {
  const response = await apiClient.get('/api/almanac', { params: { days: ALMANAC_DAYS } });
  return response.data;
}
