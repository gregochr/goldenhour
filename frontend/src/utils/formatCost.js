/**
 * Formats micro-dollars to GBP using the stored exchange rate.
 * Falls back to legacy costPence if microDollars not available.
 *
 * @param {number|null} microDollars - cost in micro-dollars (1 dollar = 1,000,000)
 * @param {number|null} exchangeRate - GBP per USD rate
 * @param {number|null} legacyPence - legacy cost in 1/10th pence (fallback)
 * @returns {string} formatted cost string
 */
export function formatCostGbp(microDollars, exchangeRate, legacyPence) {
  if (microDollars != null && microDollars > 0 && exchangeRate) {
    const gbp = (microDollars / 1_000_000) * exchangeRate;
    if (gbp < 0.01) return `${(gbp * 100).toFixed(3)}p`;
    return `\u00a3${gbp.toFixed(4)}`;
  }
  if (legacyPence != null && legacyPence > 0) {
    return `\u00a3${(legacyPence / 1000).toFixed(3)}`;
  }
  return '\u2014';
}

/**
 * Formats micro-dollars to USD.
 *
 * @param {number|null} microDollars - cost in micro-dollars
 * @returns {string} formatted cost string
 */
export function formatCostUsd(microDollars) {
  if (microDollars == null || microDollars === 0) return '\u2014';
  const dollars = microDollars / 1_000_000;
  if (dollars < 0.01) return `${(dollars * 100).toFixed(3)}\u00a2`;
  return `$${dollars.toFixed(4)}`;
}

/**
 * Formats a token count with locale-aware thousands separators.
 *
 * @param {number|null} count - token count
 * @returns {string} formatted token count
 */
export function formatTokens(count) {
  if (count == null || count === 0) return '\u2014';
  return count.toLocaleString();
}
