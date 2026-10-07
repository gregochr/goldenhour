/**
 * Reads the Anthropic prompt-cache diagnostics the admin per-call rows carry.
 *
 * `GET /api/metrics/api-calls` serves `cacheDiagnostics` as the compact JSON string stored in
 * `api_call_log.cache_diagnostics` (e.g.
 * `{"status":"MISS","reason":"messages_changed","missedInputTokens":1234}`), or null when the call
 * carried none. This only reads and words what the backend wrote; it decides nothing about what a
 * reason means for the forecast.
 */

/**
 * Parses a call's stored diagnostics.
 *
 * @param {string|null|undefined} raw - the `cacheDiagnostics` field of an api-call row
 * @returns {{status: string, reason: string|null, missedInputTokens: number|null}|null} the
 *   diagnostics, or null when there are none or the string is not what the backend writes
 */
export function parseCacheDiagnostics(raw) {
  if (typeof raw !== 'string' || raw === '') {
    return null;
  }
  try {
    const parsed = JSON.parse(raw);
    if (!parsed || typeof parsed !== 'object' || typeof parsed.status !== 'string') {
      return null;
    }
    return {
      status: parsed.status,
      reason: typeof parsed.reason === 'string' ? parsed.reason : null,
      missedInputTokens:
        typeof parsed.missedInputTokens === 'number' ? parsed.missedInputTokens : null,
    };
  } catch {
    return null;
  }
}

/**
 * The compact badge for a call that carries diagnostics: what the cache did to its tokens (read,
 * or written) beside what the API said about why (`miss — <reason>`, or `pending`).
 *
 * @param {{cacheDiagnostics?: string|null, cacheReadInputTokens?: number|null,
 *   cacheCreationInputTokens?: number|null}} call - an api-call row
 * @returns {string|null} the badge text, or null when the call carries no diagnostics
 */
export function cacheBadge(call) {
  const diagnostics = parseCacheDiagnostics(call?.cacheDiagnostics);
  if (!diagnostics) {
    return null;
  }
  const parts = [];
  if (call.cacheReadInputTokens > 0) {
    parts.push(`read ${call.cacheReadInputTokens.toLocaleString()}`);
  }
  if (call.cacheCreationInputTokens > 0) {
    parts.push(`write ${call.cacheCreationInputTokens.toLocaleString()}`);
  }
  if (diagnostics.status === 'PENDING') {
    parts.push('pending');
  } else {
    const missed = diagnostics.missedInputTokens != null
      ? ` (${diagnostics.missedInputTokens.toLocaleString()} tok)`
      : '';
    parts.push(`miss — ${diagnostics.reason ?? 'unknown'}${missed}`);
  }
  return `cache: ${parts.join(' · ')}`;
}

/**
 * Tallies the diagnostics reasons across a run's calls, most common first.
 *
 * @param {Array<{cacheDiagnostics?: string|null}>} calls - api-call rows
 * @returns {Array<{label: string, count: number}>} one entry per reason (or `pending`)
 */
export function tallyCacheDiagnostics(calls) {
  const counts = new Map();
  calls.forEach((call) => {
    const diagnostics = parseCacheDiagnostics(call.cacheDiagnostics);
    if (!diagnostics) {
      return;
    }
    const label = diagnostics.status === 'PENDING' ? 'pending' : (diagnostics.reason ?? 'unknown');
    counts.set(label, (counts.get(label) ?? 0) + 1);
  });
  return [...counts.entries()]
    .map(([label, count]) => ({ label, count }))
    .sort((a, b) => b.count - a.count || a.label.localeCompare(b.label));
}
