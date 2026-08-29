/**
 * The "Coming up" badge's per-user-join derivation (plan D3/D12) — the ONE client class licensed
 * to compute something the shared `/api/almanac` payload cannot answer for itself.
 *
 * <h2>Why this lives on the client at all</h2>
 *
 * <p>`lastSeenAt` is per-user; `/api/almanac` is ETag-shared and user-independent (D2), so "what is
 * new *to you*" has no servable answer on that payload — exactly the reasoning that keeps reach off
 * `GET /api/briefing` (CLAUDE.md's Backend-heavy bullet). This module is the entire membership of
 * that class: `isNew` per entry, the badge `{band, count}`, and the since-line's entry selection.
 * Chip counts, month grouping and sparkline geometry are a DIFFERENT, already-licensed class
 * (presentation arithmetic over served fields — see `comingUpFeed.js`'s own class doc) and do not
 * belong here even though they also live on the client.
 *
 * <h2>What counts as an arrival</h2>
 *
 * <p>An entry "arrives" the day its served `enteredWindow` first exceeds the reader's stored
 * `comingUpLastSeenDate` (both ISO `YYYY-MM-DD` strings, safe to compare lexicographically — D3).
 * A null `comingUpLastSeenDate` means "never opened the tab", which must render as nothing new (a
 * brand-new account opens quiet) — never as "everything is new".
 *
 * <h2>What counts as badge-worthy</h2>
 *
 * <p>Three filters, all mandatory (design §6, plan D4):
 * <ul>
 *   <li>{@code kind === 'ALMANAC'} — a FORECAST entry never badges (design: "forecast topics do
 *       not badge on arrival").</li>
 *   <li>{@code interim !== true} — a bucketed/unmeasurable score is not a badge-worthy claim (plan
 *       §13's `interim` field doc: "exclude interim entries from clearing a band regardless of how
 *       high bits reads").</li>
 *   <li>{@code bits} clears the served `announce` edge, lower-inclusive (D4's `>=` convention,
 *       matching {@code SurpriseScore.bandOf} on the backend).</li>
 * </ul>
 *
 * <p>The two badge SHAPES (count vs solid {@code ◆}) are the top two bands of one surface, not two
 * kinds of judgement (README §3/§6): interrupt drops the number because above that edge there is
 * only ever one thing in play.
 */

/**
 * Whether `entry` arrived since `lastSeenDate`.
 *
 * @param {{enteredWindow: string}} entry        a served {@code ComingUpEntry}
 * @param {?string}                 lastSeenDate the reader's stored civil date (`YYYY-MM-DD`), or
 *                                               null/undefined when they have never opened the tab
 * @returns {boolean}
 */
export function isNewEntry(entry, lastSeenDate) {
  if (!lastSeenDate) return false;
  return entry.enteredWindow > lastSeenDate;
}

/**
 * Every new arrival that clears the announce edge, sorted by descending bits — the ordering the
 * since-line and the band derivation both need (the highest-scoring arrival decides both).
 *
 * @param {Array}   entries      the wire's {@code ComingUpEntry[]}, or undefined
 * @param {?object} bands        the served {@code ComingUpBands}, or null/undefined before it
 *                                arrives — no bands means no badge can be derived
 * @param {?string} lastSeenDate the reader's stored civil date, or null
 * @returns {Array} qualifying entries, highest `bits` first
 */
export function qualifyingArrivals(entries, bands, lastSeenDate) {
  if (!Array.isArray(entries) || !bands || typeof bands.announce !== 'number') return [];
  return entries
    .filter((entry) => entry.kind === 'ALMANAC'
      && entry.interim !== true
      && typeof entry.bits === 'number'
      && entry.bits >= bands.announce
      && isNewEntry(entry, lastSeenDate))
    .sort((a, b) => b.bits - a.bits);
}

/**
 * The tab badge's state (design §6): {@code null} when nothing qualifies, otherwise the band the
 * HIGHEST-scoring qualifying arrival reached and, for `announce`, how many qualify in total.
 * `interrupt` carries no count — the design's "above 9.5 there is only ever one thing in play, so
 * the badge drops the number".
 *
 * @param {Array}   entries      the wire's {@code ComingUpEntry[]}, or undefined
 * @param {?object} bands        the served {@code ComingUpBands}, or null/undefined
 * @param {?string} lastSeenDate the reader's stored civil date, or null
 * @returns {?{band: 'announce'|'interrupt', count: ?number}}
 */
export function deriveBadge(entries, bands, lastSeenDate) {
  const qualifying = qualifyingArrivals(entries, bands, lastSeenDate);
  if (qualifying.length === 0) return null;
  const topBits = qualifying[0].bits;
  if (typeof bands.interrupt === 'number' && topBits >= bands.interrupt) {
    return { band: 'interrupt', count: null };
  }
  return { band: 'announce', count: qualifying.length };
}

/**
 * The since-line's entry (design §6's "the badge must land somewhere"): the highest-bits qualifying
 * arrival, whose `{bits, title, dates, scoreNote}` the line renders VERBATIM — the client never
 * composes score prose (plan §13: {@code scoreNote} is server-authored for exactly this reason).
 *
 * @param {Array}   entries      the wire's {@code ComingUpEntry[]}, or undefined
 * @param {?object} bands        the served {@code ComingUpBands}, or null/undefined
 * @param {?string} lastSeenDate the reader's stored civil date, or null
 * @returns {?object} the entry to render the since-line from, or null when nothing qualifies
 */
export function selectSinceEntry(entries, bands, lastSeenDate) {
  const qualifying = qualifyingArrivals(entries, bands, lastSeenDate);
  return qualifying.length > 0 ? qualifying[0] : null;
}
